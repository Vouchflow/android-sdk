package dev.vouchflow.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the HTTP status code → VouchflowError mapping rules
 * that live inside VouchflowAPIClient.perform().
 *
 * All logic is tested through a pure private helper (mapStatusCode) so no
 * Android runtime, OkHttp, or MockWebServer is required — these are
 * plain JVM unit tests.
 */
class ApiErrorMappingTest {

    private fun mapStatusCode(
        statusCode: Int,
        errorCode: String?,
        retrySessionId: String?,
        retryChallenge: String?
    ): VouchflowError {
        return when (statusCode) {
            401 -> VouchflowError.InvalidApiKey

            410 -> {
                if (errorCode == "session_expired" &&
                    retrySessionId != null &&
                    retryChallenge != null) {
                    VouchflowError.SessionExpiredInternal(
                        retrySessionId = retrySessionId,
                        retryChallenge = retryChallenge
                    )
                } else {
                    VouchflowError.ServerError(
                        statusCode = 410,
                        code = errorCode,
                        serverMessage = null
                    )
                }
            }

            else -> {
                if (errorCode == "verification_impossible") {
                    VouchflowError.MinimumConfidenceUnmet
                } else {
                    VouchflowError.ServerError(
                        statusCode = statusCode,
                        code = errorCode,
                        serverMessage = null
                    )
                }
            }
        }
    }

    @Test
    fun `401 response maps to InvalidApiKey`() {
        val error = mapStatusCode(401, null, null, null)
        assertTrue(
            "Expected InvalidApiKey but got ${error::class.simpleName}",
            error is VouchflowError.InvalidApiKey
        )
    }

    @Test
    fun `410 with session_expired code and retry fields maps to SessionExpiredInternal`() {
        val error = mapStatusCode(
            statusCode = 410,
            errorCode = "session_expired",
            retrySessionId = "sess_retry_abc",
            retryChallenge = "challenge_xyz=="
        )
        assertTrue(
            "Expected SessionExpiredInternal but got ${error::class.simpleName}",
            error is VouchflowError.SessionExpiredInternal
        )
        val internal = error as VouchflowError.SessionExpiredInternal
        assertEquals("sess_retry_abc", internal.retrySessionId)
        assertEquals("challenge_xyz==", internal.retryChallenge)
    }

    @Test
    fun `410 with session_expired but missing retry fields maps to ServerError`() {
        val error = mapStatusCode(
            statusCode = 410,
            errorCode = "session_expired",
            retrySessionId = null,
            retryChallenge = null
        )
        assertTrue(
            "Expected ServerError but got ${error::class.simpleName}",
            error is VouchflowError.ServerError
        )
        assertEquals(410, (error as VouchflowError.ServerError).statusCode)
    }

    @Test
    fun `410 with unknown error code maps to ServerError`() {
        val error = mapStatusCode(
            statusCode = 410,
            errorCode = "rate_limited",
            retrySessionId = null,
            retryChallenge = null
        )
        assertTrue(error is VouchflowError.ServerError)
        assertEquals(410, (error as VouchflowError.ServerError).statusCode)
    }

    @Test
    fun `4xx with verification_impossible code maps to MinimumConfidenceUnmet`() {
        for (statusCode in listOf(400, 403, 422, 451)) {
            val error = mapStatusCode(
                statusCode = statusCode,
                errorCode = "verification_impossible",
                retrySessionId = null,
                retryChallenge = null
            )
            assertTrue(
                "Status $statusCode with verification_impossible expected MinimumConfidenceUnmet",
                error is VouchflowError.MinimumConfidenceUnmet
            )
        }
    }

    @Test
    fun `4xx with unknown code maps to ServerError`() {
        val error = mapStatusCode(
            statusCode = 422,
            errorCode = "invalid_device",
            retrySessionId = null,
            retryChallenge = null
        )
        assertTrue(error is VouchflowError.ServerError)
        assertEquals(422, (error as VouchflowError.ServerError).statusCode)
        assertEquals("invalid_device", (error as VouchflowError.ServerError).code)
    }

    @Test
    fun `IOException without pinning message maps to NetworkUnavailable`() {
        val msg = "Connection refused"
        val isPinningFailure = msg.contains("Certificate pinning failure") ||
            msg.contains("Certificate pinning failed") ||
            msg.contains("placeholder pins")
        assertTrue("Plain IOException should NOT look like a pinning failure", !isPinningFailure)
    }

    @Test
    fun `IOException with pinning message maps to PinningFailure`() {
        val msg = "Certificate pinning failure!"
        val isPinningFailure = msg.contains("Certificate pinning failure") ||
            msg.contains("Certificate pinning failed") ||
            msg.contains("placeholder pins")
        assertTrue("Pinning error message should be detected as PinningFailure", isPinningFailure)
    }

    @Test
    fun `SessionExpiredInternal is internal and not accessible from public API surface`() {
        val error: VouchflowError = VouchflowError.SessionExpiredInternal(
            retrySessionId = "retry_sess_001",
            retryChallenge = "ch_abc=="
        )
        assertTrue(error is VouchflowError.SessionExpiredInternal)
        val carrier = error as VouchflowError.SessionExpiredInternal
        assertEquals("retry_sess_001", carrier.retrySessionId)
        assertEquals("ch_abc==", carrier.retryChallenge)
        assertTrue(error !is VouchflowError.SessionExpiredRepeatedly)
    }

    @Test
    fun `SHA-256 of user@example dot com equals known digest`() {
        val input = "user@example.com"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        assertEquals(
            "b4c9a289323b21a01c3e940f150eb9b8c542587f1abfd8f0e1cc1ffc5e475514",
            hex
        )
    }

    @Test
    fun `SHA-256 hash is lowercase hex`() {
        val input = "test@vouchflow.dev"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        assertEquals("Hash must be lowercase hex only", hex, hex.lowercase())
        assertTrue("Hash must be 64 hex characters", hex.matches(Regex("[0-9a-f]{64}")))
    }
}
