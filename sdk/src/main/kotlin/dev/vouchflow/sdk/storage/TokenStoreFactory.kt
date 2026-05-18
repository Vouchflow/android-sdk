package dev.vouchflow.sdk.storage

import android.content.Context
import dev.vouchflow.sdk.internal.VouchflowLogger

/**
 * Selects the [TokenStore] implementation for the current device and configuration.
 *
 * Preference order:
 *  1. [AccountManagerStore] — when [useAccountManager] is `true` and AccountManager is
 *     actually usable on this device/profile (cross-reinstall persistence).
 *  2. [EncryptedPrefsTokenStore] — when AccountManager is disabled by config, or
 *     unavailable (fresh-install race, work profile, restricted user).
 *
 * [create] never throws — it performs the AccountManager probe through
 * [AccountManagerStore.ensureAvailable], which is itself infallible. It is invoked lazily
 * by [DeferredTokenStore], off the synchronous `Vouchflow.configure()` path.
 */
internal object TokenStoreFactory {

    fun create(context: Context, useAccountManager: Boolean): TokenStore {
        if (!useAccountManager) {
            VouchflowLogger.debug(
                "[VouchflowSDK] Token storage: encrypted in-app preferences " +
                    "(AccountManager disabled via VouchflowConfig)."
            )
            return EncryptedPrefsTokenStore(context)
        }

        val accountManagerStore = AccountManagerStore(context)
        if (accountManagerStore.ensureAvailable()) {
            VouchflowLogger.debug(
                "[VouchflowSDK] Token storage: AccountManager (cross-reinstall persistence enabled)."
            )
            return accountManagerStore
        }

        // ensureAvailable() has already logged the specific reason.
        VouchflowLogger.debug(
            "[VouchflowSDK] Token storage: encrypted in-app preferences (AccountManager fallback)."
        )
        return EncryptedPrefsTokenStore(context)
    }
}
