package dev.vouchflow.sdk.storage

/**
 * Persistence abstraction for the device token and the pending-enrollment placeholder.
 *
 * ## Infallibility contract
 * Every method on a [TokenStore] **must not throw**. Token persistence is an optional
 * enhancement — a storage failure (a `SecurityException` from [AccountManager], a
 * device-policy denial, a Keystore error) must degrade gracefully, never crash the host
 * app. Implementations swallow and log their own failures:
 *  - read methods return `null` on failure,
 *  - write/delete methods become no-ops on failure.
 *
 * Two implementations exist:
 *  - [AccountManagerStore] — OS account-level storage; survives app reinstall.
 *  - [EncryptedPrefsTokenStore] — in-sandbox encrypted storage; the fallback when
 *    AccountManager is unavailable (fresh-install race, work profiles, restricted users)
 *    or disabled via [dev.vouchflow.sdk.VouchflowConfig.accountManagerStorage].
 *
 * [TokenStoreFactory] selects the implementation; [DeferredTokenStore] keeps that
 * selection (and its Binder I/O) off the synchronous `Vouchflow.configure()` path.
 */
internal interface TokenStore {

    // ── Device token ──────────────────────────────────────────────────────────

    fun readDeviceToken(): String?
    fun writeDeviceToken(token: String)
    fun deleteDeviceToken()
    fun deviceTokenExists(): Boolean

    // ── Pending enrollment placeholder ────────────────────────────────────────

    fun readPendingToken(): String?
    fun writePendingToken(token: String)
    fun deletePendingToken()
    fun pendingTokenExists(): Boolean
}
