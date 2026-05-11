package dev.vouchflow.sdk.integration

import android.content.Context
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.vouchflow.sdk.Confidence
import dev.vouchflow.sdk.SignedBundle
import dev.vouchflow.sdk.Vouchflow
import dev.vouchflow.sdk.VouchflowError
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
 * Instrumented integration tests for signPayload against the sandbox API.
 *
 * Run via:
 *   ./gradlew sdk:connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     dev.vouchflow.sdk.integration.SignPayloadIntegrationTest
 *
 * Requires a device credential (PIN/biometric) for Keystore key access. CI
 * configures PIN 1234 before this test runs (same setup as the existing
 * passcodeEnrolled_* tests).
 */
@RunWith(AndroidJUnit4::class)
class SignPayloadIntegrationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    @get:Rule
    val activityRule = ActivityScenarioRule(TestActivity::class.java)

    @Before
    fun setUp() {
        StagingTestConfig.configure(context)
        StagingTestConfig.reset(context)
    }

    @After
    fun tearDown() {
        StagingTestConfig.reset(context)
    }

    /**
     * passcodeEnrolled_signPayload_succeeds — verifies the full ceremony returns
     * a well-formed SignedBundle when device credential auth is available.
     */
    @Test
    fun passcodeEnrolled_signPayloadSucceeds() {
        assumeTrue(
            "Device credential not configured — CI sets PIN 1234 before this pass",
            BiometricSimulator.isAnyAuthAvailable(context)
        )
        assumeFalse(
            "Biometric tests handled elsewhere; this test focuses on PIN-only sign",
            BiometricSimulator.isBiometricStrongAvailable(context)
        )

        // Pre-enroll without auth.
        kotlinx.coroutines.runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }

        var bundle: SignedBundle? = null
        var thrownError: Exception? = null
        val latch = CountDownLatch(1)

        var capturedActivity: TestActivity? = null
        activityRule.scenario.onActivity { capturedActivity = it }

        // Build a small mandate-shaped payload via map literal — same shape the
        // canonicalizer is tested against in unit tests, so the canonical
        // bytes are predictable.
        val mandate = linkedMapOf<String, Any?>(
            "v" to 1,
            "id" to "mand_android_test",
            "scope" to "send",
        )

        CoroutineScope(Dispatchers.Main).launch {
            try {
                bundle = Vouchflow.shared.signPayload(
                    activity = capturedActivity!!,
                    payload = mandate,
                    context = "mandate_signing",
                    minimumConfidence = Confidence.MEDIUM,
                )
            } catch (e: Exception) {
                thrownError = e
            } finally {
                latch.countDown()
            }
        }

        try {
            BiometricSimulator.acceptWithPin(device, StagingTestConfig.EMULATOR_TEST_PIN)
        } catch (e: BiometricSimulator.PatternLockDetectedException) {
            assumeTrue("Pattern lock not supported — use a PIN emulator.", false)
        }

        assertTrue("signPayload did not return within 30s", latch.await(30, TimeUnit.SECONDS))
        assertNull("Expected no error: $thrownError", thrownError)
        val b = bundle!!
        assertEquals("android", b.platform)
        assertTrue("signing_device_id must start sdv_", b.signingDeviceId.startsWith("sdv_"))
        assertTrue("device_token must start dvt_", b.deviceToken.startsWith("dvt_"))
        assertEquals(3, b.assertion.split(".").size)
        assertEquals("mandate_signing", b.context)
        // Canonical payload should match what JCSCanonicalizer produces for the
        // same input.
        assertEquals("""{"id":"mand_android_test","scope":"send","v":1}""", b.payload)
    }

    /**
     * Canonicalization failure path. Passing a value containing a non-finite
     * double (encoded via the canonicalizer's check) must throw
     * CanonicalizationFailed before any biometric prompt.
     */
    @Test
    fun signPayload_canonicalizationFailure_throwsCanonicalizationFailed() {
        assumeTrue(
            "Device credential required for enrollment",
            BiometricSimulator.isAnyAuthAvailable(context)
        )
        kotlinx.coroutines.runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }

        var thrownError: Exception? = null
        val latch = CountDownLatch(1)
        var capturedActivity: TestActivity? = null
        activityRule.scenario.onActivity { capturedActivity = it }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                Vouchflow.shared.signPayload(
                    activity = capturedActivity!!,
                    payload = mapOf("amount" to Double.POSITIVE_INFINITY),
                    context = "x",
                    minimumConfidence = Confidence.MEDIUM,
                )
            } catch (e: Exception) {
                thrownError = e
            } finally {
                latch.countDown()
            }
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertTrue(
            "Expected CanonicalizationFailed, got: $thrownError",
            thrownError is VouchflowError.CanonicalizationFailed
        )
    }
}
