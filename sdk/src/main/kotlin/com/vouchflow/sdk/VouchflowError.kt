package com.vouchflow.sdk

/**
 * All errors surfaced to the developer by the Vouchflow SDK.
 *
 * The SDK throws rather than using callbacks — catch what you care about and let everything
 * else propagate.
 */
sealed class VouchflowError : Exception() {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** [Vouchflow.configure] was not called before using the SDK. */
    object NotConfigured : VouchflowError()

    /** The API key provided to [VouchflowConfig] is not a recognised Vouchflow key. */
    object InvalidApiKey : VouchflowError()

    // ── Enrollment ────────────────────────────────────────────────────────────

    /**
     * Device enrollment failed. The SDK will retry automatically on the next call.
     * Verification can still proceed in degraded mode, or the developer can hard-fail.
     */
    data class EnrollmentFailed(val enrollmentCause: Throwable? = null) : VouchflowError()

    /**
     * Play Integrity attestation is not available on this device (no Google Play Services,
     * de-Googled ROM, Amazon Fire). Enrollment continues without attestation;
     * confidence ceiling is set to `medium` for the device lifetime.
     */
    object AttestationUnavailable : VouchflowError()

    /**
     * The SDK could not read from or write to the AccountManager store.
     * Typically indicates a permission or authenticator registration problem.
     */
    object AccountStoreAccessDenied : VouchflowError()

    // ── Biometric ─────────────────────────────────────────────────────────────

    /** Biometric hardware is not available or no biometrics are enrolled on this device. */
    object BiometricUnavailable : VouchflowError()

    /**
     * The user explicitly cancelled the biometric prompt.
     * Show a retry button. Call [Vouchflow.requestFallback] if the user opts into email fallback.
     *
     * @param sessionId Pass to [Vouchflow.requestFallback] to initiate email OTP for this session.
     */
    data class BiometricCancelled(val sessionId: String) : VouchflowError()

    /**
     * The biometric attempt failed (wrong face/finger, lockout, hardware error).
     * Do not auto-retry more than once. Offer fallback or hard-fail.
     *
     * @param sessionId Pass to [Vouchflow.requestFallback] to initiate email OTP for this session.
     */
    data class BiometricFailed(val sessionId: String) : VouchflowError()

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * The verification session expired before the challenge was signed.
     * The SDK automatically retried once using the server-provided retry session.
     * This error is thrown only when the retry session also expired.
     */
    object SessionExpiredRepeatedly : VouchflowError()

    // ── Confidence ────────────────────────────────────────────────────────────

    /**
     * The device cannot meet the [minimumConfidence] threshold specified in
     * [Vouchflow.verify]. No fallback is initiated automatically.
     */
    object MinimumConfidenceUnmet : VouchflowError()

    // ── Network ───────────────────────────────────────────────────────────────

    /** A network connection could not be established. */
    object NetworkUnavailable : VouchflowError()

    /** The Vouchflow API returned an unexpected error response. */
    data class ServerError(
        val statusCode: Int,
        val code: String?,
        val serverMessage: String?
    ) : VouchflowError()

    /**
     * The server's TLS certificate did not match the configured pins.
     * This may indicate a MITM attack or a pin rotation that has not been deployed to the SDK.
     */
    object PinningFailure : VouchflowError()

    // ── Internal (not part of the public API surface) ─────────────────────────

    /**
     * Internal carrier used by [com.vouchflow.sdk.network.VouchflowAPIClient] to pass
     * retry session data up to [com.vouchflow.sdk.core.VerificationManager].
     * Developers never see this — it is always translated before crossing the public boundary.
     */
    internal data class SessionExpiredInternal(
        val retrySessionId: String,
        val retryChallenge: String
    ) : VouchflowError()
}
