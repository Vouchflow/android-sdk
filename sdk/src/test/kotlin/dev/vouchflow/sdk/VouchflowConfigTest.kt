package dev.vouchflow.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VouchflowConfigTest {

    // ── hasTodoPlaceholderPins ────────────────────────────────────────────────

    @Test
    fun `hasTodoPlaceholderPins is true when leafCertificatePin starts with TODO`() {
        val config = VouchflowConfig(
            apiKey = "vsk_live_test",
            leafCertificatePin = "TODO-replace-me",
            intermediateCertificatePin = "y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU="
        )
        assertTrue(config.hasTodoPlaceholderPins)
    }

    @Test
    fun `hasTodoPlaceholderPins is true when intermediateCertificatePin starts with TODO`() {
        val config = VouchflowConfig(
            apiKey = "vsk_live_test",
            leafCertificatePin = "y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=",
            intermediateCertificatePin = "TODO-replace-me-too"
        )
        assertTrue(config.hasTodoPlaceholderPins)
    }

    @Test
    fun `hasTodoPlaceholderPins is false with real pins`() {
        val config = VouchflowConfig(
            apiKey = "vsk_live_test",
            leafCertificatePin = "y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=",
            intermediateCertificatePin = "y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU="
        )
        assertFalse(config.hasTodoPlaceholderPins)
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `default environment is PRODUCTION`() {
        val config = VouchflowConfig(apiKey = "vsk_live_test")
        assertEquals(VouchflowEnvironment.PRODUCTION, config.environment)
    }

    @Test
    fun `default leafCertificatePin is non-empty and not a TODO placeholder`() {
        val config = VouchflowConfig(apiKey = "vsk_live_test")
        assertTrue(
            "Default leafCertificatePin must be non-empty",
            config.leafCertificatePin.isNotEmpty()
        )
        assertFalse(
            "Default leafCertificatePin must not start with TODO",
            config.leafCertificatePin.startsWith("TODO")
        )
    }

    @Test
    fun `default intermediateCertificatePin is non-empty and not a TODO placeholder`() {
        val config = VouchflowConfig(apiKey = "vsk_live_test")
        assertTrue(
            "Default intermediateCertificatePin must be non-empty",
            config.intermediateCertificatePin.isNotEmpty()
        )
        assertFalse(
            "Default intermediateCertificatePin must not start with TODO",
            config.intermediateCertificatePin.startsWith("TODO")
        )
    }

    @Test
    fun `accountManagerStorage defaults to true`() {
        val config = VouchflowConfig(apiKey = "vsk_live_test")
        assertTrue(
            "OS-level account storage must be enabled by default",
            config.accountManagerStorage
        )
    }

    @Test
    fun `accountManagerStorage can be disabled`() {
        val config = VouchflowConfig(apiKey = "vsk_live_test", accountManagerStorage = false)
        assertFalse(config.accountManagerStorage)
    }

    @Test
    fun `configs differing only in accountManagerStorage are not equal`() {
        val withAm = VouchflowConfig(apiKey = "vsk_live_abc", accountManagerStorage = true)
        val withoutAm = VouchflowConfig(apiKey = "vsk_live_abc", accountManagerStorage = false)
        assertTrue(withAm != withoutAm)
    }

    // ── Data class equality ───────────────────────────────────────────────────

    @Test
    fun `two configs with identical fields are equal`() {
        val a = VouchflowConfig(
            apiKey = "vsk_live_abc",
            environment = VouchflowEnvironment.SANDBOX,
            leafCertificatePin = "pin1==",
            intermediateCertificatePin = "pin2=="
        )
        val b = VouchflowConfig(
            apiKey = "vsk_live_abc",
            environment = VouchflowEnvironment.SANDBOX,
            leafCertificatePin = "pin1==",
            intermediateCertificatePin = "pin2=="
        )
        assertEquals(a, b)
    }

    @Test
    fun `configs with different apiKeys are not equal`() {
        val a = VouchflowConfig(apiKey = "vsk_live_aaa")
        val b = VouchflowConfig(apiKey = "vsk_live_bbb")
        assertTrue(a != b)
    }
}
