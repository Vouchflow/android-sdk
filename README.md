# Vouchflow Android SDK

Device-native identity verification for Android apps. Vouchflow uses Android Keystore cryptography and biometrics to verify that a user is operating from a known, trusted device — without passwords or third-party redirects.

## Requirements

- Android API 28+ (Android 9.0 Pie)
- Kotlin 1.9+
- Google Play Services (required for Play Integrity attestation)

## Installation

### Gradle

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.vouchflow:android-sdk:1.0.0")
}
```

Or in Groovy `build.gradle`:

```groovy
dependencies {
    implementation 'com.vouchflow:android-sdk:1.0.0'
}
```

The SDK's manifest is merged automatically. No additional permissions need to be declared in your app's `AndroidManifest.xml`.

## Setup

Call `Vouchflow.configure()` once at app startup, before any other SDK method. The earliest safe point is `Application.onCreate()`.

```kotlin
import com.vouchflow.sdk.Vouchflow
import com.vouchflow.sdk.VouchflowConfig

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Vouchflow.configure(
            VouchflowConfig(
                apiKey = "vsk_live_...",
                customerId = "cust_..."
            )
        )
    }
}
```

No `Context` argument is needed — the SDK captures it automatically via a `ContentProvider` that initialises before `Application.onCreate()`. The `configure()` call itself is context-free.

`apiKey` is your write-scoped API key from the Vouchflow dashboard. `customerId` is your Vouchflow customer ID. Both are safe to store in your `BuildConfig` — do not use a read-scoped key here.

## Verification

All SDK methods are `suspend` functions. Call them from a coroutine, typically with `lifecycleScope.launch` inside an Activity or Fragment.

### Happy path

```kotlin
lifecycleScope.launch {
    try {
        val result = Vouchflow.shared.verify(
            activity = this@MainActivity,
            context = VerificationContext.LOGIN
        )
        // result.verified          — Boolean
        // result.confidence        — Confidence.HIGH / MEDIUM / LOW
        // result.deviceToken       — String (pass to your server for reputation queries)
        // result.signals           — VouchflowSignals
        println("Verified with ${result.confidence} confidence")
    } catch (e: VouchflowError) {
        // Handle errors (see Error Handling below)
    }
}
```

`activity` must be the currently-visible `ComponentActivity` (this includes `AppCompatActivity` and `FragmentActivity`). `BiometricPrompt` is bound to it — do not cache the activity reference.

### Verification context

Pass the action the user is performing. This is stored on the verification record and included in webhook payloads.

| Context | Use for |
|---|---|
| `VerificationContext.SIGNUP` | New account creation |
| `VerificationContext.LOGIN` | Signing in to an existing account |
| `VerificationContext.SENSITIVE_ACTION` | High-value actions: password change, payment, export |

### Minimum confidence

Require a minimum confidence level. If the device cannot meet it, the SDK throws `VouchflowError.MinimumConfidenceUnmet` rather than returning a low-confidence result.

```kotlin
val result = Vouchflow.shared.verify(
    activity = this,
    context = VerificationContext.SENSITIVE_ACTION,
    minimumConfidence = Confidence.HIGH
)
```

## Error handling

```kotlin
lifecycleScope.launch {
    try {
        val result = Vouchflow.shared.verify(activity = this@MainActivity, context = VerificationContext.LOGIN)
        // success

    } catch (e: VouchflowError.BiometricCancelled) {
        // User tapped Cancel on the biometric prompt.
        // Show a retry button. Optionally offer email fallback:
        val fallback = Vouchflow.shared.requestFallback(
            sessionId = e.sessionId,
            email = currentUserEmail,
            reason = FallbackReason.BIOMETRIC_CANCELLED
        )
        showOtpInput(expiresAt = fallback.expiresAt)

    } catch (e: VouchflowError.BiometricFailed) {
        // Biometric failed (wrong face/finger, lockout, hardware error).
        // Offer fallback or hard-fail depending on your policy.
        val fallback = Vouchflow.shared.requestFallback(
            sessionId = e.sessionId,
            email = currentUserEmail,
            reason = FallbackReason.BIOMETRIC_FAILED
        )
        showOtpInput(expiresAt = fallback.expiresAt)

    } catch (e: VouchflowError.BiometricUnavailable) {
        // No biometric hardware or no biometrics enrolled.
        // Hard-fail or gate the feature.

    } catch (e: VouchflowError.MinimumConfidenceUnmet) {
        // Device cannot reach the required confidence level.
        // Do not offer fallback — this is a device posture issue, not a user error.

    } catch (e: VouchflowError.NetworkUnavailable) {
        // No network connection. Prompt the user to check connectivity and retry.

    } catch (e: VouchflowError.EnrollmentFailed) {
        // Enrollment failed. The SDK will retry automatically on next launch.
        // You may choose to degrade the experience or hard-fail.

    } catch (e: VouchflowError) {
        // Unexpected error. Log and surface a generic error state.
    }
}
```

## Email fallback

When biometric verification fails or is unavailable, you can offer email OTP as a fallback. The SDK SHA-256 hashes the email internally before transmission — do not pre-hash it.

### Step 1 — Initiate fallback

Call `requestFallback` with the `sessionId` from the caught error and the user's email address.

```kotlin
val fallback = Vouchflow.shared.requestFallback(
    sessionId = e.sessionId,   // from VouchflowError.BiometricFailed or .BiometricCancelled
    email = userEmail,
    reason = FallbackReason.BIOMETRIC_FAILED
)
// fallback.fallbackSessionId — pass to submitFallbackOtp
// fallback.expiresAt         — show a countdown timer (5-minute window)
```

### Step 2 — Submit OTP

```kotlin
val result = Vouchflow.shared.submitFallbackOtp(
    sessionId = fallback.fallbackSessionId,
    otp = userEnteredCode
)
// result.verified   — Boolean
// result.confidence — always Confidence.LOW for email fallback
```

### Fallback reasons

Pass the most specific reason that applies:

| Reason | When to use |
|---|---|
| `BIOMETRIC_FAILED` | Biometric attempt failed |
| `BIOMETRIC_CANCELLED` | User cancelled the biometric prompt |
| `BIOMETRIC_UNAVAILABLE` | No biometric hardware or biometrics not enrolled |
| `ATTESTATION_UNAVAILABLE` | Play Integrity not available (no Google Play Services) |
| `MINIMUM_CONFIDENCE_UNMET` | Device cannot meet the required confidence level |
| `KEY_INVALIDATED` | Keystore key was invalidated (e.g. new biometric enrolled) |
| `DEVELOPER_INITIATED` | Your app bypassed biometric for its own reasons |

## Result reference

### `VouchflowResult`

Returned by `verify()`.

| Property | Type | Description |
|---|---|---|
| `verified` | `Boolean` | Whether verification succeeded |
| `confidence` | `Confidence` | `HIGH`, `MEDIUM`, or `LOW` |
| `deviceToken` | `String` | Stable device identifier — pass to your server for reputation queries |
| `deviceAgeDays` | `Int` | Days since this device was first enrolled |
| `networkVerifications` | `Int` | Total verifications for this device across the Vouchflow network |
| `firstSeen` | `Instant?` | When this device was first enrolled |
| `signals` | `VouchflowSignals` | Device signals (see below) |
| `fallbackUsed` | `Boolean` | Always `false` for `VouchflowResult` — fallback returns `FallbackVerificationResult` |
| `context` | `VerificationContext` | The context passed to `verify()` |

### `VouchflowSignals`

| Property | Type | Description |
|---|---|---|
| `persistentToken` | `Boolean` | Device token survived app deletion and reinstall (AccountManager persistence confirmed) |
| `biometricUsed` | `Boolean` | Biometric authentication was used for this verification |
| `crossAppHistory` | `Boolean` | Device has verified across more than one Vouchflow-integrated app |
| `anomalyFlags` | `List<String>` | Anomaly flags from the network graph — empty for clean devices |
| `attestationVerified` | `Boolean` | Play Integrity was verified at enrollment time |

### `FallbackVerificationResult`

Returned by `submitFallbackOtp()`.

| Property | Type | Description |
|---|---|---|
| `verified` | `Boolean` | Whether the OTP was correct |
| `confidence` | `Confidence` | Always `LOW` — email OTP proves inbox access, not device presence |
| `sessionState` | `String` | Final session state |
| `fallbackSignals` | `FallbackSignals` | Signals available from the fallback flow |

## Configuration reference

```kotlin
VouchflowConfig(
    apiKey = "vsk_live_...",           // Required. Write-scoped key from the dashboard.
    customerId = "cust_...",           // Required. Your Vouchflow customer ID.
    environment = VouchflowEnvironment.PRODUCTION,  // Optional. Default: PRODUCTION
    leafCertificatePin = "...",        // Optional. SHA-256 of the Vouchflow intermediate CA SPKI.
    intermediateCertificatePin = "..."  // Optional. SHA-256 of the ISRG Root X1 SPKI.
)
```

### Environments

| Environment | Description |
|---|---|
| `PRODUCTION` | Live environment. Verifications count toward billing and enter the network graph. |
| `SANDBOX` | Development environment. Verifications are free and isolated from the network graph. |

### Certificate pinning

The SDK pins the Vouchflow TLS certificate by default using the Let's Encrypt intermediate CA (not the leaf) to survive 60-day leaf certificate rotations without requiring an SDK update. In debug builds, placeholder pin values disable pinning with a runtime warning. **In release builds, placeholder values cause all requests to fail** — replace them with the real pins from the Vouchflow dashboard before shipping.

## How it works

1. **Enrollment** — On first launch, the SDK generates an EC P-256 key pair in the Android Keystore (StrongBox where available, TEE otherwise) and registers the device with the Vouchflow API. Play Integrity is used to verify device and app integrity at enrollment time. Enrollment is automatic and transparent to the user.

2. **Verification** — When `verify()` is called, the SDK retrieves a challenge from the server, presents a biometric prompt (`BiometricPrompt` with `BIOMETRIC_STRONG`), and signs the challenge with the Keystore-backed private key inside the authenticated `BiometricPrompt` callback. The server verifies the signature against the enrolled public key.

3. **Key invalidation** — If the user adds a new fingerprint or face, Android invalidates the Keystore key. The SDK detects this automatically and re-enrolls transparently, preserving the device token and its network reputation.

4. **Network graph** — Device signals are shared across Vouchflow-integrated apps (with customer consent). A device that has verified across multiple apps builds a richer history, resulting in higher confidence scores.

5. **Fallback** — If biometric is unavailable or fails, email OTP provides a fallback path. The OTP is delivered to the user's email address; only a SHA-256 hash of the address is transmitted to the server.

## Security notes

- The Keystore private key never leaves the device. Vouchflow only ever sees the public key and challenge signatures.
- The key requires biometric authentication for every use (`setUserAuthenticationRequired(true)`) and is invalidated if new biometrics are enrolled (`setInvalidatedByBiometricEnrollment(true)`).
- Emails are SHA-256 hashed before transmission and are never stored in plaintext by the server.
- The SDK enforces TLS certificate pinning in production builds to prevent interception.
- API keys are hashed before storage server-side — raw keys are never persisted.
- Device tokens are stored in AccountManager, which persists across app reinstalls but is cleared on factory reset.
