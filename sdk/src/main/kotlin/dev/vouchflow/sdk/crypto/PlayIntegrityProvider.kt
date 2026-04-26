package dev.vouchflow.sdk.crypto

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import dev.vouchflow.sdk.internal.VouchflowLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Obtains a Play Integrity API token for device attestation at enrollment time.
 *
 * Play Integrity verifies that the request originates from a genuine, unmodified app on a
 * real Android device. The token is forwarded to the Vouchflow server, which validates it
 * using Vouchflow's own Google Cloud project credentials (baked in — no developer configuration
 * required).
 *
 * ## Reliability notes
 * - Play Integrity routes through Google servers — allow 10 seconds.
 * - Permanently unavailable on devices without Google Play Services (some Chinese markets,
 *   de-Googled ROMs, Amazon Fire). In these cases [attest] returns `null` and enrollment
 *   continues with `confidence_ceiling = medium` for the device lifetime.
 * - No dev/prod environment split — Play Integrity is always production regardless of SDK
 *   environment setting.
 */
internal object PlayIntegrityProvider {

    /**
     * TODO: Replace with the real Vouchflow Google Cloud project number once provisioned.
     * Obtained from Google Cloud Console → IAM → project settings.
     */
    private const val VOUCHFLOW_CLOUD_PROJECT_NUMBER = 0L

    private const val TIMEOUT_MS = 10_000L

    /**
     * Requests a Play Integrity token for the given nonce.
     *
     * @param context Application context.
     * @param nonce A unique string for this enrollment attempt. The idempotency key is used
     *   as the nonce — it is already sufficiently random and unique.
     * @return The Play Integrity token string, or `null` if attestation failed or timed out.
     *   Failure is non-fatal — enrollment continues without attestation.
     */
    suspend fun attest(context: Context, nonce: String): String? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(TIMEOUT_MS) {
                val manager = IntegrityManagerFactory.create(context.applicationContext)
                val request = IntegrityTokenRequest.builder()
                    .setNonce(nonce)
                    .setCloudProjectNumber(VOUCHFLOW_CLOUD_PROJECT_NUMBER)
                    .build()

                suspendCancellableCoroutine { continuation ->
                    val task = manager.requestIntegrityToken(request)
                    task.addOnSuccessListener { response ->
                        continuation.resume(response.token())
                    }
                    task.addOnFailureListener { error ->
                        continuation.resumeWithException(error)
                    }
                }
            }
        } catch (e: Exception) {
            VouchflowLogger.warn("[VouchflowSDK] Play Integrity attestation failed — proceeding without attestation. confidence_ceiling will be medium. Error: ${e.message}")
            null
        }
    }
}
