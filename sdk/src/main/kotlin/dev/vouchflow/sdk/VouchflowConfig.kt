package dev.vouchflow.sdk

/**
 * The environment the SDK operates in.
 *
 * @property baseUrl Base URL for all API requests.
 * @property hostname Hostname used for certificate pinning.
 */
enum class VouchflowEnvironment(val baseUrl: String, val hostname: String) {
    PRODUCTION("https://api.vouchflow.dev", "api.vouchflow.dev"),
    SANDBOX("https://api.vouchflow.dev", "api.vouchflow.dev");
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
 * @param leafCertificatePin Raw base64-encoded SHA-256 of the server leaf certificate's
 *   SubjectPublicKeyInfo. **No `sha256/` prefix** — the SDK adds it internally. To compute:
 *
 *   ```bash
 *   openssl s_client -connect api.vouchflow.dev:443 \
 *       -servername api.vouchflow.dev -showcerts < /dev/null 2>/dev/null \
 *     | awk '/BEGIN CERT/{c++} c==1,/END CERT/' \
 *     | openssl x509 -pubkey -noout \
 *     | openssl pkey -pubin -outform DER \
 *     | openssl dgst -sha256 -binary | base64
 *   ```
 *
 *   Placeholder values (starting with "TODO") disable pinning in debug builds and block
 *   all requests in release builds.
 * @param intermediateCertificatePin Raw base64-encoded SHA-256 of the intermediate CA's
 *   SubjectPublicKeyInfo (currently Let's Encrypt **YE1**). **No `sha256/` prefix.** Pinning
 *   at the intermediate lets the leaf rotate every 60 days (Fly.io's Let's Encrypt cadence)
 *   without forcing an SDK release. ISRG Root X1 is NOT a valid value here — Fly.io's TLS
 *   handshake doesn't include the root, so a root-level pin will never match.
 *
 *   Same openssl one-liner as above, using `c==2` for the second cert in the chain.
 * @param accountManagerStorage When `true` (default), the device token is persisted at the OS
 *   account level via [android.accounts.AccountManager], so it survives app reinstall on most
 *   devices. When `false`, the SDK uses only encrypted in-app storage — the token does not
 *   survive uninstall, and a reinstall presents to the server as a fresh enrollment.
 *   Set `false` to disable OS-level account storage entirely (e.g. for managed-device fleets
 *   that restrict account modification). Note: even when `true`, the SDK silently falls back
 *   to encrypted in-app storage if AccountManager is unavailable on the device or profile.
 */
data class VouchflowConfig(
    val apiKey: String,
    val environment: VouchflowEnvironment = VouchflowEnvironment.PRODUCTION,
    // Live Let's Encrypt YE1 SPKI pins. Refreshed 2026-06-14 against the live chain.
    // When Let's Encrypt rotates intermediates again, refresh both values here. Compute via
    // the openssl one-liner in the KDoc above.
    val leafCertificatePin: String = "NQ7reZqY0tQjef9LBQwbs0gHjrdrroWrd+scM74zQrU=",
    val intermediateCertificatePin: String = "brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=",
    val accountManagerStorage: Boolean = true
) {
    init {
        // The single most common pinning bug we've seen: integrators read the OkHttp docs,
        // see CertificatePinner's "sha256/<base64>" format, and prepend "sha256/" to the
        // value they pass here. PinningInterceptor then builds "sha256/sha256/<base64>",
        // which OkHttp rejects at runtime with an opaque "Certificate pinning failure".
        // Fail at configure() instead, with a message that names the fix.
        require(!leafCertificatePin.startsWith("sha256/")) {
            "leafCertificatePin must be raw base64 SPKI SHA-256, NOT the OkHttp \"sha256/<hash>\" form. " +
                "The SDK adds the prefix internally. Got: $leafCertificatePin"
        }
        require(!intermediateCertificatePin.startsWith("sha256/")) {
            "intermediateCertificatePin must be raw base64 SPKI SHA-256, NOT the OkHttp \"sha256/<hash>\" form. " +
                "The SDK adds the prefix internally. Got: $intermediateCertificatePin"
        }
    }

    internal val hasTodoPlaceholderPins: Boolean
        get() = leafCertificatePin.startsWith("TODO") || intermediateCertificatePin.startsWith("TODO")
}
