package com.vouchflow.sdk

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.vouchflow.sdk.core.EnrollmentManager
import com.vouchflow.sdk.core.FallbackManager
import com.vouchflow.sdk.core.VerificationManager
import com.vouchflow.sdk.crypto.ChallengeProcessor
import com.vouchflow.sdk.crypto.KeystoreKeyManager
import com.vouchflow.sdk.internal.VouchflowLogger
import com.vouchflow.sdk.network.VouchflowAPIClient
import com.vouchflow.sdk.storage.AccountManagerStore
import com.vouchflow.sdk.storage.SessionCache

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
 * via a [com.vouchflow.sdk.internal.VouchflowInitProvider] content provider that initialises
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
 *         sessionId = e.sessionId,
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

    /** Captured by [com.vouchflow.sdk.internal.VouchflowInitProvider] before configure() is called. */
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

        return VouchflowInstance(verificationManager, fallbackManager)
    }
}

/**
 * The configured SDK instance returned by [Vouchflow.shared].
 *
 * All methods are `suspend` — call them from a coroutine or with `lifecycleScope.launch { }`.
 */
class VouchflowInstance internal constructor(
    private val verificationManager: VerificationManager,
    private val fallbackManager: FallbackManager
) {

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
     * Initiates email OTP fallback for a verification session.
     *
     * Call this after catching [VouchflowError.BiometricFailed] or [VouchflowError.BiometricCancelled].
     * The SDK SHA-256 hashes the email internally — do not pre-hash it.
     *
     * @param sessionId The `sessionId` from the thrown [VouchflowError] associated value.
     * @param email The user's plain-text email address. Never stored or logged by the SDK.
     * @param reason Why fallback is being requested.
     * @return A [FallbackResult] containing the [FallbackResult.fallbackSessionId] and OTP expiry.
     */
    suspend fun requestFallback(
        sessionId: String,
        email: String,
        reason: FallbackReason = FallbackReason.BIOMETRIC_FAILED
    ): FallbackResult = fallbackManager.requestFallback(sessionId, email, reason)

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
}
