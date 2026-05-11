package dev.vouchflow.sdk.core

import android.os.Build
import android.util.Base64
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.vouchflow.sdk.Confidence
import dev.vouchflow.sdk.SignedBundle
import dev.vouchflow.sdk.VouchflowConfig
import dev.vouchflow.sdk.VouchflowError
import dev.vouchflow.sdk.crypto.JCSCanonicalizer
import dev.vouchflow.sdk.crypto.KeystoreKeyManager
import dev.vouchflow.sdk.internal.VouchflowLogger
import dev.vouchflow.sdk.network.VouchflowAPIClient
import dev.vouchflow.sdk.network.models.SignCompleteRequest
import dev.vouchflow.sdk.network.models.SignInitiateRequest
import dev.vouchflow.sdk.network.models.SignInitiateResponse
import dev.vouchflow.sdk.storage.AccountManagerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives the `signPayload` ceremony per the signPayload RFC §4.3.
 *
 * Flow per call:
 *   1. Canonicalize payload (RFC 8785 JCS) → bytes
 *   2. POST /v1/sign  → server returns 32-byte challenge bound to the payload
 *   3. signing_input = canonical_payload || challenge
 *   4. BiometricPrompt + CryptoObject around a SHA256withECDSA Signature
 *      initialized for the enrolled Keystore key
 *   5. signature.update(signing_input); signature.sign() → DER bytes
 *   6. POST /v1/sign/:session_id/complete → JWS bundle
 *
 * No App Attest re-attestation step on Android — Keystore Attestation is
 * one-time at enrollment, and the per-call signature against the same
 * Keystore key already proves "same hardware key as at enrollment."
 */
internal class SignPayloadManager(
    private val config: VouchflowConfig,
    private val store: AccountManagerStore,
    private val keystoreKeyManager: KeystoreKeyManager,
    private val enrollmentManager: EnrollmentManager,
    private val apiClient: VouchflowAPIClient,
) {

    /** Sign an arbitrary JSON-serializable payload. */
    suspend fun signPayload(
        activity: FragmentActivity,
        payload: Any?,
        context: String,
        minimumConfidence: Confidence,
    ): SignedBundle {
        val canonical: String = try {
            // Accept maps, lists, primitives directly. Strings starting with
            // { or [ are treated as JSON to support Gson/Moshi callers.
            when (payload) {
                is String -> if (payload.trimStart().let { it.startsWith("{") || it.startsWith("[") }) {
                    JCSCanonicalizer.canonicalizeJson(payload)
                } else {
                    JCSCanonicalizer.canonicalize(payload)
                }
                else -> JCSCanonicalizer.canonicalize(payload)
            }
        } catch (e: Exception) {
            throw VouchflowError.CanonicalizationFailed(e)
        }
        return signCanonicalized(activity, canonical, context, minimumConfidence)
    }

    /** For tests and the harness: callers that have already canonicalized. */
    suspend fun signCanonicalized(
        activity: FragmentActivity,
        canonicalized: String,
        context: String,
        minimumConfidence: Confidence,
    ): SignedBundle {
        // Auto-enroll on first call.
        try {
            enrollmentManager.ensureEnrolled()
        } catch (e: VouchflowError) {
            throw e
        } catch (e: Exception) {
            throw VouchflowError.EnrollmentFailed(e)
        }

        val deviceToken = withContext(Dispatchers.IO) { store.readDeviceToken() }
            ?: throw VouchflowError.EnrollmentFailed()

        val idempotencyKey = "sk_" + UUID.randomUUID().toString().replace("-", "")

        // 1. Initiate sign session.
        val initRequest = SignInitiateRequest(
            deviceToken = deviceToken,
            context = context,
            canonicalizedPayload = canonicalized,
            minimumConfidence = minimumConfidence.apiValue,
            idempotencyKey = idempotencyKey,
        )
        val init: SignInitiateResponse = withContext(Dispatchers.IO) { apiClient.initiateSign(initRequest) }

        // 2. Build signing_input = canonical_payload || challenge.
        val canonicalBytes = canonicalized.toByteArray(Charsets.UTF_8)
        val challengeBytes = Base64.decode(init.challenge, Base64.DEFAULT)
        val signingInput = ByteArray(canonicalBytes.size + challengeBytes.size).also {
            System.arraycopy(canonicalBytes, 0, it, 0, canonicalBytes.size)
            System.arraycopy(challengeBytes, 0, it, canonicalBytes.size, challengeBytes.size)
        }

        // 3. Biometric-gated signature via CryptoObject.
        val cryptoObject = withContext(Dispatchers.IO) { keystoreKeyManager.createCryptoObject() }
            ?: throw VouchflowError.EnrollmentFailed()

        val authResult = authenticateBiometric(activity, cryptoObject)
        val signature = authResult.signature ?: throw VouchflowError.BiometricFailed(init.sessionId)
        signature.update(signingInput)
        val signed = signature.sign()
        val signedBase64 = Base64.encodeToString(signed, Base64.NO_WRAP)

        // 4. Complete the session.
        val response = withContext(Dispatchers.IO) {
            apiClient.completeSign(
                init.sessionId,
                SignCompleteRequest(
                    deviceToken = deviceToken,
                    signedChallenge = signedBase64,
                ),
            )
        }

        // 5. Defense in depth: enforce minConfidence client-side too.
        val achieved = Confidence.fromApi(response.confidence)
        if (achieved.rank < minimumConfidence.rank) {
            throw VouchflowError.MinimumConfidenceUnmet
        }

        return SignedBundle(
            payload = canonicalized,
            context = context,
            assertion = response.assertion,
            signingDeviceId = response.signingDeviceId,
            deviceToken = response.deviceToken,
            confidence = achieved,
            signedAt = Instant.parse(response.signedAt),
            platform = response.platform,
        )
    }

    // ── Biometric prompt ────────────────────────────────────────────────────────

    private data class BiometricAuthResult(
        val signature: java.security.Signature?,
        val biometricUsed: Boolean,
    )

    private suspend fun authenticateBiometric(
        activity: FragmentActivity,
        cryptoObject: BiometricPrompt.CryptoObject,
    ): BiometricAuthResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val sig = result.cryptoObject?.signature
                        val biometricUsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC
                        } else true
                        if (continuation.isActive) {
                            continuation.resume(BiometricAuthResult(sig, biometricUsed))
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        val err: VouchflowError = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED ->
                                VouchflowError.BiometricCancelled("")
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                                VouchflowError.BiometricUnavailable
                            else -> VouchflowError.BiometricFailed("")
                        }
                        continuation.resumeWithException(err)
                    }
                },
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authorize signature")
                .setSubtitle("Confirm to sign with this device")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            try {
                prompt.authenticate(promptInfo, cryptoObject)
            } catch (e: Exception) {
                VouchflowLogger.warn("[VouchflowSDK] BiometricPrompt.authenticate threw: $e")
                if (continuation.isActive) {
                    continuation.resumeWithException(VouchflowError.BiometricFailed(""))
                }
            }
        }
    }
}

// ── Confidence ordering helper ──────────────────────────────────────────────

private val Confidence.rank: Int
    get() = when (this) {
        Confidence.LOW -> 0
        Confidence.MEDIUM -> 1
        Confidence.HIGH -> 2
    }
