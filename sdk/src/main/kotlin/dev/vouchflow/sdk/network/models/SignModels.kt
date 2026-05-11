package dev.vouchflow.sdk.network.models

import com.google.gson.annotations.SerializedName

/** POST /v1/sign request body (initiate). */
data class SignInitiateRequest(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("context") val context: String,
    @SerializedName("canonicalized_payload") val canonicalizedPayload: String,
    @SerializedName("minimum_confidence") val minimumConfidence: String?,
    @SerializedName("idempotency_key") val idempotencyKey: String,
)

/** POST /v1/sign response (initiate). */
data class SignInitiateResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("challenge") val challenge: String,           // base64
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("payload_sha256") val payloadSha256: String,
)

/** POST /v1/sign/:session_id/complete request body for mobile platforms. */
data class SignCompleteRequest(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("signed_challenge") val signedChallenge: String,      // base64 DER
    @SerializedName("app_attest_assertion") val appAttestAssertion: String? = null,
)

/** POST /v1/sign/:session_id/complete response. */
data class SignCompleteResponse(
    @SerializedName("verified") val verified: Boolean,
    @SerializedName("confidence") val confidence: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("signing_device_id") val signingDeviceId: String,
    @SerializedName("signed_at") val signedAt: String,
    @SerializedName("assertion") val assertion: String,
    @SerializedName("platform") val platform: String,
    @SerializedName("session_id") val sessionId: String,
)
