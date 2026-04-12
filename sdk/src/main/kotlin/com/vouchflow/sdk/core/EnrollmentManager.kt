package com.vouchflow.sdk.core

import android.content.Context
import com.vouchflow.sdk.VouchflowConfig
import com.vouchflow.sdk.VouchflowError
import com.vouchflow.sdk.crypto.KeystoreKeyManager
import com.vouchflow.sdk.crypto.PlayIntegrityProvider
import com.vouchflow.sdk.internal.VouchflowLogger
import com.vouchflow.sdk.network.VouchflowAPIClient
import com.vouchflow.sdk.network.models.EnrollRequest
import com.vouchflow.sdk.storage.AccountManagerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Detects enrollment state and performs enrollment atomically.
 *
 * A [Mutex] serialises concurrent [ensureEnrolled] calls — if two [com.vouchflow.sdk.Vouchflow.verify]
 * calls race on a fresh install, only one enrollment network request is made.
 *
 * ## Enrollment state machine
 * ```
 * FRESH_ENROLLMENT  → no device_token, no Keystore key → enroll from scratch
 * SKIP_ENROLLMENT   → device_token exists, key exists, key valid → nothing to do
 * REINSTALL         → device_token exists, no key → re-enroll with existing token
 * CORRUPTED         → no device_token, key exists → delete orphan key, enroll fresh
 * KEY_INVALIDATED   → device_token exists, key exists, KeyPermanentlyInvalidatedException
 *                     → re-enroll with existing token; new key pair, reputation preserved
 * ```
 *
 * ## Atomicity
 * 1. Generate Keystore keypair
 * 2. Write pending placeholder to AccountManager (`pending_dvt_<idempotencyKey>`) BEFORE network call
 * 3. Obtain Play Integrity token (non-fatal on failure)
 * 4. POST /v1/enroll
 * 5a. Success → write real device_token, clear pending placeholder
 * 5b. Failure → retain placeholder, retry on next [ensureEnrolled] call
 */
internal class EnrollmentManager(
    private val config: VouchflowConfig,
    private val context: Context,
    private val store: AccountManagerStore,
    private val keystoreKeyManager: KeystoreKeyManager,
    private val apiClient: VouchflowAPIClient
) {
    private val mutex = Mutex()

    private sealed class EnrollmentState {
        object SkipEnrollment : EnrollmentState()
        object FreshEnrollment : EnrollmentState()
        data class Reinstall(val existingDeviceToken: String) : EnrollmentState()
        object Corrupted : EnrollmentState()
        data class KeyInvalidated(val existingDeviceToken: String) : EnrollmentState()
    }

    // ── Public ────────────────────────────────────────────────────────────────

    /**
     * Ensures the device is enrolled. No-op if already enrolled. Idempotent — safe to call
     * before every [com.vouchflow.sdk.Vouchflow.verify]; returns immediately in [EnrollmentState.SkipEnrollment].
     */
    suspend fun ensureEnrolled() = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Retry a pending enrollment from a previous failed attempt first.
            val pendingToken = store.readPendingToken()
            if (pendingToken != null) {
                retryPendingEnrollment(pendingToken)
                return@withContext
            }

            when (val state = detectState()) {
                is EnrollmentState.SkipEnrollment -> return@withContext

                is EnrollmentState.FreshEnrollment ->
                    performEnrollment(reason = "fresh_enrollment", existingDeviceToken = null)

                is EnrollmentState.Reinstall ->
                    performEnrollment(reason = "reinstall", existingDeviceToken = state.existingDeviceToken)

                is EnrollmentState.Corrupted -> {
                    VouchflowLogger.warn("[VouchflowSDK] Corrupted enrollment state — orphan Keystore key with no device token. Re-enrolling.")
                    keystoreKeyManager.deleteKey()
                    performEnrollment(reason = "fresh_enrollment", existingDeviceToken = null)
                }

                is EnrollmentState.KeyInvalidated -> {
                    VouchflowLogger.warn("[VouchflowSDK] KEY_INVALIDATED — biometric configuration changed. Re-enrolling with existing device token.")
                    keystoreKeyManager.deleteKey()
                    performEnrollment(reason = "key_invalidated", existingDeviceToken = state.existingDeviceToken)
                }
            }
        }
    }

    // ── State detection ───────────────────────────────────────────────────────

    private fun detectState(): EnrollmentState {
        val hasDeviceToken = store.deviceTokenExists()
        val hasKey = keystoreKeyManager.keyExists()

        return when {
            !hasDeviceToken && !hasKey -> EnrollmentState.FreshEnrollment
            hasDeviceToken && !hasKey  -> EnrollmentState.Reinstall(store.readDeviceToken()!!)
            !hasDeviceToken && hasKey  -> EnrollmentState.Corrupted
            else -> {
                // hasDeviceToken && hasKey — check key validity
                if (!keystoreKeyManager.isKeyValid()) {
                    EnrollmentState.KeyInvalidated(store.readDeviceToken()!!)
                } else {
                    EnrollmentState.SkipEnrollment
                }
            }
        }
    }

    // ── Enrollment ────────────────────────────────────────────────────────────

    private suspend fun performEnrollment(reason: String, existingDeviceToken: String?) {
        val idempotencyKey = "ik_${UUID.randomUUID().toString().lowercase()}"

        // Step 1: Generate keypair in Keystore.
        val (_, publicKeyBase64, strongboxBacked) = try {
            keystoreKeyManager.generateKeyPair()
        } catch (e: Exception) {
            throw VouchflowError.EnrollmentFailed(e)
        }

        // Step 2: Write pending placeholder BEFORE network call (crash / kill safety).
        val placeholder = "pending_dvt_$idempotencyKey"
        store.writePendingToken(placeholder)

        // Step 3: Obtain Play Integrity token (non-fatal on failure).
        val integrityToken = PlayIntegrityProvider.attest(context, idempotencyKey)
        val attestationPayload = integrityToken?.let {
            EnrollRequest.AttestationPayload(token = it)
        }

        // Step 4: POST /v1/enroll.
        val enrollRequest = EnrollRequest(
            idempotencyKey = idempotencyKey,
            customerId = config.customerId,
            platform = "android",
            reason = reason,
            attestation = attestationPayload,
            publicKey = publicKeyBase64,
            deviceToken = existingDeviceToken,
            strongboxBacked = strongboxBacked
        )

        val response = try {
            apiClient.enroll(enrollRequest)
        } catch (e: Exception) {
            // Step 5b: Leave placeholder — retried on next ensureEnrolled() call.
            VouchflowLogger.warn("[VouchflowSDK] Enrollment network call failed. Will retry on next launch. Error: ${e.message}")
            throw VouchflowError.EnrollmentFailed(e)
        }

        // Step 5a: Commit real device token and clear placeholder.
        store.writeDeviceToken(response.deviceToken)
        store.deletePendingToken()

        VouchflowLogger.debug("[VouchflowSDK] Enrollment complete. attestation_verified=${response.attestationVerified}, confidence_ceiling=${response.confidenceCeiling}")
    }

    // ── Pending enrollment retry ──────────────────────────────────────────────

    /**
     * Retries a previously failed enrollment using the idempotency key embedded in the placeholder.
     *
     * The keypair already exists in the Keystore from the previous attempt. If the key is gone
     * (unlikely but possible on force-clear), we fall through to fresh enrollment. The server's
     * 24-hour idempotency window deduplicates if the first request actually succeeded.
     */
    private suspend fun retryPendingEnrollment(pendingToken: String) {
        val prefix = "pending_dvt_"
        if (!pendingToken.startsWith(prefix)) {
            store.deletePendingToken()
            performEnrollment(reason = "fresh_enrollment", existingDeviceToken = null)
            return
        }

        val idempotencyKey = pendingToken.removePrefix(prefix)

        if (!keystoreKeyManager.keyExists()) {
            store.deletePendingToken()
            performEnrollment(reason = "fresh_enrollment", existingDeviceToken = null)
            return
        }

        val existingDeviceToken = store.readDeviceToken()
        val reason = if (existingDeviceToken != null) "reinstall" else "fresh_enrollment"

        // Regenerating public key from Keystore for the retry request.
        val publicKeyBase64 = extractPublicKeyFromKeystore() ?: run {
            store.deletePendingToken()
            keystoreKeyManager.deleteKey()
            performEnrollment(reason = "fresh_enrollment", existingDeviceToken = null)
            return
        }

        val integrityToken = PlayIntegrityProvider.attest(context, idempotencyKey)
        val attestationPayload = integrityToken?.let {
            EnrollRequest.AttestationPayload(token = it)
        }

        val strongboxBacked = keystoreKeyManager.isStrongBoxBacked()

        val enrollRequest = EnrollRequest(
            idempotencyKey = idempotencyKey,
            customerId = config.customerId,
            platform = "android",
            reason = reason,
            attestation = attestationPayload,
            publicKey = publicKeyBase64,
            deviceToken = existingDeviceToken,
            strongboxBacked = strongboxBacked
        )

        val response = try {
            apiClient.enroll(enrollRequest)
        } catch (e: Exception) {
            throw VouchflowError.EnrollmentFailed(e)
        }

        store.writeDeviceToken(response.deviceToken)
        store.deletePendingToken()
    }

    private fun extractPublicKeyFromKeystore(): String? {
        return try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            val entry = ks.getEntry("com.vouchflow.sdk.key_v1", null) as? java.security.KeyStore.PrivateKeyEntry
                ?: return null
            val ecKey = entry.certificate.publicKey as java.security.interfaces.ECPublicKey
            val x = ecKey.w.affineX.toByteArray()
            val y = ecKey.w.affineY.toByteArray()
            val uncompressed = ByteArray(65)
            uncompressed[0] = 0x04
            fun copyCoord(coord: ByteArray, offset: Int) {
                val stripped = coord.dropWhile { it == 0.toByte() }.toByteArray()
                val start = offset + (32 - stripped.size.coerceAtMost(32))
                stripped.copyInto(uncompressed, destinationOffset = start, startIndex = (stripped.size - 32).coerceAtLeast(0))
            }
            copyCoord(x, 1)
            copyCoord(y, 33)
            android.util.Base64.encodeToString(uncompressed, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
