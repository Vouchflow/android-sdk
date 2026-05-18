package dev.vouchflow.sdk.storage

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.RemoteException
import dev.vouchflow.sdk.internal.VouchflowLogger

/**
 * Persists the device token and pending enrollment placeholder via [AccountManager].
 *
 * AccountManager stores data at the OS account level — outside the app data sandbox — which
 * means it survives app uninstalls on most devices. This is the Android equivalent of the iOS
 * Keychain `kSecAttrAccessibleAfterFirstUnlock` strategy.
 *
 * ## Infallibility
 * Implements the [TokenStore] no-throw contract. Every AccountManager call is wrapped:
 * a `SecurityException` (the fresh-install authenticator-indexing race, or a
 * `DISALLOW_MODIFY_ACCOUNTS` device policy on work profiles / restricted users) or a
 * `RemoteException` is logged and swallowed. Token persistence is an optional enhancement —
 * its failure must never propagate into the host app's `Application.onCreate()`.
 *
 * ## No constructor I/O
 * The constructor performs no Binder IPC. Account creation is done by [ensureAvailable],
 * which [TokenStoreFactory] calls off the synchronous `configure()` path (see
 * [DeferredTokenStore]). This avoids both the crash and the ANR risk of doing AccountManager
 * I/O inside `Application.onCreate()`.
 *
 * ## OEM variance
 * Xiaomi, OPPO, and Realme devices with aggressive battery management may purge AccountManager
 * data despite the account surviving uninstall. The SDK treats this as a REINSTALL state —
 * the server-side device_token and reputation history are preserved.
 *
 * ## Permissions
 * No extra permissions are required. The stub authenticator service declared in the SDK's
 * manifest registers the account type "dev.vouchflow.sdk" under the host app's UID.
 * OS-level enforcement then grants the host app full access to its own account type.
 */
internal class AccountManagerStore(context: Context) : TokenStore {

    private val accountManager: AccountManager = AccountManager.get(context.applicationContext)
    private val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)

    /** Set once the SDK-owned account is confirmed to exist. Never reset to `false` — a */
    /** transient fresh-install race should be retried, not cached as permanent failure. */
    @Volatile private var accountReady = false
    private val lock = Any()

    /**
     * Ensures the SDK-owned account exists, creating it via the non-interactive
     * [AccountManager.addAccountExplicitly] if needed.
     *
     * @return `true` if AccountManager storage is usable; `false` if it is unavailable
     *   (fresh-install race, device policy denial, or `addAccountExplicitly` rejection).
     *   On `false` the caller should fall back to [EncryptedPrefsTokenStore]. Never throws.
     */
    fun ensureAvailable(): Boolean {
        if (accountReady) return true
        return synchronized(lock) {
            if (accountReady) return true
            val ok = try {
                val existing = accountManager.getAccountsByType(ACCOUNT_TYPE)
                if (existing.isNotEmpty()) {
                    true
                } else {
                    // addAccountExplicitly: no UI, synchronous, permitted for the package
                    // that owns the authenticator. Returns false if the account already
                    // exists or if the system has not yet indexed our authenticator service.
                    accountManager.addAccountExplicitly(account, null, null)
                }
            } catch (e: SecurityException) {
                VouchflowLogger.warn(
                    "[VouchflowSDK] AccountManager unavailable (SecurityException): ${e.message}. " +
                        "Falling back to encrypted in-app storage; token will not survive reinstall."
                )
                false
            } catch (e: RemoteException) {
                VouchflowLogger.warn(
                    "[VouchflowSDK] AccountManager unavailable (RemoteException): ${e.message}. " +
                        "Falling back to encrypted in-app storage."
                )
                false
            } catch (e: Exception) {
                VouchflowLogger.warn("[VouchflowSDK] AccountManager unavailable: ${e.message}.")
                false
            }
            if (ok) accountReady = true
            ok
        }
    }

    // ── Device token ──────────────────────────────────────────────────────────

    override fun readDeviceToken(): String? = read(KEY_DEVICE_TOKEN)

    override fun writeDeviceToken(token: String) = write(KEY_DEVICE_TOKEN, token)

    override fun deleteDeviceToken() = write(KEY_DEVICE_TOKEN, null)

    override fun deviceTokenExists(): Boolean = readDeviceToken() != null

    // ── Pending enrollment placeholder ────────────────────────────────────────

    override fun readPendingToken(): String? = read(KEY_PENDING_TOKEN)

    override fun writePendingToken(token: String) = write(KEY_PENDING_TOKEN, token)

    override fun deletePendingToken() = write(KEY_PENDING_TOKEN, null)

    override fun pendingTokenExists(): Boolean = readPendingToken() != null

    // ── Private ───────────────────────────────────────────────────────────────

    /** Reads user data. Returns `null` on a missing account or any AccountManager failure. */
    private fun read(key: String): String? = try {
        accountManager.getUserData(account, key)
    } catch (e: Exception) {
        VouchflowLogger.warn("[VouchflowSDK] AccountManager read failed for '$key': ${e.message}.")
        null
    }

    /** Writes (or clears, when [value] is `null`) user data. No-op on any failure. */
    private fun write(key: String, value: String?) {
        if (!ensureAvailable()) return
        try {
            accountManager.setUserData(account, key, value)
        } catch (e: Exception) {
            VouchflowLogger.warn("[VouchflowSDK] AccountManager write failed for '$key': ${e.message}.")
        }
    }

    companion object {
        private const val ACCOUNT_TYPE = "dev.vouchflow.sdk"
        private const val ACCOUNT_NAME = "vouchflow_device"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PENDING_TOKEN = "pending_device_token"
    }
}
