package dev.vouchflow.sdk.network.models

import com.google.gson.annotations.SerializedName

// ── Request ───────────────────────────────────────────────────────────────────

internal data class EnrollRequest(
    @SerializedName("idempotency_key") val idempotencyKey: String,
    @SerializedName("platform") val platform: String,
    /** fresh_enrollment | reinstall | key_invalidated | corrupted */
    @SerializedName("reason") val reason: String,
    @SerializedName("attestation") val attestation: AttestationPayload?,
    @SerializedName("public_key") val publicKey: String,
    /** Null on fresh enrollment; existing token on reinstall / key_invalidated. */
    @SerializedName("device_token") val deviceToken: String?,
    /** Whether the Keystore key is StrongBox-backed. Feeds confidence scoring. */
    @SerializedName("strongbox_backed") val strongboxBacked: Boolean?
) {
    internal data class AttestationPayload(
        /**
         * Apple App Attest base64 attestation object. Always null on Android.
         */
        @SerializedName("token") val token: String? = null,
        /**
         * Apple App Attest credential ID. Always null on Android.
         */
        @SerializedName("key_id") val keyId: String? = null,
        /**
         * Android Keystore Attestation certificate chain, leaf-first, each cert
         * base64-encoded DER. Always null on iOS. The server walks the chain to
         * the Google Hardware Attestation Root CA and parses the leaf's
         * attestation extension to verify the challenge nonce, security level,
         * and the calling app's package name + signing-key digest.
         */
        @SerializedName("cert_chain") val certChain: List<String>? = null,
    )
}

// ── Response ──────────────────────────────────────────────────────────────────

internal data class EnrollResponse(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("enrolled_at") val enrolledAt: String,
    @SerializedName("status") val status: String,
    @SerializedName("attestation_verified") val attestationVerified: Boolean,
    @SerializedName("confidence_ceiling") val confidenceCeiling: String,
    @SerializedName("idempotency_key") val idempotencyKey: String
)
