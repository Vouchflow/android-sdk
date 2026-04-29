package dev.vouchflow.sdk.integration

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.vouchflow.sdk.Vouchflow
import dev.vouchflow.sdk.VouchflowConfig
import dev.vouchflow.sdk.VouchflowEnvironment

internal object StagingTestConfig {

    // Sandbox write key — safe to commit; sandbox verifications are isolated from production
    // billing and never enter the live network graph.
    const val SANDBOX_WRITE_KEY = "vsk_sandbox_20af25f2668a65ae268625ab2235e765153fe11b"

    // PIN used on emulators configured with device-credential lock screen.
    // Must match the PIN set during CI emulator setup:
    //   adb shell locksettings set-pin --old '' 1234
    const val EMULATOR_TEST_PIN = "1234"

    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Configures the SDK against the sandbox environment with cert pinning disabled.
     * Pinning is disabled by using TODO placeholder pins, which the SDK skips in debug builds.
     * Call this in every @Before.
     */
    fun configure() {
        // VouchflowInitProvider fires before tests and sets applicationContext automatically,
        // but we set it explicitly here as a safety net for unusual test runner configurations.
        Vouchflow.applicationContext = context.applicationContext
        Vouchflow.configure(
            VouchflowConfig(
                apiKey = SANDBOX_WRITE_KEY,
                environment = VouchflowEnvironment.SANDBOX,
                // TODO prefix disables pinning in debug builds — instrumented tests are always debug.
                leafCertificatePin = "TODO-sandbox-integration-test",
                intermediateCertificatePin = "TODO-sandbox-integration-test"
            )
        )
    }

    fun reset() {
        Vouchflow.reset()
    }
}
