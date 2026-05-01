package dev.vouchflow.sdk.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.biometric.BiometricPrompt
import dev.vouchflow.sdk.internal.VouchflowLogger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Manages the ECDSA P-256 keypair stored in Android Keystore.
 *
 * ## Key properties
 * - Algorithm: EC secp256r1 (P-256), SHA-256 digest
 * - Authentication: required on every use (`timeout = 0`)
 * - On API 30+: allows `AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL` so the key can be
 *   unlocked by fingerprint, face, or device PIN/pattern/password.
 * - On API < 30: biometric-only (device credential not supported via per-use keys on older APIs).
 * - StrongBox if hardware is present; TEE otherwise. Both are recorded for confidence scoring.
 *
 * ## Key validity check
 * [isKeyValid] test-signs with a dummy payload and catches [KeyPermanentlyInvalidatedException].
 * Result is **not** cached here — [dev.vouchflow.sdk.core.EnrollmentManager] caches it for the
 * app session after the first [dev.vouchflow.sdk.core.EnrollmentManager.ensureEnrolled] call.
 *
 * ## Signing with biometric
 * The private key requires biometric authentication on every use. The key operation must be
 * initiated inside a [BiometricPrompt] callback via a [BiometricPrompt.CryptoObject].
 * [createCryptoObject] initialises the [Signature] up to — but not including — the biometric gate;
 * the [Signature] is then passed through [BiometricPrompt] and the authenticated instance is
 * returned for [ChallengeProcessor.sign].
 */
internal class KeystoreKeyManager(private val context: Context) {

    // ── Key generation ────────────────────────────────────────────────────────

    /**
     * Result of [generateKeyPair]: the new keypair, the SubjectPublicKeyInfo-encoded base64
     * public key, the StrongBox-backed flag, and the Keystore Attestation certificate chain
     * leaf-first when an [attestationChallenge] was supplied. The chain proves to the server
     * that the private key resides in real TEE/StrongBox hardware (rooted in the Google
     * Hardware Attestation Root CA) and that the attestation was generated in response to
     * the supplied challenge — i.e. it can't be replayed.
     *
     * `attestationChain` is empty when attestation is not supported on the device (very old
     * KeyMaster) or fails for any reason — enrollment continues without attestation in that
     * case (confidence_ceiling = medium).
     */
    data class KeyGenerationResult(
        val publicKey: PublicKey,
        val publicKeyBase64: String,
        val strongBoxBacked: Boolean,
        val attestationChain: List<String>,
    )

    /**
     * Generates a new EC P-256 keypair in the Android Keystore.
     *
     * Attempts StrongBox first; falls back to TEE silently.
     *
     * @param attestationChallenge bytes the Keystore embeds in the attestation extension of
     *   the leaf cert. The server compares this against the value it expects (the enrollment
     *   idempotency key) to defeat replay. Pass null to skip attestation.
     */
    fun generateKeyPair(attestationChallenge: ByteArray? = null): KeyGenerationResult {
        val canUseStrongBox = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        if (canUseStrongBox) {
            try {
                val keyPair = generateWith(strongBox = true, attestationChallenge = attestationChallenge)
                return KeyGenerationResult(
                    publicKey = keyPair.public,
                    publicKeyBase64 = encodePublicKey(keyPair.public),
                    strongBoxBacked = true,
                    attestationChain = readAttestationChain(),
                )
            } catch (e: StrongBoxUnavailableException) {
                VouchflowLogger.warn("[VouchflowSDK] StrongBox unavailable — falling back to TEE-backed key.")
            }
        }

        val keyPair = generateWith(strongBox = false, attestationChallenge = attestationChallenge)
        return KeyGenerationResult(
            publicKey = keyPair.public,
            publicKeyBase64 = encodePublicKey(keyPair.public),
            strongBoxBacked = false,
            attestationChain = readAttestationChain(),
        )
    }

    private fun generateWith(strongBox: Boolean, attestationChallenge: ByteArray?): KeyPair {
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // setInvalidatedByBiometricEnrollment is incompatible with AUTH_DEVICE_CREDENTIAL
            // (throws IllegalArgumentException on API 30+). Omit it — key remains valid when the
            // user adds new biometrics, which is acceptable when device credential is allowed.
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0, // timeout = 0: authentication required on every key use
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    // API < 30: AUTH_DEVICE_CREDENTIAL per-use is not supported; biometric-only.
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox) {
                    setIsStrongBoxBacked(true)
                }
                if (attestationChallenge != null) {
                    setAttestationChallenge(attestationChallenge)
                }
            }
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        generator.initialize(builder)
        return generator.generateKeyPair()
    }

    /**
     * Returns the certificate chain installed on the just-generated key, base64-DER encoded,
     * leaf-first. The chain is rooted in the Google Hardware Attestation Root CA when the
     * device supports key attestation; on devices without attestation support it consists
     * of a single self-signed cert and the server will treat the device as un-attested.
     *
     * Returns an empty list on any failure (unsupported KeyMaster, key missing, etc.) — the
     * caller treats that as "no attestation available."
     */
    private fun readAttestationChain(): List<String> {
        return try {
            val ks = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
            val chain = ks.getCertificateChain(KEY_ALIAS) ?: return emptyList()
            chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
        } catch (e: Exception) {
            VouchflowLogger.warn("[VouchflowSDK] Failed to read Keystore attestation chain: ${e.message}")
            emptyList()
        }
    }

    /**
     * Encodes the public key as a SubjectPublicKeyInfo DER structure, base64-encoded without
     * padding. This is the standard Java `PublicKey.encoded` (X.509) format and is directly
     * importable by Node.js's `crypto.createPublicKey({ format: 'der', type: 'spki' })`.
     *
     * Previously this returned a raw uncompressed EC point (04 || x || y), which cannot be parsed
     * as a PEM or DER key by the server's Node.js crypto module.
     */
    private fun encodePublicKey(publicKey: PublicKey): String {
        return android.util.Base64.encodeToString(publicKey.encoded, android.util.Base64.NO_WRAP)
    }

    // ── Key existence and validity ────────────────────────────────────────────

    fun keyExists(): Boolean {
        return try {
            val ks = keyStore()
            ks.containsAlias(KEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns true if the key exists and has not been permanently invalidated.
     *
     * A key is permanently invalidated when the user adds a new biometric (or removes all
     * biometrics) after the key was generated with [setInvalidatedByBiometricEnrollment]=true.
     * Catches [KeyPermanentlyInvalidatedException] on `initSign()` — the earliest point at
     * which the Keystore signals invalidation.
     */
    fun isKeyValid(): Boolean {
        return try {
            val ks = keyStore()
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return false
            val signature = Signature.getInstance(SIGNING_ALGORITHM)
            signature.initSign(entry.privateKey) // throws KeyPermanentlyInvalidatedException if invalidated
            true
        } catch (e: KeyPermanentlyInvalidatedException) {
            false
        } catch (e: Exception) {
            true // Unknown error — assume valid; next sign attempt will surface the real failure
        }
    }

    /**
     * Whether the existing key is backed by StrongBox.
     *
     * Uses [android.security.keystore.KeyInfo] to check the security level at runtime.
     */
    fun isStrongBoxBacked(): Boolean {
        return try {
            val ks = keyStore()
            val key = ks.getKey(KEY_ALIAS, null) ?: return false
            val keyFactory = KeyFactory.getInstance(key.algorithm, KEYSTORE_PROVIDER)
            val keyInfo = keyFactory.getKeySpec(key, android.security.keystore.KeyInfo::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── Crypto object for BiometricPrompt ─────────────────────────────────────

    /**
     * Initialises a [Signature] with the Keystore private key and wraps it in a
     * [BiometricPrompt.CryptoObject] ready for [dev.vouchflow.sdk.core.VerificationManager].
     *
     * The [Signature] is pre-initialised for signing but NOT yet authenticated — biometric
     * authentication via [BiometricPrompt] completes the unlock. After successful authentication,
     * [BiometricPrompt.AuthenticationResult.cryptoObject] returns the same [Signature] instance,
     * now permitted to sign.
     *
     * Returns `null` if the key does not exist (re-enrollment required).
     */
    fun createCryptoObject(): BiometricPrompt.CryptoObject? {
        return try {
            val ks = keyStore()
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return null
            val signature = Signature.getInstance(SIGNING_ALGORITHM)
            signature.initSign(entry.privateKey)
            BiometricPrompt.CryptoObject(signature)
        } catch (e: KeyPermanentlyInvalidatedException) {
            null // Caller (VerificationManager) will detect KEY_INVALIDATED state and re-enroll
        } catch (e: Exception) {
            VouchflowLogger.warn("[VouchflowSDK] createCryptoObject failed: ${e.message}")
            null
        }
    }

    // ── Key deletion ──────────────────────────────────────────────────────────

    fun deleteKey() {
        try {
            val ks = keyStore()
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            VouchflowLogger.warn("[VouchflowSDK] Failed to delete Keystore key: ${e.message}")
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.vouchflow.sdk.key_v1"
        internal const val SIGNING_ALGORITHM = "SHA256withECDSA"
    }
}
