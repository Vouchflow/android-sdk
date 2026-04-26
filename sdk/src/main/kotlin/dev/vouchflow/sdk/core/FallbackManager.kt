package dev.vouchflow.sdk.core

import dev.vouchflow.sdk.FallbackReason
import dev.vouchflow.sdk.FallbackResult
import dev.vouchflow.sdk.FallbackSignals
import dev.vouchflow.sdk.FallbackVerificationResult
import dev.vouchflow.sdk.VouchflowError
import dev.vouchflow.sdk.network.VouchflowAPIClient
import dev.vouchflow.sdk.network.models.FallbackCompleteRequest
import dev.vouchflow.sdk.network.models.FallbackRequest
import dev.vouchflow.sdk.storage.AccountManagerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant

/**
 * Manages the email OTP fallback path.
 *
 * Called by the developer after catching [dev.vouchflow.sdk.VouchflowError.BiometricFailed]
 * or [dev.vouchflow.sdk.VouchflowError.BiometricCancelled] from [dev.vouchflow.sdk.Vouchflow.verify].
 * The developer decides whether to offer fallback — the SDK never auto-triggers it.
 */
internal class FallbackManager(
    private val store: AccountManagerStore,
    private val apiClient: VouchflowAPIClient
) {

    // ── Initiate fallback ─────────────────────────────────────────────────────

    /**
     * Initiates email OTP fallback for the given verification session.
     *
     * The SDK SHA-256 hashes the email internally — do not pre-hash it.
     * Neither the plain-text email nor the hash is stored beyond this request.
     */
    suspend fun requestFallback(
        sessionId: String,
        email: String,
        reason: FallbackReason
    ): FallbackResult = withContext(Dispatchers.IO) {
        val deviceToken = store.readDeviceToken()
        val emailHash = sha256Hex(email)

        val request = FallbackRequest(
            deviceToken = deviceToken,
            email = email,
            emailHash = emailHash,
            reason = reason.apiValue
        )
        val response = apiClient.initiateFallback(sessionId, request)

        val expiresAt = runCatching { Instant.parse(response.expiresAt) }
            .getOrElse { Instant.now().plusSeconds(300) }

        FallbackResult(
            fallbackSessionId = response.fallbackSessionId,
            expiresAt = expiresAt
        )
    }

    // ── Submit OTP ────────────────────────────────────────────────────────────

    /**
     * Submits the 6-digit OTP entered by the user.
     *
     * Uses [fallbackSessionId] from [FallbackResult] as the path parameter (same
     * `/v1/verify/{id}/complete` endpoint, keyed by the fallback session).
     */
    suspend fun submitOtp(sessionId: String, otp: String): FallbackVerificationResult =
        withContext(Dispatchers.IO) {
            val deviceToken = store.readDeviceToken()
            val request = FallbackCompleteRequest(otp = otp, deviceToken = deviceToken)
            val response = apiClient.completeFallback(sessionId, request)

            FallbackVerificationResult(
                verified = response.verified,
                confidence = dev.vouchflow.sdk.Confidence.fromApi(response.confidence),
                sessionState = response.sessionState,
                fallbackSignals = FallbackSignals(
                    ipConsistent = response.fallbackSignals.ipConsistent,
                    disposableEmailDomain = response.fallbackSignals.disposableEmailDomain,
                    deviceHasPriorVerifications = response.fallbackSignals.deviceHasPriorVerifications,
                    emailDomainAgeDays = response.fallbackSignals.emailDomainAgeDays,
                    otpAttempts = response.fallbackSignals.otpAttempts,
                    timeToCompleteSeconds = response.fallbackSignals.timeToCompleteSeconds
                )
            )
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
