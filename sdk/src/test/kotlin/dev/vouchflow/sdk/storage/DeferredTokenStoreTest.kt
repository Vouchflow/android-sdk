package dev.vouchflow.sdk.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies [DeferredTokenStore] defers provider invocation off the `configure()` path and
 * faithfully delegates the [TokenStore] contract to the resolved delegate.
 */
class DeferredTokenStoreTest {

    /** Minimal in-memory [TokenStore] for exercising delegation. */
    private class FakeTokenStore : TokenStore {
        private val map = HashMap<String, String>()
        override fun readDeviceToken(): String? = map[KEY_DVT]
        override fun writeDeviceToken(token: String) { map[KEY_DVT] = token }
        override fun deleteDeviceToken() { map.remove(KEY_DVT) }
        override fun deviceTokenExists(): Boolean = map.containsKey(KEY_DVT)
        override fun readPendingToken(): String? = map[KEY_PENDING]
        override fun writePendingToken(token: String) { map[KEY_PENDING] = token }
        override fun deletePendingToken() { map.remove(KEY_PENDING) }
        override fun pendingTokenExists(): Boolean = map.containsKey(KEY_PENDING)

        private companion object {
            const val KEY_DVT = "dvt"
            const val KEY_PENDING = "pending"
        }
    }

    // ── Laziness ──────────────────────────────────────────────────────────────

    @Test
    fun `provider is not invoked at construction`() {
        val calls = AtomicInteger(0)
        DeferredTokenStore { calls.incrementAndGet(); FakeTokenStore() }
        assertEquals("Provider must not run until first store access", 0, calls.get())
    }

    @Test
    fun `provider runs on first access`() {
        val calls = AtomicInteger(0)
        val store = DeferredTokenStore { calls.incrementAndGet(); FakeTokenStore() }
        store.readDeviceToken()
        assertEquals(1, calls.get())
    }

    @Test
    fun `provider runs exactly once across many accesses`() {
        val calls = AtomicInteger(0)
        val store = DeferredTokenStore { calls.incrementAndGet(); FakeTokenStore() }
        store.writeDeviceToken("a")
        store.readDeviceToken()
        store.deviceTokenExists()
        store.writePendingToken("b")
        store.deletePendingToken()
        assertEquals("Delegate must be resolved once and reused", 1, calls.get())
    }

    // ── Delegation ────────────────────────────────────────────────────────────

    @Test
    fun `delegates device token reads and writes`() {
        val store = DeferredTokenStore { FakeTokenStore() }
        assertNull(store.readDeviceToken())
        assertFalse(store.deviceTokenExists())

        store.writeDeviceToken("dvt_123")
        assertEquals("dvt_123", store.readDeviceToken())
        assertTrue(store.deviceTokenExists())

        store.deleteDeviceToken()
        assertNull(store.readDeviceToken())
        assertFalse(store.deviceTokenExists())
    }

    @Test
    fun `delegates pending token reads and writes`() {
        val store = DeferredTokenStore { FakeTokenStore() }
        assertNull(store.readPendingToken())
        assertFalse(store.pendingTokenExists())

        store.writePendingToken("pending_456")
        assertEquals("pending_456", store.readPendingToken())
        assertTrue(store.pendingTokenExists())

        store.deletePendingToken()
        assertNull(store.readPendingToken())
        assertFalse(store.pendingTokenExists())
    }
}
