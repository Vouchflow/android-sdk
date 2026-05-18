package dev.vouchflow.sdk.storage

/**
 * A [TokenStore] that defers construction of its delegate until first use.
 *
 * `Vouchflow.configure()` runs on the host app's `Application.onCreate()` thread. Selecting
 * a concrete store ([TokenStoreFactory]) probes [android.accounts.AccountManager] via
 * synchronous Binder IPC — work that must not run on that path, both because it can throw
 * (see [AccountManagerStore]) and because synchronous IPC in `onCreate()` is an ANR risk.
 *
 * This wrapper lets `configure()` return instantly. The [provider] runs on the first store
 * access — which the SDK's managers always perform from a background dispatcher — so the
 * Binder I/O lands off the main thread. The [lazy] delegate is thread-safe (SYNCHRONIZED);
 * the provider runs exactly once.
 */
internal class DeferredTokenStore(provider: () -> TokenStore) : TokenStore {

    private val delegate: TokenStore by lazy(provider)

    override fun readDeviceToken(): String? = delegate.readDeviceToken()
    override fun writeDeviceToken(token: String) = delegate.writeDeviceToken(token)
    override fun deleteDeviceToken() = delegate.deleteDeviceToken()
    override fun deviceTokenExists(): Boolean = delegate.deviceTokenExists()
    override fun readPendingToken(): String? = delegate.readPendingToken()
    override fun writePendingToken(token: String) = delegate.writePendingToken(token)
    override fun deletePendingToken() = delegate.deletePendingToken()
    override fun pendingTokenExists(): Boolean = delegate.pendingTokenExists()
}
