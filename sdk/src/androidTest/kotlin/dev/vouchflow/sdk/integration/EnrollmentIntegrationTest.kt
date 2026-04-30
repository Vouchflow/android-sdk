package dev.vouchflow.sdk.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vouchflow.sdk.Vouchflow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * Instrumented integration tests for the enrollment state machine against the sandbox environment.
 *
 * Enrollment does not require biometric authentication — [ensureEnrolledForTesting] triggers
 * key generation and POST /v1/enroll directly. Tests are skipped when the device has no auth
 * configured, because Android Keystore's `setUserAuthenticationRequired(true)` requires at
 * least one credential enrolled (biometric or device credential) for key generation to succeed.
 *
 * Run via:
 *   ./gradlew sdk:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=\
 *     dev.vouchflow.sdk.integration.EnrollmentIntegrationTest
 */
@RunWith(AndroidJUnit4::class)
class EnrollmentIntegrationTest {

    @Before
    fun setUp() {
        StagingTestConfig.reset()
        StagingTestConfig.configure()
    }

    @After
    fun tearDown() {
        StagingTestConfig.reset()
    }

    // ── Fresh enrollment ──────────────────────────────────────────────────────

    /**
     * Clean device (no token, no key) → POST /v1/enroll → device token written.
     * Skipped on devices with no auth enrolled (key generation would fail).
     */
    @Test
    fun freshDevice_enrollSucceeds_deviceTokenWritten() {
        assumeTrue(
            "No auth enrolled — Keystore key generation requires at least one credential",
            BiometricSimulator.isAnyAuthAvailable(StagingTestConfig.context)
        )
        assertNull("Pre-condition: no device token before enrollment", Vouchflow.shared.cachedDeviceToken)

        runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }

        val token = Vouchflow.shared.cachedDeviceToken
        assertNotNull("Device token must be written after enrollment", token)
        assertTrue("Device token must start with 'dvt_'", token!!.startsWith("dvt_"))
    }

    // ── Idempotent enrollment ─────────────────────────────────────────────────

    /**
     * Second call to [ensureEnrolledForTesting] hits SkipEnrollment — no network request
     * and the device token is unchanged.
     */
    @Test
    fun alreadyEnrolled_secondCallSkipsEnrollment_tokenUnchanged() {
        assumeTrue(
            "No auth enrolled",
            BiometricSimulator.isAnyAuthAvailable(StagingTestConfig.context)
        )

        runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }
        val firstToken = Vouchflow.shared.cachedDeviceToken
        assertNotNull(firstToken)

        runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }
        val secondToken = Vouchflow.shared.cachedDeviceToken

        assertEquals(
            "SkipEnrollment: device token must be unchanged on second call",
            firstToken,
            secondToken
        )
    }

    // ── Re-enrollment after reset ─────────────────────────────────────────────

    /**
     * After [Vouchflow.reset], the next enrollment call produces a new device token.
     */
    @Test
    fun afterReset_enrollSucceeds_newTokenIssued() {
        assumeTrue(
            "No auth enrolled",
            BiometricSimulator.isAnyAuthAvailable(StagingTestConfig.context)
        )

        runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }
        val firstToken = Vouchflow.shared.cachedDeviceToken
        assertNotNull(firstToken)

        // reset() nulls the singleton, so accessing Vouchflow.shared between
        // reset and configure throws NotConfigured. Reconfigure first, then
        // assert that the freshly re-initialised SDK has no cached token.
        StagingTestConfig.reset()
        StagingTestConfig.configure()
        assertNull("Device token must be null after reset", Vouchflow.shared.cachedDeviceToken)

        runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }
        val secondToken = Vouchflow.shared.cachedDeviceToken

        assertNotNull("Re-enrollment must produce a new device token", secondToken)
        assertNotEquals(
            "Re-enrollment after reset must produce a different device token",
            firstToken,
            secondToken
        )
    }

    // ── Reinstall (token present, key missing) ────────────────────────────────

    /**
     * Simulates an app reinstall: device token survives in AccountManager but the Keystore
     * key is gone. SDK re-enrolls using the existing token (REINSTALL state) — server-side
     * reputation is preserved and the same token is returned.
     */
    @Test
    fun reinstall_existingToken_keyDeleted_reEnrollsWithSameToken() {
        assumeTrue(
            "No auth enrolled",
            BiometricSimulator.isAnyAuthAvailable(StagingTestConfig.context)
        )

        runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }
        val originalToken = Vouchflow.shared.cachedDeviceToken
        assertNotNull(originalToken)

        // Simulate reinstall: delete only the Keystore key, leaving the device token intact
        deleteKeystoreKey()

        runBlocking { Vouchflow.shared.ensureEnrolledForTesting() }
        val tokenAfterReinstall = Vouchflow.shared.cachedDeviceToken

        assertNotNull("Re-enrollment after reinstall must produce a device token", tokenAfterReinstall)
        assertEquals(
            "Reinstall must return the same device token (server preserves reputation)",
            originalToken,
            tokenAfterReinstall
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun deleteKeystoreKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val alias = "dev.vouchflow.sdk.key_v1"
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
    }
}
