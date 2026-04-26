package dev.vouchflow.sdk.storage

import java.time.Instant

/**
 * In-memory cache for an active verification session.
 *
 * **Never persisted to disk.** If the process is killed while a session is active, the session
 * is lost and a new one is initiated on the next [dev.vouchflow.sdk.Vouchflow.verify] call —
 * correct and expected behaviour.
 *
 * The cache is used to restore state after the app returns to the foreground following a
 * system-level biometric cancellation ([android.hardware.biometrics.BiometricPrompt] ERROR_CANCELED).
 */
internal class SessionCache {

    data class CachedSession(
        val sessionId: String,
        val challenge: String,
        val expiresAt: Instant,
        /**
         * Number of consecutive expirations for this session chain. When this reaches 2,
         * [dev.vouchflow.sdk.VouchflowError.SessionExpiredRepeatedly] is thrown.
         */
        val expiryCount: Int
    ) {
        val isExpired: Boolean get() = Instant.now() >= expiresAt
    }

    @Volatile private var session: CachedSession? = null

    fun store(session: CachedSession) {
        this.session = session
    }

    fun current(): CachedSession? = session

    fun clear() {
        session = null
    }
}
