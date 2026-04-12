package com.vouchflow.sdk

import java.time.Instant

// ── Enumerations ──────────────────────────────────────────────────────────────

/** Confidence level returned by a completed verification. */
enum class Confidence(internal val apiValue: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    companion object {
        internal fun fromApi(value: String): Confidence =
            entries.firstOrNull { it.apiValue == value } ?: LOW
    }
}

/** The action the user is performing when [Vouchflow.verify] is called. */
enum class VerificationContext(internal val apiValue: String) {
    SIGNUP("signup"),
    LOGIN("login"),
    SENSITIVE_ACTION("sensitive_action");
}

/** The reason passed to [Vouchflow.requestFallback]. */
enum class FallbackReason(internal val apiValue: String) {
    ATTESTATION_UNAVAILABLE("attestation_unavailable"),
    ATTESTATION_FAILED("attestation_failed"),
    ATTESTATION_TIMEOUT("attestation_timeout"),
    BIOMETRIC_UNAVAILABLE("biometric_unavailable"),
    BIOMETRIC_FAILED("biometric_failed"),
    BIOMETRIC_CANCELLED("biometric_cancelled"),
    KEY_INVALIDATED("key_invalidated"),
    SDK_ERROR("sdk_error"),
    MINIMUM_CONFIDENCE_UNMET("minimum_confidence_unmet"),
    DEVELOPER_INITIATED("developer_initiated"),
    ENROLLMENT_FAILED("enrollment_failed");
}

// ── Signals ───────────────────────────────────────────────────────────────────

/** Device signals included in a completed verification. */
data class VouchflowSignals(
    /** The device token survived app deletion and reinstall (AccountManager persistence confirmed). */
    val persistentToken: Boolean,
    /** Biometric authentication was used for this verification. */
    val biometricUsed: Boolean,
    /** This device has verified across more than one Vouchflow-integrated app. */
    val crossAppHistory: Boolean,
    /** Anomaly flags raised against this device in the network graph. Empty for clean devices. */
    val anomalyFlags: List<String>,
    /** Play Integrity was verified at enrollment time for this device. */
    val attestationVerified: Boolean
)

// ── Results ───────────────────────────────────────────────────────────────────

/** The result of a successful primary verification. */
data class VouchflowResult(
    /** Whether the verification was successful. */
    val verified: Boolean,
    /** Confidence level of this verification. */
    val confidence: Confidence,
    /**
     * The device token for this device. Use this for server-side reputation API calls
     * (`GET /v1/device/{device_token}/reputation`). Never log or store it unnecessarily.
     */
    val deviceToken: String,
    /** Number of days since this device token was first enrolled. */
    val deviceAgeDays: Int,
    /** Total verifications for this device in the Vouchflow network. */
    val networkVerifications: Int,
    /** When this device was first seen by Vouchflow. */
    val firstSeen: Instant?,
    /** Device signals for this verification. */
    val signals: VouchflowSignals,
    /** Whether email fallback was used (always `false` for primary results). */
    val fallbackUsed: Boolean,
    /** The context passed to [Vouchflow.verify]. */
    val context: VerificationContext
)

/** The result of a successful fallback (email OTP) verification. */
data class FallbackVerificationResult(
    /** Whether the OTP verification was successful. */
    val verified: Boolean,
    /** Always [Confidence.LOW] — email OTP proves inbox access, not device presence. */
    val confidence: Confidence,
    /** Session state at completion. */
    val sessionState: String,
    /** Signals available from fallback (no device cryptography involved). */
    val fallbackSignals: FallbackSignals
)

/** Signals returned when a fallback (email OTP) verification completes. */
data class FallbackSignals(
    /** Whether the OTP submission came from the same IP that initiated the session. */
    val ipConsistent: Boolean,
    /** Whether the email domain is a known disposable provider. */
    val disposableEmailDomain: Boolean,
    /** Whether this device has prior successful verifications. */
    val deviceHasPriorVerifications: Boolean,
    /** Age of the email domain in days. `null` if undetermined. */
    val emailDomainAgeDays: Int?,
    /** Number of OTP attempts made. */
    val otpAttempts: Int,
    /** Seconds from fallback initiation to OTP submission. */
    val timeToCompleteSeconds: Int
)

/**
 * Returned by [Vouchflow.requestFallback].
 * Pass [fallbackSessionId] to [Vouchflow.submitFallbackOtp].
 */
data class FallbackResult(
    /** Identifier for this fallback session. Pass to [Vouchflow.submitFallbackOtp]. */
    val fallbackSessionId: String,
    /** When the OTP expires. 5-minute window from initiation. */
    val expiresAt: Instant
)
