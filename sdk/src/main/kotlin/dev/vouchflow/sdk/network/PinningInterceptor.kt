package dev.vouchflow.sdk.network

import android.content.Context
import dev.vouchflow.sdk.VouchflowConfig
import dev.vouchflow.sdk.internal.VouchflowLogger
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * Configures certificate pinning on the [OkHttpClient].
 *
 * Two SPKI SHA-256 pins are checked (OR semantics — either passing is sufficient):
 * - **Leaf pin**: SHA-256 of the server leaf certificate SubjectPublicKeyInfo.
 * - **Intermediate pin**: SHA-256 of the intermediate CA SubjectPublicKeyInfo.
 *
 * This allows zero-downtime leaf rotation: ship new leaf, intermediate pin continues passing,
 * rotate leaf pin in the next SDK release.
 *
 * ## Placeholder pins
 * - **Debug app**: pinning is skipped with a warning. Enables testing before TLS is finalised.
 * - **Release app**: all requests are blocked. Do not ship without real pins.
 *
 * Pin format expected in [VouchflowConfig]: raw base64-encoded SHA-256 of the
 * SubjectPublicKeyInfo. **No `sha256/` prefix** — this interceptor prepends it before passing
 * the value to OkHttp's [CertificatePinner]. Passing a value with the prefix already attached
 * yields `sha256/sha256/<hash>` and fails pinning at runtime; [VouchflowConfig.init] rejects
 * that case eagerly so the misconfiguration surfaces at `configure()` time instead.
 */
internal class PinningInterceptor(
    private val config: VouchflowConfig,
    private val isDebugApp: Boolean
) {

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (config.hasTodoPlaceholderPins) {
            if (isDebugApp) {
                VouchflowLogger.warn(
                    "[VouchflowSDK] Certificate pinning DISABLED — placeholder pins detected. " +
                    "Set real leafCertificatePin and intermediateCertificatePin in VouchflowConfig before shipping."
                )
                return builder
            } else {
                VouchflowLogger.error(
                    "[VouchflowSDK] Blocking all connections — placeholder certificate pins detected in a release build. " +
                    "Set real leafCertificatePin and intermediateCertificatePin in VouchflowConfig."
                )
                return builder.addInterceptor(RejectAllInterceptor())
            }
        }

        val hostname = config.environment.hostname
        val pinner = CertificatePinner.Builder()
            .add(hostname, "sha256/${config.leafCertificatePin}")
            .add(hostname, "sha256/${config.intermediateCertificatePin}")
            .build()

        return builder.certificatePinner(pinner)
    }

    private class RejectAllInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            throw IOException(
                "[VouchflowSDK] Certificate pinning failed — placeholder pins in a release build. " +
                "Set real pins in VouchflowConfig."
            )
        }
    }

    companion object {
        fun isDebugApp(context: Context): Boolean {
            return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        }
    }
}
