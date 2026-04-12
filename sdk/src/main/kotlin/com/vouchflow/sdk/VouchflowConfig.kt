package com.vouchflow.sdk

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
 *         customerId = "cust_abc123",
 *         environment = VouchflowEnvironment.PRODUCTION
 *     )
 * )
 * ```
 *
 * @param apiKey Write-scoped API key. Safe to store in your build config; never use the
 *   read-scoped key here.
 * @param customerId Your Vouchflow customer ID (e.g. `cust_abc123`). Included in enroll and
 *   verify requests so the server can scope device tokens to your account.
 * @param environment Defaults to [VouchflowEnvironment.PRODUCTION]. Use
 *   [VouchflowEnvironment.SANDBOX] during development — verifications do not count toward
 *   billing and do not enter the network graph.
 * @param leafCertificatePin SHA-256 hash of the Let's Encrypt intermediate CA's
 *   SubjectPublicKeyInfo serving api.vouchflow.dev. Pinned at intermediate (not leaf) level
 *   to survive Fly.io's 60-day Let's Encrypt leaf rotations without requiring an SDK release.
 *   Placeholder values disable pinning in debug builds and block all connections in release builds.
 * @param intermediateCertificatePin SHA-256 hash of ISRG Root X1 (Let's Encrypt root CA).
 *   Essentially permanent — serves as a safety net if the intermediate CA is ever rotated.
 */
data class VouchflowConfig(
    val apiKey: String,
    val customerId: String,
    val environment: VouchflowEnvironment = VouchflowEnvironment.PRODUCTION,
    val leafCertificatePin: String = "iFvwVyJSxnQdyaUvUERIf+8qk7gRze3612JMwoO3zdU=",
    val intermediateCertificatePin: String = "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="
) {
    internal val hasTodoPlaceholderPins: Boolean
        get() = leafCertificatePin.startsWith("TODO") || intermediateCertificatePin.startsWith("TODO")
}
