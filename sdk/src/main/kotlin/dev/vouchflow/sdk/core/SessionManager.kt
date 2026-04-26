package dev.vouchflow.sdk.core

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Provides app lifecycle signals to the verification flow.
 *
 * When the OS sends the app to the background mid-biometric, [BiometricPrompt] cancels the
 * prompt with `ERROR_CANCELED`. [dev.vouchflow.sdk.core.VerificationManager] catches this and
 * calls [waitForForeground] to suspend until the user returns to the app, at which point the
 * biometric prompt is silently re-presented.
 *
 * Uses [ProcessLifecycleOwner] (app-level lifecycle, not per-Activity) so this works correctly
 * regardless of which Activity is in the foreground when the app resumes.
 */
internal object SessionManager {

    /**
     * Suspends until the app next enters the foreground (RESUMED state).
     *
     * Must be called from a coroutine. Transitions the calling coroutine to [Dispatchers.Main]
     * internally to add the lifecycle observer safely.
     */
    suspend fun waitForForeground() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    owner.lifecycle.removeObserver(this)
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }

            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

            continuation.invokeOnCancellation {
                // Must remove observer on main thread — schedule it.
                @Suppress("ForbiddenVoid")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
                }
            }
        }
    }
}
