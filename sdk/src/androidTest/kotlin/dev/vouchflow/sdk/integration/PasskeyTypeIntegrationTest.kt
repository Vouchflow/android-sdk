package dev.vouchflow.sdk.integration

import android.os.Build
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.vouchflow.sdk.Vouchflow
import dev.vouchflow.sdk.VouchflowError
import dev.vouchflow.sdk.VouchflowResult
import dev.vouchflow.sdk.VerificationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented integration tests verifying the full enroll + verify flow for each device
 * passkey configuration against the sandbox environment.
 *
 * ## Running each test class
 *
 * Biometric (fingerprint/face enrolled):
 *   1. Create AVD with virtual fingerprint sensor (`-feature Fingerprint`).
 *   2. Boot emulator and enroll fingerprint via Settings > Security > Fingerprint.
 *   3. Run [biometricEnrolled_enrollAndVerifySucceeds].
 *   4. While the test is waiting (after BiometricPrompt appears), send from the host:
 *        adb emu finger touch 1
 *
 * Passcode only (PIN, no biometric):
 *   1. Clean emulator (no fingerprint sensor or biometric un-enrolled).
 *   2. Set PIN:  adb shell locksettings set-pin --old '' 1234
 *   3. Run [passcodeEnrolled_enrollAndVerifySucceeds].
 *      UiAutomator types the PIN automatically.
 *
 * Pattern only:
 *   Run [passcodeEnrolled_enrollAndVerifySucceeds] on an emulator with a pattern lock.
 *   The test skips automatically if a pattern grid is detected (pattern drawing is not
 *   supported in automated tests — use a PIN-locked emulator instead).
 *
 * No auth:
 *   Fresh emulator with no lock screen configured. Run [noAuth_verifyThrowsError].
 */
@RunWith(AndroidJUnit4::class)
class PasskeyTypeIntegrationTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(TestActivity::class.java)

    private lateinit var device: UiDevice
    private val context get() = StagingTestConfig.context

    @Before
    fun setUp() {
        StagingTestConfig.reset()
        StagingTestConfig.configure()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @After
    fun tearDown() {
        StagingTestConfig.reset()
    }

    // ── Biometric enrolled ────────────────────────────────────────────────────

    /**
     * Device has a strong biometric (fingerprint or face) enrolled.
     *
     * The test launches verify(), waits for the BiometricPrompt to appear, then
     * waits up to 30 seconds for a result. The CI runner must send `adb emu finger touch 1`
     * from the host machine while this test is in the waiting phase.
     */
    @Test
    fun biometricEnrolled_enrollAndVerifySucceeds() {
        assumeTrue(
            "No strong biometric enrolled — run this test on a biometric-enrolled emulator",
            BiometricSimulator.isBiometricStrongAvailable(context)
        )

        val result = launchVerifyAndWaitForResult(timeoutSeconds = 30)

        assertNotNull("verify() must complete successfully with biometric enrolled", result)
        assertTrue("verified must be true", result!!.verified)
        assertTrue("biometricUsed must be true for biometric authentication", result.signals.biometricUsed)
        assertNotNull("device token must be set after enrollment + verify", Vouchflow.shared.cachedDeviceToken)
    }

    // ── Passcode / PIN enrolled (no biometric) ────────────────────────────────

    /**
     * Device has a PIN (or password) configured as the only authenticator — no biometric enrolled.
     * On API 30+, BiometricPrompt presents the device credential UI directly.
     *
     * On API < 30, the SDK only allows BIOMETRIC_STRONG (device credential is not supported
     * for per-use Keystore keys on older APIs). If no biometric is available, the Keystore key
     * cannot be generated and enrollment fails — this test is skipped on API < 30.
     *
     * Pattern lock: if a pattern grid is detected instead of a PIN field, the test skips.
     * Re-run on a PIN-locked emulator.
     */
    @Test
    fun passcodeEnrolled_enrollAndVerifySucceeds() {
        assumeTrue(
            "API 30+ required for DEVICE_CREDENTIAL in per-use Keystore keys",
            BiometricSimulator.isApiAtLeast30()
        )
        assumeTrue(
            "No device credential enrolled — set a PIN with: adb shell locksettings set-pin --old '' 1234",
            BiometricSimulator.isDeviceCredentialAvailable(context)
        )
        assumeFalse(
            "Biometric is also enrolled — this test is for passcode-only devices. " +
            "Run biometricEnrolled_enrollAndVerifySucceeds instead.",
            BiometricSimulator.isBiometricStrongAvailable(context)
        )

        var result: VouchflowResult? = null
        var thrownError: Exception? = null
        val latch = CountDownLatch(1)

        var capturedActivity: TestActivity? = null
        activityRule.scenario.onActivity { capturedActivity = it }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                result = Vouchflow.shared.verify(
                    activity = capturedActivity!!,
                    context = VerificationContext.LOGIN
                )
            } catch (e: Exception) {
                thrownError = e
            } finally {
                latch.countDown()
            }
        }

        // Interact with the device credential prompt via UiAutomator
        try {
            BiometricSimulator.acceptWithPin(device, StagingTestConfig.EMULATOR_TEST_PIN)
        } catch (e: BiometricSimulator.PatternLockDetectedException) {
            assumeTrue("Pattern lock — automated pattern drawing not supported. Use a PIN-locked emulator.", false)
        }

        assertTrue("verify() did not complete within 30s after PIN entry", latch.await(30, TimeUnit.SECONDS))
        assertNull("Expected no error after PIN authentication: $thrownError", thrownError)
        assertNotNull("verify() must return a result", result)
        assertTrue("verified must be true", result!!.verified)
        assertNotNull("device token must be set", Vouchflow.shared.cachedDeviceToken)
    }

    // ── No auth enrolled ──────────────────────────────────────────────────────

    /**
     * Device has no lock screen configured (no biometric, no PIN, no pattern, no password).
     *
     * Expected behavior:
     * - On most API levels: Keystore key generation fails because setUserAuthenticationRequired(true)
     *   cannot be satisfied without any credential enrolled → VouchflowError.EnrollmentFailed.
     * - On some emulator configurations: key generation succeeds but BiometricPrompt immediately
     *   returns ERROR_NO_BIOMETRICS → VouchflowError.BiometricUnavailable.
     *
     * Either error is acceptable — the important assertion is that verify() does not return a
     * successful VouchflowResult on a device with no authentication configured.
     */
    @Test
    fun noAuth_verifyThrowsError() {
        assumeFalse(
            "An authentication method is enrolled — run this test on a clean emulator with no lock screen",
            BiometricSimulator.isAnyAuthAvailable(context)
        )

        var result: VouchflowResult? = null
        var thrownError: VouchflowError? = null

        try {
            var capturedActivity: TestActivity? = null
            activityRule.scenario.onActivity { capturedActivity = it }
            val latch = CountDownLatch(1)

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    result = Vouchflow.shared.verify(
                        activity = capturedActivity!!,
                        context = VerificationContext.LOGIN
                    )
                } catch (e: VouchflowError.EnrollmentFailed) {
                    thrownError = e
                } catch (e: VouchflowError.BiometricUnavailable) {
                    thrownError = VouchflowError.BiometricUnavailable
                } finally {
                    latch.countDown()
                }
            }

            latch.await(15, TimeUnit.SECONDS)
        } catch (_: Exception) { }

        assertNull(
            "verify() must not succeed on a device with no authentication configured",
            result
        )
        assertNotNull(
            "verify() must throw EnrollmentFailed or BiometricUnavailable on a no-auth device",
            thrownError
        )
        assertTrue(
            "Expected EnrollmentFailed or BiometricUnavailable, got: ${thrownError!!::class.simpleName}",
            thrownError is VouchflowError.EnrollmentFailed || thrownError is VouchflowError.BiometricUnavailable
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Launches [Vouchflow.shared.verify] on the Main dispatcher and waits up to
     * [timeoutSeconds] for it to complete. Returns the [VouchflowResult] on success,
     * or fails the test if an error is thrown or the timeout is exceeded.
     *
     * For biometric tests: after [BiometricSimulator.awaitBiometricPrompt] confirms the
     * prompt is showing, send `adb emu finger touch 1` from the CI host to accept it.
     */
    private fun launchVerifyAndWaitForResult(timeoutSeconds: Long): VouchflowResult? {
        var capturedActivity: TestActivity? = null
        activityRule.scenario.onActivity { capturedActivity = it }

        var result: VouchflowResult? = null
        var thrownError: Exception? = null
        val latch = CountDownLatch(1)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                result = Vouchflow.shared.verify(
                    activity = capturedActivity!!,
                    context = VerificationContext.LOGIN
                )
            } catch (e: Exception) {
                thrownError = e
            } finally {
                latch.countDown()
            }
        }

        // Confirm the biometric prompt appeared (proves enrollment + session initiation succeeded)
        val promptVisible = BiometricSimulator.awaitBiometricPrompt(device)
        assertTrue(
            "BiometricPrompt did not appear — enrollment or session initiation may have failed",
            promptVisible
        )

        // CI runner now sends `adb emu finger touch 1` — wait for verify() to complete
        val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)
        assertTrue("verify() did not complete within ${timeoutSeconds}s", completed)

        if (thrownError != null) {
            fail("verify() threw unexpected error: ${thrownError!!::class.simpleName} — ${thrownError!!.message}")
        }

        return result
    }
}
