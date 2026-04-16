package com.vouchflow.sdk.core

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vouchflow.sdk.*
import com.vouchflow.sdk.crypto.ChallengeProcessor
import com.vouchflow.sdk.crypto.KeystoreKeyManager
import com.vouchflow.sdk.internal.VouchflowLogger
import com.vouchflow.sdk.network.VouchflowAPIClient
import com.vouchflow.sdk.network.models.CompleteVerificationRequest
import com.vouchflow.sdk.network.models.VerifyRequest
import com.vouchflow.sdk.network.models.VerifyResponse
import com.vouchflow.sdk.storage.AccountManagerStore
import com.vouchflow.sdk.storage.SessionCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.Signature
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Orchestrates the complete verification flow.
 *
 * ## Happy path
 * 1. [EnrollmentManager.ensureEnrolled] — no-op if already enrolled
 * 2. `POST /v1/verify` → session_id + challenge
 * 3. [BiometricPrompt] with [BiometricPrompt.CryptoObject] → authenticated [Signature]
 * 4. [ChallengeProcessor.sign] → signed_challenge
 * 5. `POST /v1/verify/{id}/complete` → [VouchflowResult]
 *
 * ## Session expiry handling
 * If the session expires while the app is backgrounded:
 * - First expiry: use `retry_session_id` + `retry_challenge` from the 410 response transparently.
 * - Second expiry: throw [VouchflowError.SessionExpiredRepeatedly].
 *
 * ## Backgrounding during biometric
 * [BiometricPrompt] cancels with `ERROR_CANCELED` when the app is backgrounded mid-prompt.
 * The SDK catches this as [BiometricSystemCancelledInternal], waits for foreground via
 * [SessionManager.waitForForeground], and silently re-presents the biometric prompt.
 * The outer session expiry loop handles the case where the session times out while waiting.
 *
 * ## Activity parameter
 * [BiometricPrompt] requires a [ComponentActivity] (via the 1.2.0-alpha biometric
 * library). The developer passes their Activity on each [verify] call. Do not cache the Activity.
 */
internal class VerificationManager(
    private val config: VouchflowConfig,
    private val store: AccountManagerStore,
    private val keystoreKeyManager: KeystoreKeyManager,
    private val challengeProcessor: ChallengeProcessor,
    private val sessionCache: SessionCache,
    private val enrollmentManager: EnrollmentManager,
    private val apiClient: VouchflowAPIClient
) {

    /**
     * The session ID from the most recently initiated (but not yet completed) verification.
     * Set when a session is created, updated if the session is retried, cleared on success.
     * Retained through biometric failures so [Vouchflow.requestFallback] can use it without
     * the developer having to extract and pass it from the thrown error.
     */
    @Volatile internal var pendingFallbackSessionId: String? = null

    // ── Verify ────────────────────────────────────────────────────────────────

    suspend fun verify(
        activity: FragmentActivity,
        context: VerificationContext,
        minimumConfidence: Confidence?
    ): VouchflowResult {
        // Step 1: Ensure enrolled (mutex-serialised — no-op if already enrolled).
        try {
            enrollmentManager.ensureEnrolled()
        } catch (e: VouchflowError) {
            throw e
        } catch (e: Exception) {
            throw VouchflowError.EnrollmentFailed(e)
        }

        // Step 2: Read device token (must exist after ensureEnrolled).
        val deviceToken = withContext(Dispatchers.IO) { store.readDeviceToken() }
            ?: throw VouchflowError.EnrollmentFailed()

        // Step 3: Initiate session.
        val verifyRequest = VerifyRequest(
            deviceToken = deviceToken,
            context = context.apiValue,
            minimumConfidence = minimumConfidence?.apiValue
        )
        var sessionResponse = withContext(Dispatchers.IO) { apiClient.initiateVerification(verifyRequest) }
        pendingFallbackSessionId = sessionResponse.sessionId

        sessionCache.store(SessionCache.CachedSession(
            sessionId = sessionResponse.sessionId,
            challenge = sessionResponse.challenge,
            expiresAt = parseInstant(sessionResponse.expiresAt),
            expiryCount = 0
        ))

        // Steps 4–5: Sign + submit loop — handles up to 2 consecutive session expirations.
        var expiryCount = 0
        while (expiryCount < 2) {
            val (signedChallenge, biometricUsed) = try {
                signChallengeWithBiometric(activity, sessionResponse.challenge, sessionResponse.sessionId)
            } catch (e: Exception) {
                // Biometric errors and other failures bubble directly — no retry at this level.
                sessionCache.clear()
                throw e
            }

            val completeRequest = CompleteVerificationRequest(
                deviceToken = deviceToken,
                signedChallenge = signedChallenge,
                biometricUsed = biometricUsed
            )

            try {
                val response = withContext(Dispatchers.IO) {
                    apiClient.completeVerification(sessionResponse.sessionId, completeRequest)
                }
                sessionCache.clear()
                pendingFallbackSessionId = null // session fully resolved — no fallback possible
                return mapResult(response, deviceToken, context)
            } catch (e: VouchflowError.SessionExpiredInternal) {
                expiryCount++
                VouchflowLogger.debug("[VouchflowSDK] Session expired (expiry #$expiryCount). Using retry session.")
                sessionResponse = VerifyResponse(
                    sessionId = e.retrySessionId,
                    challenge = e.retryChallenge,
                    expiresAt = Instant.now().plusSeconds(60).toString(),
                    sessionState = "INITIATED"
                )
                pendingFallbackSessionId = e.retrySessionId // follow the retry chain
                sessionCache.store(SessionCache.CachedSession(
                    sessionId = e.retrySessionId,
                    challenge = e.retryChallenge,
                    expiresAt = Instant.now().plusSeconds(60),
                    expiryCount = expiryCount
                ))
            }
        }

        sessionCache.clear()
        throw VouchflowError.SessionExpiredRepeatedly
    }

    // ── Biometric signing ─────────────────────────────────────────────────────

    /**
     * Presents [BiometricPrompt] with a [BiometricPrompt.CryptoObject] and signs the challenge
     * with the authenticated [Signature]. Retries silently on system-level cancellation
     * (app backgrounded mid-prompt).
     *
     * @return Pair of (base64-encoded DER signature, biometricUsed flag).
     */
    private suspend fun signChallengeWithBiometric(
        activity: FragmentActivity,
        challengeBase64: String,
        sessionId: String
    ): Pair<String, Boolean> {
        val cryptoObject = withContext(Dispatchers.IO) { keystoreKeyManager.createCryptoObject() }
            ?: throw VouchflowError.EnrollmentFailed()

        while (true) {
            val authResult = try {
                authenticateBiometric(activity, cryptoObject, sessionId)
            } catch (e: BiometricSystemCancelledInternal) {
                VouchflowLogger.debug("[VouchflowSDK] Biometric interrupted by system. Waiting for foreground.")
                SessionManager.waitForForeground()
                continue // Re-present biometric prompt on next iteration.
            }

            val signature = authResult.signature
                ?: throw VouchflowError.BiometricFailed(sessionId)

            val signed = withContext(Dispatchers.IO) {
                challengeProcessor.sign(challengeBase64, signature)
            }

            return Pair(signed, authResult.biometricUsed)
        }
    }

    /**
     * Suspends until the user completes (or fails) biometric authentication.
     *
     * Must be called from a coroutine. Internally switches to [Dispatchers.Main] to construct
     * and present [BiometricPrompt], which is a main-thread-only operation.
     *
     * Error code mapping:
     * - `ERROR_CANCELED`          → [BiometricSystemCancelledInternal] (app backgrounded — retry)
     * - `ERROR_USER_CANCELED`     → [VouchflowError.BiometricCancelled] (user dismissed prompt)
     * - `ERROR_NEGATIVE_BUTTON`   → [VouchflowError.BiometricCancelled] (API < 30: "Use another method")
     * - `ERROR_HW_UNAVAILABLE` et al. → [VouchflowError.BiometricUnavailable]
     * - anything else             → [VouchflowError.BiometricFailed]
     */
    private suspend fun authenticateBiometric(
        activity: FragmentActivity,
        cryptoObject: BiometricPrompt.CryptoObject,
        sessionId: String
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
                        } else {
                            true
                        }
                        if (continuation.isActive) {
                            continuation.resume(BiometricAuthResult(signature = sig, biometricUsed = biometricUsed))
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        val error: Exception = when (errorCode) {
                            BiometricPrompt.ERROR_CANCELED ->
                                BiometricSystemCancelledInternal() // App backgrounded — retry after foreground.
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                                VouchflowError.BiometricCancelled(sessionId)
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                                VouchflowError.BiometricUnavailable
                            else ->
                                VouchflowError.BiometricFailed(sessionId)
                        }
                        continuation.resumeWithException(error)
                    }

                    override fun onAuthenticationFailed() {
                        // Single failed attempt — BiometricPrompt continues showing and manages retries
                        // internally. Do NOT resume the continuation here.
                    }
                }
            )

            // On API 30+: allow biometric OR device credential (PIN/pattern/password).
            // setNegativeButtonText is incompatible with DEVICE_CREDENTIAL — omit it on API 30+;
            // the system provides its own dismiss action.
            // On API < 30: device credential is not supported for per-use CryptoObject keys;
            // keep biometric-only with an explicit negative button.
            val promptInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Verify your identity")
                    .setDescription("Use your biometric or device PIN to verify")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
            } else {
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Verify your identity")
                    .setDescription("Use your biometric credential to verify")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Cancel")
                    .build()
            }

            prompt.authenticate(promptInfo, cryptoObject)

            continuation.invokeOnCancellation {
                prompt.cancelAuthentication()
            }
        }
    }

    // ── Result mapping ────────────────────────────────────────────────────────

    private fun mapResult(
        response: com.vouchflow.sdk.network.models.CompleteVerificationResponse,
        deviceToken: String,
        context: VerificationContext
    ): VouchflowResult {
        return VouchflowResult(
            verified = response.verified,
            confidence = response.confidence?.let { Confidence.fromApi(it) } ?: Confidence.LOW,
            deviceToken = deviceToken,
            deviceAgeDays = response.deviceAgeDays,
            networkVerifications = response.networkVerifications,
            firstSeen = response.firstSeen?.let { runCatching { Instant.parse(it) }.getOrNull() },
            signals = VouchflowSignals(
                persistentToken = response.signals.keychainPersistent,
                biometricUsed = response.signals.biometricUsed,
                crossAppHistory = response.signals.crossAppHistory,
                anomalyFlags = response.signals.anomalyFlags,
                attestationVerified = response.signals.attestationVerified
            ),
            fallbackUsed = response.fallbackUsed,
            context = context
        )
    }

    // ── Private types ─────────────────────────────────────────────────────────

    private data class BiometricAuthResult(
        val signature: Signature?,
        val biometricUsed: Boolean
    )
}

/** Internal signal from [BiometricPrompt] ERROR_CANCELED (system-level, app backgrounded). */
private class BiometricSystemCancelledInternal : Exception()

/** Parse an ISO-8601 string to [java.time.Instant], falling back to 60s from now on failure. */
private fun parseInstant(iso: String): java.time.Instant =
    runCatching { java.time.Instant.parse(iso) }.getOrDefault(java.time.Instant.now().plusSeconds(60))
