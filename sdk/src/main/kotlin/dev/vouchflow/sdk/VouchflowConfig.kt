package dev.vouchflow.sdk

/**
 * The environment the SDK operates in.
 *
 * @property baseUrl Base URL for all API requests.
 * @property hostname Hostname used for certificate pinning.
 */
enum class VouchflowEnvironment(val baseUrl: String, val hostname: String) {
    PRODUCTION("https://api.vouchflow.dev", "api.vouchflow.dev"),
    SANDBOX("https://sandbox.api.vouchflow.dev", "sandbox.api.vouchflow.dev");
}

/**
 * Configuration passed to [Vouchflow.configure] at app startup.
 *
 * ```kotlin
 * Vouchflow.configure(
 *     VouchflowConfig(
 *         apiKey = "vsk_live_...",
 *         environment = VouchflowEnvironment.PRODUCTION
 *     )
 * )
 * ```
 *
 * @param apiKey Write-scoped API key. Safe to store in your build config; never use the
 *   read-scoped key here.
 * @param environment Defaults to [VouchflowEnvironment.PRODUCTION]. Use
 *   [VouchflowEnvironment.SANDBOX] during development — verifications do not count toward
 *   billing and do not enter the network graph.
 * @param leafCertificatePin SPKI SHA-256 of the Let's Encrypt E7 intermediate CA serving
 *   api.vouchflow.dev. Pinned at intermediate (not leaf) level so the SDK survives Fly.io's
 *   60-day Let's Encrypt leaf rotations without requiring an SDK update. Placeholder values
 *   (starting with "TODO") disable pinning in debug builds and block all requests in release builds.
 * @param intermediateCertificatePin Backup SPKI SHA-256 pin. Currently set to the same E7
 *   intermediate as [leafCertificatePin]. Update both when Let's Encrypt rotates to a new
 *   intermediate CA. ISRG Root X1 is NOT used because Fly.io's TLS handshake does not include
 *   the root certificate, so it cannot be matched by OkHttp's CertificatePinner.
 */
data class VouchflowConfig(
    val apiKey: String,
    val environment: VouchflowEnvironment = VouchflowEnvironment.PRODUCTION,
    // E7 intermediate CA SubjectPublicKeyInfo SHA-256. Valid while Let's Encrypt issues from E7.
    val leafCertificatePin: String = "y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=",
    val intermediateCertificatePin: String = "y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU="
) {
    internal val hasTodoPlaceholderPins: Boolean
        get() = leafCertificatePin.startsWith("TODO") || intermediateCertificatePin.startsWith("TODO")
}
