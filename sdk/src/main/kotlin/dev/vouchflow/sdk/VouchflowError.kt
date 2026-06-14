package dev.vouchflow.sdk

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
     */
    data class BiometricCancelled(val sessionId: String) : VouchflowError()

    /**
     * The biometric attempt failed (wrong face/finger, lockout, hardware error).
     * Do not auto-retry more than once. Offer fallback or hard-fail.
     */
    data class BiometricFailed(val sessionId: String) : VouchflowError()

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * The verification session expired before the challenge was signed.
     * The SDK automatically retried once using the server-provided retry session.
     * This error is thrown only when the retry session also expired.
     */
    object SessionExpiredRepeatedly : VouchflowError()

    /**
     * [Vouchflow.requestFallback] was called but there is no active session to fall back from.
     * Call [Vouchflow.verify] first; only call [Vouchflow.requestFallback] after catching
     * [BiometricCancelled] or [BiometricFailed].
     */
    object NoActiveSession : VouchflowError()

    // ── Confidence ────────────────────────────────────────────────────────────

    /**
     * The device cannot meet the [minimumConfidence] threshold specified in
     * [Vouchflow.verify]. No fallback is initiated automatically.
     */
    object MinimumConfidenceUnmet : VouchflowError()

    // ── signPayload ───────────────────────────────────────────────────────────

    /**
     * The payload passed to [Vouchflow.signPayload] could not be canonicalized
     * as JSON (contains non-JSON types, NaN/Infinity, etc.).
     */
    data class CanonicalizationFailed(val canonicalizationCause: Throwable? = null) : VouchflowError()

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
     * The device's persistent token belongs to a different App row on the server.
     *
     * Surfaces the server's `device_not_owned` 403. The likeliest cause is an
     * integrator who created two App rows in the Vouchflow dashboard for what
     * the server-side model considers a single app with two key types
     * (sandbox + live). Devices enrolled under App A cannot be verified
     * through App B's key, even within the same customer.
     *
     * Recovery options for the integrator:
     *
     * 1. **Consolidate to one App.** In the dashboard, pick the App whose
     *    keys the production build ships with as the canonical one. Use its
     *    sandbox key in dev builds, its live key in prod builds.
     * 2. **Transfer existing devices to the canonical App.** The server
     *    exposes an admin-keyed endpoint:
     *
     *    ```
     *    POST /v1/customers/:id/apps/:appId/devices/transfer
     *    Authorization: Bearer $ADMIN_KEY
     *    { "fromAppId": "...", "deviceTokens": ["...", "..."] }
     *    ```
     *
     *    Bulk-moves Device + Verification rows from `fromAppId` to the
     *    destination App, within the same customer.
     * 3. **Wipe and re-enroll.** A `Vouchflow.reset()` followed by `verify()`
     *    will mint a fresh device under whichever App the SDK's current API
     *    key resolves to. Loses the device's history (network signals,
     *    confidence ceiling, etc.).
     *
     * Catch this case explicitly rather than letting it fall through to a
     * generic `ServerError(403, "device_not_owned", …)` — the recovery is
     * always integrator-side, never end-user-side, and the SDK can't decide
     * which option (1/2/3) is right.
     */
    object DeviceClaimedElsewhere : VouchflowError()

    /**
     * Enrollment failed because the device's public key is already
     * registered under a different customer or app.
     *
     * Surfaces the server's `public_key_already_registered` 409. Almost
     * always one of:
     *
     * - The same hardware Keystore key has been used by another tenant
     *   (genuine cross-tenant claim — talk to support if unexpected).
     * - The integrator is running the SDK against an App whose dashboard
     *   row was deleted and re-created; the orphan Device row still holds
     *   the public key. Resolution: support to release the orphan.
     *
     * Notably **not** the same as the old SDK 2.2.x behaviour: as of server
     * v59 the same-tenant re-token case (e.g. SDK reset() with a surviving
     * hardware Keystore key) succeeds with the existing device token, so
     * this error fires only for genuine cross-tenant collisions.
     */
    object PublicKeyAlreadyRegistered : VouchflowError()

    /**
     * The server's TLS certificate did not match the configured pins.
     *
     * Either a MITM attack, a Let's Encrypt rotation that left the SDK's pinned values
     * stale, or an integrator misconfiguration. Inspect the payload to tell which:
     *
     * - [configuredPins] is what you passed in [VouchflowConfig].
     * - [servedSpkiSha256] is what the server's chain actually carries today.
     *   Compare the two — if your configured leaf doesn't appear in the served list,
     *   you're pinning to a stale value and need a refresh (see
     *   [VouchflowConfig.leafCertificatePin] KDoc for the openssl one-liner).
     *
     * @param hostname Host that failed pinning (e.g. `api.vouchflow.dev`).
     * @param configuredPins Raw base64 SPKI SHA-256 pins from [VouchflowConfig],
     *   `sha256/` prefix stripped.
     * @param servedSpkiSha256 Raw base64 SPKI SHA-256 of each certificate in the
     *   chain the server presented. Parsed from OkHttp's failure message; empty if
     *   the message couldn't be parsed (e.g. placeholder-pins failure path).
     * @param pinningCause Underlying OkHttp/JSSE exception (named to avoid shadowing
     *   [Throwable.cause] from the [Exception] base class).
     */
    data class PinningFailure(
        val hostname: String,
        val configuredPins: List<String>,
        val servedSpkiSha256: List<String>,
        val pinningCause: Throwable?
    ) : VouchflowError() {
        override fun toString(): String =
            "PinningFailure(host=$hostname, configured=$configuredPins, served=$servedSpkiSha256)"
    }

    // ── Internal (not part of the public API surface) ─────────────────────────

    /**
     * Internal carrier used by [dev.vouchflow.sdk.network.VouchflowAPIClient] to pass
     * retry session data up to [dev.vouchflow.sdk.core.VerificationManager].
     * Developers never see this — it is always translated before crossing the public boundary.
     */
    internal data class SessionExpiredInternal(
        val retrySessionId: String,
        val retryChallenge: String
    ) : VouchflowError()
}
