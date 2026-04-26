package dev.vouchflow.sdk.network.models

import com.google.gson.annotations.SerializedName

// ── Initiate verification ─────────────────────────────────────────────────────

internal data class VerifyRequest(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("context") val context: String,
    /** Optional. If the device cannot reach this level, the server returns verification_impossible. */
    @SerializedName("minimum_confidence") val minimumConfidence: String?
)

internal data class VerifyResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("challenge") val challenge: String,
    /** ISO 8601 string. Parsed to Instant at the VerificationManager boundary. */
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("session_state") val sessionState: String
)

// ── Complete verification (primary path) ──────────────────────────────────────

internal data class CompleteVerificationRequest(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("signed_challenge") val signedChallenge: String,
    @SerializedName("biometric_used") val biometricUsed: Boolean
)

internal data class CompleteVerificationResponse(
    @SerializedName("verified") val verified: Boolean,
    @SerializedName("confidence") val confidence: String?,
    @SerializedName("session_state") val sessionState: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("device_age_days") val deviceAgeDays: Int,
    @SerializedName("network_verifications") val networkVerifications: Int,
    /** ISO 8601 string. Null if never seen cross-app. */
    @SerializedName("first_seen") val firstSeen: String?,
    @SerializedName("signals") val signals: SignalsPayload,
    @SerializedName("fallback_used") val fallbackUsed: Boolean,
    @SerializedName("context") val context: String
) {
    internal data class SignalsPayload(
        @SerializedName("keychain_persistent") val keychainPersistent: Boolean,
        @SerializedName("biometric_used") val biometricUsed: Boolean,
        @SerializedName("cross_app_history") val crossAppHistory: Boolean,
        @SerializedName("anomaly_flags") val anomalyFlags: List<String>,
        @SerializedName("attestation_verified") val attestationVerified: Boolean
    )
}
