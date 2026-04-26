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
