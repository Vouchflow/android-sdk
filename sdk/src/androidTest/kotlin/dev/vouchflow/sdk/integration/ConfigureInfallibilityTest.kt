package dev.vouchflow.sdk.integration

import android.accounts.AccountManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vouchflow.sdk.Vouchflow
import dev.vouchflow.sdk.VouchflowConfig
import dev.vouchflow.sdk.VouchflowEnvironment
import dev.vouchflow.sdk.storage.EncryptedPrefsTokenStore
import dev.vouchflow.sdk.storage.TokenStoreFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression test for android-sdk issue #1 — `Vouchflow.configure()` crashing
 * the host process with a `SecurityException` out of `AccountManager`.
 *
 * ## Acceptance criteria (issue #1)
 *  - `configure()` never throws from storage, on any launch.
 *  - Fresh-install cold launch in a loop (>=50x) shows zero crashes.
 *  - AccountManager unavailable -> SDK silently falls back to encrypted in-app storage.
 *
 * ## Fidelity note
 * A true cross-process reinstall (`adb install` / `pm clear`) cannot be reproduced from
 * inside the app's own process. This test approximates a fresh first launch each iteration
 * by removing the SDK-owned AccountManager account, which forces `addAccountExplicitly`
 * down the same code path that crashed the host during the real fresh-install indexing
 * race. A genuine cross-process reinstall loop belongs in the `android.yml` CI script as
 * an adb-driven step.
 *
 * Requires no device credential — runs in the no-auth CI pass.
 */
@RunWith(AndroidJUnit4::class)
class ConfigureInfallibilityTest {

    private val context get() = StagingTestConfig.context

    @Before
    fun setUp() {
        Vouchflow.applicationContext = context.applicationContext
        Vouchflow.reset()
        removeSdkAccount()
    }

    @After
    fun tearDown() {
        Vouchflow.reset()
    }

    /**
     * Issue #1 core criterion: >=50 simulated fresh launches — `configure()` plus the first
     * store access must never throw. The SDK-owned account is wiped before each iteration so
     * every pass exercises account (re)creation, the path that crashed the host in the report.
     */
    @Test
    fun configure_repeatedFreshLaunch_neverThrows() {
        repeat(ITERATIONS) { i ->
            removeSdkAccount()
            try {
                Vouchflow.configure(sandboxConfig(accountManagerStorage = true))
                // Force DeferredTokenStore resolution: this is where the AccountManager
                // Binder I/O (getAccountsByType / addAccountExplicitly) actually runs.
                Vouchflow.shared.cachedDeviceToken
            } catch (t: Throwable) {
                fail(
                    "Iteration $i: configure()/first store access threw " +
                        "${t.javaClass.name}: ${t.message}"
                )
            }
            Vouchflow.reset()
        }
    }

    /**
     * With `accountManagerStorage = false` the SDK must skip AccountManager entirely and use
     * encrypted in-app storage — and still never throw from `configure()`, across >=50 launches.
     */
    @Test
    fun configure_withAccountManagerDisabled_neverThrows() {
        repeat(ITERATIONS) { i ->
            try {
                Vouchflow.configure(sandboxConfig(accountManagerStorage = false))
                Vouchflow.shared.cachedDeviceToken
            } catch (t: Throwable) {
                fail(
                    "Iteration $i (AccountManager disabled) threw " +
                        "${t.javaClass.name}: ${t.message}"
                )
            }
            Vouchflow.reset()
        }
    }

    /**
     * Criterion 3: the encrypted in-app fallback store is fully functional — the store the
     * SDK lands on when AccountManager is unavailable (work profile / restricted user) or
     * disabled by config.
     */
    @Test
    fun tokenStoreFactory_withAccountManagerDisabled_returnsWorkingEncryptedStore() {
        val store = TokenStoreFactory.create(context, useAccountManager = false)
        assertTrue(
            "Disabling AccountManager must yield the encrypted in-app store",
            store is EncryptedPrefsTokenStore
        )

        store.deleteDeviceToken()
        assertNull("Pre-condition: no token", store.readDeviceToken())
        assertFalse(store.deviceTokenExists())

        store.writeDeviceToken("dvt_fallback_roundtrip")
        assertEquals("dvt_fallback_roundtrip", store.readDeviceToken())
        assertTrue(store.deviceTokenExists())

        store.deleteDeviceToken()
        assertNull("Token cleared after delete", store.readDeviceToken())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sandboxConfig(accountManagerStorage: Boolean) = VouchflowConfig(
        apiKey = StagingTestConfig.SANDBOX_WRITE_KEY,
        environment = VouchflowEnvironment.SANDBOX,
        // TODO prefix disables cert pinning in debug builds.
        leafCertificatePin = "TODO-configure-infallibility-test",
        intermediateCertificatePin = "TODO-configure-infallibility-test",
        accountManagerStorage = accountManagerStorage,
    )

    /** Removes the SDK-owned account so the next configure() re-runs account creation. */
    private fun removeSdkAccount() {
        try {
            val am = AccountManager.get(context)
            am.getAccountsByType(ACCOUNT_TYPE).forEach { am.removeAccountExplicitly(it) }
        } catch (_: Exception) {
            // Best effort — if removal is not permitted the test still exercises configure().
        }
    }

    companion object {
        private const val ITERATIONS = 50
        private const val ACCOUNT_TYPE = "dev.vouchflow.sdk"
    }
}
