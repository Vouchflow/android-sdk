package com.vouchflow.sdk.network.models

import com.google.gson.annotations.SerializedName

// ── Initiate fallback ─────────────────────────────────────────────────────────

internal data class FallbackRequest(
    /** Null if enrollment failed — server creates a minimal unlinked device record. */
    @SerializedName("device_token") val deviceToken: String?,
    /** Plain-text email. The server delivers the OTP here. */
    @SerializedName("email") val email: String,
    /** SHA-256 hex of the plain-text email. Used server-side for rate limiting. */
    @SerializedName("email_hash") val emailHash: String,
    @SerializedName("reason") val reason: String
)

internal data class FallbackResponse(
    @SerializedName("fallback_session_id") val fallbackSessionId: String,
    @SerializedName("method") val method: String,
    /** ISO 8601 string. */
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("session_state") val sessionState: String
)

// ── Complete fallback (OTP submission) ────────────────────────────────────────

internal data class FallbackCompleteRequest(
    @SerializedName("otp") val otp: String,
    @SerializedName("device_token") val deviceToken: String?
)

internal data class FallbackCompleteResponse(
    @SerializedName("verified") val verified: Boolean,
    @SerializedName("confidence") val confidence: String,
    @SerializedName("session_state") val sessionState: String,
    @SerializedName("fallback_signals") val fallbackSignals: FallbackSignalsPayload
) {
    internal data class FallbackSignalsPayload(
        @SerializedName("ip_consistent") val ipConsistent: Boolean,
        @SerializedName("disposable_email_domain") val disposableEmailDomain: Boolean,
        @SerializedName("device_has_prior_verifications") val deviceHasPriorVerifications: Boolean,
        @SerializedName("email_domain_age_days") val emailDomainAgeDays: Int?,
        @SerializedName("otp_attempts") val otpAttempts: Int,
        @SerializedName("time_to_complete_seconds") val timeToCompleteSeconds: Int
    )
}
