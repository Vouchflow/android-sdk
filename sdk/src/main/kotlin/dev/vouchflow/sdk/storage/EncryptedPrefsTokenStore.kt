package dev.vouchflow.sdk.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.vouchflow.sdk.internal.VouchflowLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * In-sandbox [TokenStore] backed by Jetpack Security [EncryptedSharedPreferences].
 *
 * Used as the fallback when [AccountManagerStore] is unavailable — the fresh-install
 * authenticator-indexing race, work profiles / restricted users with
 * `DISALLOW_MODIFY_ACCOUNTS`, or when an integrator opts out via
 * [dev.vouchflow.sdk.VouchflowConfig.accountManagerStorage].
 *
 * ## Persistence guarantee
 * Tokens are encrypted at rest with an AES-256 key held in the Android Keystore. Unlike
 * [AccountManagerStore], data lives in the app data sandbox: it survives process death and
 * app updates, but **not app uninstall**. A reinstall after using this store presents to
 * the server as a fresh enrollment — reputation history is still preserved server-side.
 *
 * ## Infallibility
 * Honors the [TokenStore] no-throw contract. If [EncryptedSharedPreferences] itself cannot
 * be constructed (rare Keystore corruption), this store degrades to a process-lifetime
 * in-memory map so callers still never see an exception.
 */
internal class EncryptedPrefsTokenStore(context: Context) : TokenStore {

    private val appContext: Context = context.applicationContext

    /** Process-lifetime fallback used only if [EncryptedSharedPreferences] cannot be built. */
    private val memoryFallback = ConcurrentHashMap<String, String>()

    /** Built lazily so construction does no I/O on the synchronous `configure()` path. */
    private val prefs: SharedPreferences? by lazy { createEncryptedPrefs() }

    private fun createEncryptedPrefs(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        VouchflowLogger.warn(
            "[VouchflowSDK] EncryptedSharedPreferences unavailable: ${e.message}. " +
                "Token persistence is limited to the current process."
        )
        null
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

    private fun read(key: String): String? = try {
        prefs?.getString(key, null) ?: memoryFallback[key]
    } catch (e: Exception) {
        VouchflowLogger.warn("[VouchflowSDK] Encrypted read failed for '$key': ${e.message}.")
        memoryFallback[key]
    }

    private fun write(key: String, value: String?) {
        try {
            val store = prefs
            if (store != null) {
                store.edit().apply {
                    if (value == null) remove(key) else putString(key, value)
                }.apply()
            } else {
                if (value == null) memoryFallback.remove(key) else memoryFallback[key] = value
            }
        } catch (e: Exception) {
            VouchflowLogger.warn("[VouchflowSDK] Encrypted write failed for '$key': ${e.message}.")
        }
    }

    companion object {
        private const val PREFS_FILE = "vouchflow_secure_tokens"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PENDING_TOKEN = "pending_device_token"
    }
}
