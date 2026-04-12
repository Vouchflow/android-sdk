package com.vouchflow.sdk.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricPrompt
import com.vouchflow.sdk.internal.VouchflowLogger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Manages the ECDSA P-256 keypair stored in Android Keystore.
 *
 * ## Key properties
 * - Algorithm: EC secp256r1 (P-256), SHA-256 digest
 * - Authentication: required on every use (`timeout = 0`)
 * - `setInvalidatedByBiometricEnrollment(true)`: adding new biometrics invalidates the key,
 *   triggering KEY_INVALIDATED re-enrollment with reputation history preserved
 * - StrongBox if hardware is present; TEE otherwise. Both are recorded for confidence scoring.
 *
 * ## Key validity check
 * [isKeyValid] test-signs with a dummy payload and catches [KeyPermanentlyInvalidatedException].
 * Result is **not** cached here — [com.vouchflow.sdk.core.EnrollmentManager] caches it for the
 * app session after the first [com.vouchflow.sdk.core.EnrollmentManager.ensureEnrolled] call.
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
     * Generates a new EC P-256 keypair in the Android Keystore.
     *
     * Attempts StrongBox first; falls back to TEE silently.
     *
     * @return Triple of (public key, base64-encoded uncompressed public key, isStrongBoxBacked)
     */
    fun generateKeyPair(): Triple<PublicKey, String, Boolean> {
        val canUseStrongBox = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        if (canUseStrongBox) {
            try {
                val keyPair = generateWith(strongBox = true)
                val publicKeyBase64 = encodePublicKey(keyPair.public)
                return Triple(keyPair.public, publicKeyBase64, true)
            } catch (e: StrongBoxUnavailableException) {
                VouchflowLogger.warn("[VouchflowSDK] StrongBox unavailable — falling back to TEE-backed key.")
            }
        }

        val keyPair = generateWith(strongBox = false)
        val publicKeyBase64 = encodePublicKey(keyPair.public)
        return Triple(keyPair.public, publicKeyBase64, false)
    }

    private fun generateWith(strongBox: Boolean): KeyPair {
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        generator.initialize(builder)
        return generator.generateKeyPair()
    }

    /**
     * Encodes the public key as an uncompressed EC point (x963 / 04 || x || y format),
     * base64-encoded without padding. Matches the iOS SDK's `x963Representation`.
     */
    private fun encodePublicKey(publicKey: PublicKey): String {
        val ecKey = publicKey as ECPublicKey
        val x = ecKey.w.affineX.toByteArray()
        val y = ecKey.w.affineY.toByteArray()

        val uncompressed = ByteArray(65)
        uncompressed[0] = 0x04
        copyCoordinate(x, uncompressed, destOffset = 1)
        copyCoordinate(y, uncompressed, destOffset = 33)

        return android.util.Base64.encodeToString(uncompressed, android.util.Base64.NO_WRAP)
    }

    /** Copies a BigInteger byte array (may have leading 0x00 sign byte) into a fixed 32-byte slot. */
    private fun copyCoordinate(coord: ByteArray, dest: ByteArray, destOffset: Int) {
        val stripped = coord.dropWhile { it == 0.toByte() }.toByteArray()
        val start = destOffset + (32 - stripped.size.coerceAtMost(32))
        stripped.copyInto(dest, destinationOffset = start, startIndex = (stripped.size - 32).coerceAtLeast(0))
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
     * [BiometricPrompt.CryptoObject] ready for [com.vouchflow.sdk.core.VerificationManager].
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
        private const val KEY_ALIAS = "com.vouchflow.sdk.key_v1"
        internal const val SIGNING_ALGORITHM = "SHA256withECDSA"
    }
}
