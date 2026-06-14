package dev.vouchflow.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the parser that pulls the *served* chain pins out of OkHttp's
 * `SSLPeerUnverifiedException` message. This is what turns the opaque
 * `PinningFailure` of older SDK versions into a self-diagnosing error: the
 * integrator immediately sees "my configured pin says X, server is presenting
 * Y" and knows whether they're stale or being MITM'd.
 *
 * If OkHttp ever changes its error-string format this test will fail loudly,
 * which is what we want — silent degradation back to empty payloads would
 * regress the diagnostic, not the security.
 */
class PinningFailurePayloadTest {

    @Test
    fun `extracts only the served chain, not the pinned set`() {
        // Verbatim shape of an OkHttp SSLPeerUnverifiedException message.
        val msg = """
            Certificate pinning failure!
              Peer certificate chain:
                sha256/NQ7reZqY0tQjef9LBQwbs0gHjrdrroWrd+scM74zQrU=: CN=api.vouchflow.dev
                sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=: CN=YE1, O=Let's Encrypt, C=US
              Pinned certificates for api.vouchflow.dev:
                sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=
                sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=
        """.trimIndent()

        val served = VouchflowAPIClient.extractServedPinsFromOkHttpMessage(msg)

        assertEquals(
            listOf(
                "NQ7reZqY0tQjef9LBQwbs0gHjrdrroWrd+scM74zQrU=",
                "brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4="
            ),
            served
        )
    }

    @Test
    fun `returns empty list for non-OkHttp messages (e g placeholder-pins path)`() {
        // Our PinningInterceptor's RejectAllInterceptor throws with our own copy,
        // which doesn't carry a chain section. We should degrade to an empty list
        // rather than throwing a parse error on top of the original failure.
        val ours = "[VouchflowSDK] Certificate pinning failed — placeholder pins in a release build. Set real pins in VouchflowConfig."
        assertEquals(emptyList<String>(), VouchflowAPIClient.extractServedPinsFromOkHttpMessage(ours))
    }

    @Test
    fun `strips sha256 prefix so callers compare against their raw configured values`() {
        // If we left the "sha256/" prefix in, the integrator would have to know to strip
        // it before comparing to the value they put in VouchflowConfig (which the SDK
        // explicitly forbids). Keep the API in one canonical form.
        val msg = "Peer certificate chain:\n    sha256/abc==: foo\nPinned certificates for x:"
        assertEquals(listOf("abc=="), VouchflowAPIClient.extractServedPinsFromOkHttpMessage(msg))
    }
}
