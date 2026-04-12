package com.vouchflow.sdk.crypto

import android.util.Base64
import java.security.Signature

/**
 * Signs server-issued challenges with the device's Keystore private key.
 *
 * The [Signature] instance arrives already authenticated from [BiometricPrompt] — the
 * biometric gate has been passed and the private key is unlocked for this signing operation.
 *
 * ## Wire format
 * Challenge: base64-encoded random nonce from `POST /v1/verify`.
 * Signed output: DER-encoded ECDSA signature, base64-encoded without padding.
 * The DER format matches the iOS SDK's `derRepresentation` output — both are verifiable by
 * the server's `node:crypto` `verify()` call with the registered public key.
 */
internal class ChallengeProcessor {

    /**
     * Signs the challenge with the authenticated [Signature].
     *
     * @param challengeBase64 The `challenge` field from `POST /v1/verify` response.
     * @param signature The authenticated [Signature] from [BiometricPrompt.AuthenticationResult].
     *   It must have already been passed through [BiometricPrompt] — calling [Signature.sign]
     *   without biometric authentication will throw [java.security.SignatureException].
     * @return Base64-encoded DER-format ECDSA signature for `signed_challenge` in the request.
     */
    fun sign(challengeBase64: String, signature: Signature): String {
        val challengeBytes = Base64.decode(challengeBase64, Base64.DEFAULT)
        signature.update(challengeBytes)
        val signed = signature.sign()
        return Base64.encodeToString(signed, Base64.NO_WRAP)
    }
}
