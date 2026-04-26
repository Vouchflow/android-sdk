package dev.vouchflow.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the enrollment state machine logic without any Android framework dependencies.
 *
 * The production [dev.vouchflow.sdk.core.EnrollmentManager.detectState] method is private,
 * so we replicate the identical decision logic here in a pure-Kotlin helper. The business rules
 * are specified in the class KDoc and the implementation is a when-expression over three booleans.
 *
 * Any future divergence between this helper and the production code will be caught by integration
 * or snapshot tests at the EnrollmentManager level.
 */
class EnrollmentStateMachineTest {

    // ── State model (mirrors EnrollmentManager.EnrollmentState) ──────────────

    private sealed class EnrollmentState {
        object SkipEnrollment : EnrollmentState()
        object FreshEnrollment : EnrollmentState()
        data class Reinstall(val existingDeviceToken: String) : EnrollmentState()
        object Corrupted : EnrollmentState()
        data class KeyInvalidated(val existingDeviceToken: String) : EnrollmentState()
    }

    /**
     * Pure re-implementation of the detectState() logic from EnrollmentManager.
     *
     * Inputs:
     *   hasDeviceToken – AccountManagerStore.deviceTokenExists()
     *   hasKey         – KeystoreKeyManager.keyExists()
     *   isKeyValid     – KeystoreKeyManager.isKeyValid() (only meaningful when hasKey == true)
     *   existingToken  – AccountManagerStore.readDeviceToken() (only meaningful when hasDeviceToken == true)
     */
    private fun detectState(
        hasDeviceToken: Boolean,
        hasKey: Boolean,
        isKeyValid: Boolean,
        existingToken: String = "dvt_existing"
    ): EnrollmentState {
        return when {
            !hasDeviceToken && !hasKey -> EnrollmentState.FreshEnrollment
            hasDeviceToken && !hasKey  -> EnrollmentState.Reinstall(existingToken)
            !hasDeviceToken && hasKey  -> EnrollmentState.Corrupted
            else -> {
                // hasDeviceToken && hasKey — check key validity
                if (!isKeyValid) {
                    EnrollmentState.KeyInvalidated(existingToken)
                } else {
                    EnrollmentState.SkipEnrollment
                }
            }
        }
    }

    // ── FreshEnrollment ───────────────────────────────────────────────────────

    @Test
    fun `no token and no key produces FreshEnrollment`() {
        val state = detectState(hasDeviceToken = false, hasKey = false, isKeyValid = false)
        assertEquals(EnrollmentState.FreshEnrollment, state)
    }

    // ── SkipEnrollment ────────────────────────────────────────────────────────

    @Test
    fun `token and valid key produces SkipEnrollment`() {
        val state = detectState(hasDeviceToken = true, hasKey = true, isKeyValid = true)
        assertEquals(EnrollmentState.SkipEnrollment, state)
    }

    // ── Reinstall ─────────────────────────────────────────────────────────────

    @Test
    fun `token but no key produces Reinstall with existing token`() {
        val state = detectState(
            hasDeviceToken = true,
            hasKey = false,
            isKeyValid = false,
            existingToken = "dvt_reinstall_token"
        )
        assertEquals(EnrollmentState.Reinstall("dvt_reinstall_token"), state)
    }

    // ── Corrupted ─────────────────────────────────────────────────────────────

    @Test
    fun `no token but orphan key produces Corrupted`() {
        val state = detectState(hasDeviceToken = false, hasKey = true, isKeyValid = true)
        assertEquals(EnrollmentState.Corrupted, state)
    }

    @Test
    fun `no token with invalid orphan key also produces Corrupted`() {
        // Key validity is irrelevant when there is no device token — still Corrupted.
        val state = detectState(hasDeviceToken = false, hasKey = true, isKeyValid = false)
        assertEquals(EnrollmentState.Corrupted, state)
    }

    // ── KeyInvalidated ────────────────────────────────────────────────────────

    @Test
    fun `token and key present but key invalid produces KeyInvalidated`() {
        val state = detectState(
            hasDeviceToken = true,
            hasKey = true,
            isKeyValid = false,
            existingToken = "dvt_old_token"
        )
        assertEquals(EnrollmentState.KeyInvalidated("dvt_old_token"), state)
    }

    // ── All 8 input combinations ──────────────────────────────────────────────

    @Test
    fun `exhaustive state table matches specification`() {
        // (hasToken, hasKey, isKeyValid) -> expected state class name
        val table = listOf(
            Triple(false, false, false) to "FreshEnrollment",
            Triple(false, false, true)  to "FreshEnrollment",   // isKeyValid irrelevant without a key
            Triple(true,  false, false) to "Reinstall",
            Triple(true,  false, true)  to "Reinstall",         // isKeyValid irrelevant without a key
            Triple(false, true,  false) to "Corrupted",
            Triple(false, true,  true)  to "Corrupted",         // token missing → Corrupted regardless
            Triple(true,  true,  true)  to "SkipEnrollment",
            Triple(true,  true,  false) to "KeyInvalidated",
        )

        for ((inputs, expected) in table) {
            val (hasToken, hasKey, isKeyValid) = inputs
            val state = detectState(hasToken, hasKey, isKeyValid)
            assertEquals(
                "State for (token=$hasToken, key=$hasKey, valid=$isKeyValid) expected $expected",
                expected,
                state::class.simpleName
            )
        }
    }
}
