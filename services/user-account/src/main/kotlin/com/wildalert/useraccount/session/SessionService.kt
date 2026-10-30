package com.wildalert.useraccount.session

import com.wildalert.useraccount.hunter.Hunter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Issues, resolves and revokes hunter sessions.
 *
 * The token the browser holds is never stored. What goes in the database is its SHA-256, so the
 * table is useless to anyone who reads it, while a lookup stays a single primary-key hit.
 */
@Service
class SessionService(
    private val sessions: HunterSessionRepository,
    private val sweeper: ExpiredSessionSweeper,
    @Value("\${session.ttl-days}") private val ttlDays: Long,
) {
    val ttl: Duration = Duration.ofDays(ttlDays)

    /**
     * Start a session for a hunter and return the **raw** token, which is the only moment it
     * exists outside the browser. The caller puts it in a cookie and forgets it.
     */
    @Transactional
    fun open(hunter: Hunter): String {
        val raw = newToken()
        val expiresAt = Instant.now().plus(ttl)
        sessions.save(HunterSession(tokenHash = hash(raw), hunterId = hunter.id!!, expiresAt = expiresAt))

        // Opportunistic housekeeping, in its own transaction so it cannot disturb or fail this
        // one -- see ExpiredSessionSweeper for why that separation is load-bearing.
        sweeper.sweep()

        return raw
    }

    /**
     * Who owns this token, or null if it is unknown or expired. Expired rows are left for the
     * sweep rather than deleted here, so this stays a read-only path.
     */
    @Transactional(readOnly = true)
    fun resolve(rawToken: String): UUID? {
        val session = sessions.findById(hash(rawToken)).orElse(null) ?: return null
        return if (session.isExpired(Instant.now())) null else session.hunterId
    }

    /** End one session. Unknown tokens are not an error -- logging out twice is not a failure. */
    @Transactional
    fun close(rawToken: String) {
        sessions.deleteById(hash(rawToken))
    }

    /** Delete every expired row. Delegates, so there is one sweep and one set of guarantees. */
    fun closeExpired(): Int = sweeper.sweep()

    companion object {
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        /**
         * 32 bytes of randomness, base64url, 43 characters.
         *
         * Deliberately unlike InboundAddresses.newToken(), which is 12 characters from a
         * 32-character alphabet (~60 bits). That one is an email local-part a human may read
         * aloud or type into a camera; this one is a bearer credential whose only job is to be
         * unguessable, so it gets 256 bits and no concessions to legibility.
         */
        fun newToken(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return encoder.encodeToString(bytes)
        }

        /**
         * SHA-256 hex, unsalted and fast -- which is correct here and would be wrong for a
         * password. We must look a session up *by* its token, so the hash has to be
         * deterministic, and there is nothing to brute-force because the input is 256 bits of
         * randomness rather than something a person chose.
         */
        fun hash(rawToken: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(rawToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
