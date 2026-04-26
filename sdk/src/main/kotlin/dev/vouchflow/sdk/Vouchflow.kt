package dev.vouchflow.sdk

import android.content.Context
import androidx.fragment.app.FragmentActivity
import dev.vouchflow.sdk.core.EnrollmentManager
import dev.vouchflow.sdk.core.FallbackManager
import dev.vouchflow.sdk.core.VerificationManager
import dev.vouchflow.sdk.crypto.ChallengeProcessor
import dev.vouchflow.sdk.crypto.KeystoreKeyManager
import dev.vouchflow.sdk.internal.VouchflowLogger
import dev.vouchflow.sdk.network.VouchflowAPIClient
import dev.vouchflow.sdk.storage.AccountManagerStore
import dev.vouchflow.sdk.storage.SessionCache

/**
 * The main entry point for the Vouchflow SDK.
 *
 * ## Setup
 * Call [configure] once at app startup, before any other SDK method — typically in
 * [android.app.Application.onCreate] or in a Jetpack Startup [Initializer]:
 * ```kotlin
 * Vouchflow.configure(
 *     VouchflowConfig(apiKey = "vsk_live_...", customerId = "cust_abc123")
 * )
 * ```
 * No [android.content.Context] argument is needed — the SDK captures it automatically
 * via a [dev.vouchflow.sdk.internal.VouchflowInitProvider] content provider that initialises
 * before [android.app.Application.onCreate].
 *
 * ## Verification
 * ```kotlin
 * try {
 *     val result = Vouchflow.shared.verify(activity = this, context = VerificationContext.SIGNUP)
 *     // result.verified, result.confidence, result.deviceToken, result.signals
 * } catch (e: VouchflowError.BiometricCancelled) {
 *     // Show retry button
 * } catch (e: VouchflowError.BiometricFailed) {
 *     val fallback = Vouchflow.shared.requestFallback(
 *         email = userEmail,
 *         reason = FallbackReason.BIOMETRIC_FAILED
 *     )
 *     // Show OTP input
 * }
 * ```
 *
 * ## Fallback OTP submission
 * ```kotlin
 * val result = Vouchflow.shared.submitFallbackOtp(
 *     sessionId = fallback.fallbackSessionId,
 *     otp = userEnteredCode
 * )
 * ```
 */
object Vouchflow {

    /** Captured by [dev.vouchflow.sdk.internal.VouchflowInitProvider] before configure() is called. */
    @Volatile internal var applicationContext: Context? = null

    @Volatile private var _instance: VouchflowInstance? = null

    /**
     * The shared SDK instance. Access only after calling [configure].
     * @throws VouchflowError.NotConfigured if [configure] has not been called.
     */
    val shared: VouchflowInstance
        get() = _instance ?: throw VouchflowError.NotConfigured

    /**
     * Configures the SDK. Must be called once before any other SDK method.
     *
     * Thread-safe — safe to call from any thread. Subsequent calls replace the current
     * configuration (useful in tests; avoid in production).
     *
     * @throws VouchflowError.InvalidApiKey if [VouchflowConfig.apiKey] does not start with `vsk_`.
     * @throws IllegalStateException if called before the application context is available
     *   (indicates a manifest merger problem with the SDK's ContentProvider).
     */
    @JvmStatic
    @Synchronized
    fun configure(config: VouchflowConfig) {
        if (!config.apiKey.startsWith("vsk_")) {
            throw VouchflowError.InvalidApiKey
        }

        val ctx = applicationContext
            ?: throw IllegalStateException(
                "[VouchflowSDK] Application context is not available. " +
                "Ensure the SDK's AndroidManifest.xml merged correctly. " +
                "If you are using a custom Application class, try calling configure() in Application.onCreate()."
            )

        _instance = buildInstance(ctx, config)
        VouchflowLogger.debug("[VouchflowSDK] Configured. environment=${config.environment}")
    }

    /**
     * Wipes all local enrollment data: deletes the Keystore keypair and clears the device token
     * and pending enrollment placeholder from AccountManager.
     *
     * After calling this the SDK is in [EnrollmentState.FreshEnrollment] — the next [verify] call
     * will re-enroll from scratch. Server-side reputation history is NOT affected.
     *
     * Primarily intended for testing and support scenarios.
     */
    @JvmStatic
    @Synchronized
    fun reset() {
        val ctx = applicationContext ?: return
        KeystoreKeyManager(ctx).deleteKey()
        val store = AccountManagerStore(ctx)
        store.deleteDeviceToken()
        store.deletePendingToken()
        _instance = null
        VouchflowLogger.debug("[VouchflowSDK] Reset complete — local enrollment data cleared.")
    }

    private fun buildInstance(context: Context, config: VouchflowConfig): VouchflowInstance {
        val store = AccountManagerStore(context)
        val keystoreKeyManager = KeystoreKeyManager(context)
        val challengeProcessor = ChallengeProcessor()
        val sessionCache = SessionCache()
        val apiClient = VouchflowAPIClient(config, context)

        val enrollmentManager = EnrollmentManager(
            config = config,
            context = context,
            store = store,
            keystoreKeyManager = keystoreKeyManager,
            apiClient = apiClient
        )

        val verificationManager = VerificationManager(
            config = config,
            store = store,
            keystoreKeyManager = keystoreKeyManager,
            challengeProcessor = challengeProcessor,
            sessionCache = sessionCache,
            enrollmentManager = enrollmentManager,
            apiClient = apiClient
        )

        val fallbackManager = FallbackManager(
            store = store,
            apiClient = apiClient
        )

        return VouchflowInstance(verificationManager, fallbackManager, store)
    }
}

/**
 * The configured SDK instance returned by [Vouchflow.shared].
 *
 * All methods are `suspend` — call them from a coroutine or with `lifecycleScope.launch { }`.
 */
class VouchflowInstance internal constructor(
    private val verificationManager: VerificationManager,
    private val fallbackManager: FallbackManager,
    private val store: dev.vouchflow.sdk.storage.AccountManagerStore
) {

    /**
     * The locally-cached device token if this device has previously enrolled, null otherwise.
     *
     * Reading this property requires no biometric prompt, no network call, and no Activity.
     * It is safe to call at cold start before the first [verify] — use it to derive at-rest
     * keys or seed local databases before any user interaction is required.
     *
     * Returns null on first launch (before the first successful [verify]) and after [Vouchflow.reset].
     * The value is stable across [verify] calls for the same enrolled device; it only changes on
     * re-enrollment (e.g. after a biometric reconfiguration or app reinstall).
     */
    val cachedDeviceToken: String?
        get() = store.readDeviceToken()

    // ── Verification ──────────────────────────────────────────────────────────

    /**
     * Verifies the current device. Handles enrollment, biometric presentation, and challenge
     * signing transparently. The developer needs only one call for the happy path.
     *
     * @param activity The currently-visible [FragmentActivity] ([AppCompatActivity] is also
     *   accepted). [BiometricPrompt] is bound to this Activity — do not cache it.
     * @param context The action being verified (signup, login, sensitive_action).
     * @param minimumConfidence If the device cannot reach this confidence level,
     *   [VouchflowError.MinimumConfidenceUnmet] is thrown instead of initiating fallback.
     * @return A [VouchflowResult] on success.
     * @throws VouchflowError
     */
    suspend fun verify(
        activity: FragmentActivity,
        context: VerificationContext,
        minimumConfidence: Confidence? = null
    ): VouchflowResult = verificationManager.verify(activity, context, minimumConfidence)

    // ── Fallback ──────────────────────────────────────────────────────────────

    /**
     * Initiates email OTP fallback for the most recently initiated verification session.
     *
     * Call this after catching [VouchflowError.BiometricFailed] or [VouchflowError.BiometricCancelled].
     * The session ID is managed internally — you do not need to pass it.
     * The SDK SHA-256 hashes the email internally — do not pre-hash it.
     *
     * @param email The user's plain-text email address. Never stored or logged by the SDK.
     * @param reason Why fallback is being requested.
     * @return A [FallbackResult] containing the [FallbackResult.fallbackSessionId] and OTP expiry.
     * @throws VouchflowError.NoActiveSession if [verify] has not been called yet or the session
     *   already completed successfully.
     */
    suspend fun requestFallback(
        email: String,
        reason: FallbackReason = FallbackReason.BIOMETRIC_FAILED
    ): FallbackResult {
        val sessionId = verificationManager.pendingFallbackSessionId
            ?: throw VouchflowError.NoActiveSession
        return fallbackManager.requestFallback(sessionId, email, reason)
    }

    /**
     * Submits the OTP entered by the user to complete a fallback verification.
     *
     * @param sessionId The [FallbackResult.fallbackSessionId] from [requestFallback].
     * @param otp The 6-digit code entered by the user.
     * @return A [FallbackVerificationResult] with [Confidence.LOW].
     */
    suspend fun submitFallbackOtp(
        sessionId: String,
        otp: String
    ): FallbackVerificationResult = fallbackManager.submitOtp(sessionId, otp)

    // ── Reset ─────────────────────────────────────────────────────────────────

    // (See companion object for Vouchflow.reset() static method)

    // ── Test harness utilities ────────────────────────────────────────────────

    /**
     * For developer test harnesses: initiates a verify session on the server without biometric
     * authentication. The session is stored as the pending fallback session, so a subsequent
     * [requestFallback] call will work without requiring a cancelled biometric prompt.
     *
     * Do not use this in production app code.
     *
     * @return The server-assigned session ID (for logging).
     */
    suspend fun initiateSessionForFallbackTesting(): String =
        verificationManager.initiateSession()
}
