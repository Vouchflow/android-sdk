package dev.vouchflow.sdk.integration

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Helpers for querying biometric availability and interacting with BiometricPrompt UI
 * via UiAutomator in instrumented integration tests.
 *
 * ## Fingerprint simulation (biometric-enrolled emulator)
 * When a test requires biometric acceptance, the CI host runner must send the ADB command
 * after the prompt appears:
 *
 *   adb emu finger touch 1
 *
 * This cannot be triggered from within the instrumented test APK because `adb` runs on the
 * host machine, not inside the emulator. The test calls [awaitBiometricPrompt] to confirm
 * the prompt is visible, then waits for the result — CI sends the finger touch in parallel.
 *
 * ## Emulator setup for each passkey type
 * Biometric:
 *   1. AVD created with `-feature Fingerprint`  (enrolls virtual fingerprint with ID 1)
 *   2. Set any PIN so the device credential is also available (optional)
 *
 * Passcode/PIN only (no biometric):
 *   adb shell locksettings set-pin --old '' 1234
 *
 * No auth:
 *   Fresh emulator with default settings (no lock screen configured)
 */
internal object BiometricSimulator {

    private const val BIOMETRIC_DIALOG_TIMEOUT_MS = 10_000L
    private const val PIN_ENTRY_TIMEOUT_MS = 10_000L

    // ── Availability ──────────────────────────────────────────────────────────

    fun isBiometricStrongAvailable(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun isDeviceCredentialAvailable(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun isAnyAuthAvailable(context: Context): Boolean =
        isBiometricStrongAvailable(context) || isDeviceCredentialAvailable(context)

    fun isApiAtLeast30(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // ── Prompt detection ──────────────────────────────────────────────────────

    /**
     * Waits up to [BIOMETRIC_DIALOG_TIMEOUT_MS] for the system biometric dialog to appear.
     * Returns true if the dialog is visible within the timeout.
     *
     * After this returns true, the CI runner should send `adb emu finger touch 1` to accept.
     */
    fun awaitBiometricPrompt(device: UiDevice): Boolean =
        device.wait(Until.hasObject(By.pkg("com.android.systemui")), BIOMETRIC_DIALOG_TIMEOUT_MS)

    // ── PIN entry ─────────────────────────────────────────────────────────────

    /**
     * Waits for a PIN entry dialog (BiometricPrompt DEVICE_CREDENTIAL flow) and types
     * the given PIN.
     *
     * If a pattern grid is detected instead of a PIN field, this method skips by throwing
     * [PatternLockDetectedException] — use [org.junit.Assume.assumeTrue] to handle this.
     */
    fun acceptWithPin(device: UiDevice, pin: String) {
        // Wait for any credential entry UI to appear
        val credentialUiAppeared = device.wait(
            Until.hasObject(By.pkg("com.android.systemui")),
            PIN_ENTRY_TIMEOUT_MS
        )
        check(credentialUiAppeared) { "Credential dialog did not appear within ${PIN_ENTRY_TIMEOUT_MS}ms" }

        // Look for PIN / password text field — common resource IDs across API levels
        val pinField = device.findObject(By.res("com.android.systemui", "pinEntry"))
            ?: device.findObject(By.res("com.android.systemui", "passwordEntry"))
            ?: device.findObject(By.clazz("android.widget.EditText"))

        if (pinField == null) {
            // Pattern lock — no text field; automated pattern drawing is not supported
            throw PatternLockDetectedException()
        }

        pinField.text = pin

        // Confirm — try common button labels, fall back to Enter key
        val confirmButton = device.findObject(By.text(Pattern.compile("OK", Pattern.CASE_INSENSITIVE)))
            ?: device.findObject(By.text(Pattern.compile("Confirm", Pattern.CASE_INSENSITIVE)))
            ?: device.findObject(By.desc(Pattern.compile("Enter", Pattern.CASE_INSENSITIVE)))
        confirmButton?.click() ?: device.pressEnter()
    }

    class PatternLockDetectedException : Exception(
        "Pattern lock detected — automated pattern interaction is not supported. " +
        "Re-run this test on an emulator configured with PIN or password lock."
    )
}
