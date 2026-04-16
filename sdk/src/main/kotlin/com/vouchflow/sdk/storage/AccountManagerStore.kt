package com.vouchflow.sdk.storage

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.vouchflow.sdk.internal.VouchflowLogger

/**
 * Persists the device token and pending enrollment placeholder via [AccountManager].
 *
 * AccountManager stores data at the OS account level — outside the app data sandbox — which
 * means it survives app uninstalls on most devices. This is the Android equivalent of the iOS
 * Keychain `kSecAttrAccessibleAfterFirstUnlock` strategy.
 *
 * ## OEM variance
 * Xiaomi, OPPO, and Realme devices with aggressive battery management may purge AccountManager
 * data despite the account surviving uninstall. The SDK treats this as a REINSTALL state —
 * the server-side device_token and reputation history are preserved.
 *
 * ## Permissions
 * No extra permissions are required. The stub authenticator service declared in the SDK's
 * manifest registers the account type "com.vouchflow.sdk" under the host app's UID.
 * OS-level enforcement then grants the host app full access to its own account type.
 *
 * ## Thread safety
 * [AccountManager.getUserData] and [setUserData] are fast Binder IPC calls. They are safe to
 * call on the calling thread; callers in [com.vouchflow.sdk.core.EnrollmentManager] already
 * run on background dispatchers.
 */
internal class AccountManagerStore(context: Context) {

    private val accountManager: AccountManager = AccountManager.get(context.applicationContext)
    private val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)

    init {
        ensureAccountExists()
    }

    // ── Device token ──────────────────────────────────────────────────────────

    fun readDeviceToken(): String? = accountManager.getUserData(account, KEY_DEVICE_TOKEN)

    fun writeDeviceToken(token: String) {
        accountManager.setUserData(account, KEY_DEVICE_TOKEN, token)
    }

    fun deleteDeviceToken() {
        accountManager.setUserData(account, KEY_DEVICE_TOKEN, null)
    }

    fun deviceTokenExists(): Boolean = readDeviceToken() != null

    // ── Pending enrollment placeholder ────────────────────────────────────────

    fun readPendingToken(): String? = accountManager.getUserData(account, KEY_PENDING_TOKEN)

    fun writePendingToken(token: String) {
        accountManager.setUserData(account, KEY_PENDING_TOKEN, token)
    }

    fun deletePendingToken() {
        accountManager.setUserData(account, KEY_PENDING_TOKEN, null)
    }

    fun pendingTokenExists(): Boolean = readPendingToken() != null

    // ── Private ───────────────────────────────────────────────────────────────

    private fun ensureAccountExists() {
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            val added = accountManager.addAccountExplicitly(account, null, null)
            if (!added) {
                VouchflowLogger.warn("[VouchflowSDK] AccountManager.addAccountExplicitly failed. " +
                    "Token persistence may not survive app reinstall.")
            }
        }
    }

    companion object {
        private const val ACCOUNT_TYPE = "com.vouchflow.sdk"
        private const val ACCOUNT_NAME = "vouchflow_device"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PENDING_TOKEN = "pending_device_token"
    }
}
