package dev.vouchflow.sdk

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.vouchflow.sdk.network.models.APIErrorResponse
import dev.vouchflow.sdk.network.models.EnrollRequest
import dev.vouchflow.sdk.network.models.EnrollResponse
import dev.vouchflow.sdk.network.models.FallbackCompleteResponse
import dev.vouchflow.sdk.network.models.FallbackRequest
import dev.vouchflow.sdk.network.models.VerifyRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that every request and response model serialises/deserialises
 * with the exact JSON field names the server expects (snake_case keys,
 * null optional fields omitted rather than sent as JSON null).
 *
 * Uses Gson's own JsonObject instead of android.org.json to stay in pure JVM.
 */
class ModelSerializationTest {

    private val gson = GsonBuilder().create()

    private fun toJson(any: Any): JsonObject = gson.toJsonTree(any).asJsonObject

    @Test
    fun `EnrollRequest top-level fields serialise with correct snake_case keys`() {
        val request = EnrollRequest(
            idempotencyKey = "ik_abc123",
            platform = "android",
            reason = "fresh_enrollment",
            attestation = null,
            publicKey = "base64pubkey==",
            deviceToken = "dvt_existing",
            strongboxBacked = true
        )
        val json = toJson(request)
        assertEquals("ik_abc123", json.get("idempotency_key").asString)
        assertEquals("android", json.get("platform").asString)
        assertEquals("fresh_enrollment", json.get("reason").asString)
        assertEquals("base64pubkey==", json.get("public_key").asString)
        assertEquals("dvt_existing", json.get("device_token").asString)
        assertTrue(json.get("strongbox_backed").asBoolean)
    }

    @Test
    fun `EnrollRequest null deviceToken is absent from serialised JSON`() {
        val request = EnrollRequest(
            idempotencyKey = "ik_abc",
            platform = "android",
            reason = "fresh_enrollment",
            attestation = null,
            publicKey = "base64pubkey==",
            deviceToken = null,
            strongboxBacked = null
        )
        val json = toJson(request)
        assertFalse("device_token must be absent when null", json.has("device_token"))
        assertFalse("strongbox_backed must be absent when null", json.has("strongbox_backed"))
    }

    @Test
    fun `EnrollRequest AttestationPayload serialises token and key_id`() {
        val attestation = EnrollRequest.AttestationPayload(
            token = "integrity-token-abc",
            keyId = null
        )
        val request = EnrollRequest(
            idempotencyKey = "ik_abc",
            platform = "android",
            reason = "fresh_enrollment",
            attestation = attestation,
            publicKey = "base64pubkey==",
            deviceToken = null,
            strongboxBacked = false
        )
        val att = toJson(request).get("attestation").asJsonObject
        assertEquals("integrity-token-abc", att.get("token").asString)
        assertFalse("key_id must be absent when null", att.has("key_id"))
        assertEquals("attestation object must have exactly 1 key", 1, att.keySet().size)
    }

    @Test
    fun `EnrollRequest attestation with non-null keyId serialises key_id`() {
        val attestation = EnrollRequest.AttestationPayload(token = "tok", keyId = "kid_abc")
        val request = EnrollRequest(
            idempotencyKey = "ik_abc",
            platform = "android",
            reason = "fresh_enrollment",
            attestation = attestation,
            publicKey = "k==",
            deviceToken = null,
            strongboxBacked = null
        )
        assertEquals("kid_abc", toJson(request).get("attestation").asJsonObject.get("key_id").asString)
    }

    @Test
    fun `VerifyRequest null minimumConfidence is absent from serialised JSON`() {
        val request = VerifyRequest(deviceToken = "dvt_abc", context = "login", minimumConfidence = null)
        val json = toJson(request)
        assertFalse("minimum_confidence must be absent when null", json.has("minimum_confidence"))
        assertEquals("dvt_abc", json.get("device_token").asString)
        assertEquals("login", json.get("context").asString)
    }

    @Test
    fun `VerifyRequest with minimumConfidence serialises correctly`() {
        val request = VerifyRequest(deviceToken = "dvt_abc", context = "signup", minimumConfidence = "high")
        assertEquals("high", toJson(request).get("minimum_confidence").asString)
    }

    @Test
    fun `FallbackRequest includes both email and email_hash`() {
        val request = FallbackRequest(
            deviceToken = "dvt_abc",
            email = "user@example.com",
            emailHash = "b4c9a289323b21a01c3e940f150eb9b8c542587f1abfd8f0e1cc1ffc5e475514",
            reason = "biometric_failed"
        )
        val json = toJson(request)
        assertEquals("user@example.com", json.get("email").asString)
        assertEquals(
            "b4c9a289323b21a01c3e940f150eb9b8c542587f1abfd8f0e1cc1ffc5e475514",
            json.get("email_hash").asString
        )
    }

    @Test
    fun `FallbackRequest null deviceToken is absent from serialised JSON`() {
        val request = FallbackRequest(
            deviceToken = null,
            email = "user@example.com",
            emailHash = "abc123",
            reason = "biometric_cancelled"
        )
        assertFalse("device_token must be absent when null", toJson(request).has("device_token"))
    }

    @Test
    fun `EnrollResponse deserialises from server JSON correctly`() {
        val serverJson = """{"device_token":"dvt_abc123","enrolled_at":"2026-04-11T12:00:00Z","status":"active","attestation_verified":true,"confidence_ceiling":"high","idempotency_key":"ik_abc"}"""
        val response = gson.fromJson(serverJson, EnrollResponse::class.java)
        assertEquals("dvt_abc123", response.deviceToken)
        assertEquals("active", response.status)
        assertTrue(response.attestationVerified)
        assertEquals("high", response.confidenceCeiling)
        assertEquals("ik_abc", response.idempotencyKey)
    }

    @Test
    fun `APIErrorResponse deserialises error code message and retry fields`() {
        val serverJson = """{"error":{"code":"session_expired","message":"The session has expired.","retry_session_id":"sess_retry_xyz","retry_challenge":"challenge_abc=="}}"""
        val response = gson.fromJson(serverJson, APIErrorResponse::class.java)
        assertNotNull(response.error)
        assertEquals("session_expired", response.error.code)
        assertEquals("The session has expired.", response.error.message)
        assertEquals("sess_retry_xyz", response.error.retrySessionId)
        assertEquals("challenge_abc==", response.error.retryChallenge)
    }

    @Test
    fun `APIErrorResponse deserialises with null optional retry fields`() {
        val serverJson = """{"error":{"code":"verification_impossible","message":"Cannot meet minimum confidence threshold."}}"""
        val response = gson.fromJson(serverJson, APIErrorResponse::class.java)
        assertEquals("verification_impossible", response.error.code)
        assertNull(response.error.retrySessionId)
        assertNull(response.error.retryChallenge)
    }

    @Test
    fun `FallbackCompleteResponse null emailDomainAgeDays deserialises as null`() {
        val serverJson = """{"verified":true,"confidence":"low","session_state":"FALLBACK_COMPLETE","fallback_signals":{"ip_consistent":true,"disposable_email_domain":false,"device_has_prior_verifications":false,"email_domain_age_days":null,"otp_attempts":1,"time_to_complete_seconds":42}}"""
        val response = gson.fromJson(serverJson, FallbackCompleteResponse::class.java)
        assertTrue(response.verified)
        assertNull(response.fallbackSignals.emailDomainAgeDays)
        assertEquals(1, response.fallbackSignals.otpAttempts)
        assertEquals(42, response.fallbackSignals.timeToCompleteSeconds)
    }

    @Test
    fun `FallbackCompleteResponse integer emailDomainAgeDays deserialises correctly`() {
        val serverJson = """{"verified":false,"confidence":"low","session_state":"FALLBACK_COMPLETE","fallback_signals":{"ip_consistent":false,"disposable_email_domain":true,"device_has_prior_verifications":false,"email_domain_age_days":3650,"otp_attempts":3,"time_to_complete_seconds":120}}"""
        val response = gson.fromJson(serverJson, FallbackCompleteResponse::class.java)
        assertEquals(3650, response.fallbackSignals.emailDomainAgeDays)
    }
}
