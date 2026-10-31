package com.wildalert.useraccount.password

import com.wildalert.useraccount.hunter.HunterRepository
import com.wildalert.useraccount.session.SessionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The database half of a reset request: decide whether an address earns a link, and persist one.
 *
 * **A separate bean from [PasswordResetService], and not for tidiness.** Spring's `@Transactional`
 * works through a proxy, so a call from one method of a class to another method of the *same*
 * class never passes through it — the annotation is silently ignored and the work runs with no
 * transaction. That trap is why [ExpiredResetTokenSweeper] and the session sweeper are their own
 * beans as well. Here it matters because the send has to happen *after* this commits: an HTTPS
 * call to a provider must not hold a Cloud SQL connection, and a failed send must not roll the
 * token back into a 500 that distinguishes a real address from an unknown one.
 */
@Component
class PasswordResetTokenIssuer(
    private val hunters: HunterRepository,
    private val tokens: PasswordResetTokenRepository,
    private val properties: PasswordResetProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A token that has been persisted and now needs delivering. */
    data class IssuedToken(val hunterId: UUID, val email: String, val rawToken: String)

    /**
     * Returns null for every case that must look identical from outside, each silent for its own
     * reason:
     * - no such hunter: an existence oracle if visible;
     * - deactivated, or no password at all: both already answer login with the same generic 401,
     *   so revealing them here would route around that;
     * - throttled: a hunter who clicks twice gets one mail, and an attacker typing someone
     *   else's address cannot use us to flood their inbox.
     */
    @Transactional
    fun issue(rawEmail: String): IssuedToken? {
        val normalized = rawEmail.trim().lowercase()
        val hunter = hunters.findByEmail(normalized)
        if (hunter == null) {
            log.info("Password reset requested for an address with no account")
            return null
        }
        if (!hunter.active || hunter.passwordHash == null) {
            log.info("Password reset requested for hunter {} which cannot log in", hunter.id)
            return null
        }

        val hunterId = hunter.id!!
        val cutoff = Instant.now().minus(properties.throttle)
        if (tokens.existsByHunterIdAndUsedAtIsNullAndCreatedAtAfter(hunterId, cutoff)) {
            log.info("Password reset for hunter {} throttled; a fresh link was already sent", hunterId)
            return null
        }

        // One working link at a time. Asking twice should replace the first mail's token, not add
        // a second key to the same door.
        tokens.deleteUnusedByHunterId(hunterId)

        val raw = SessionService.newToken()
        tokens.save(
            PasswordResetToken(
                tokenHash = SessionService.hash(raw),
                hunterId = hunterId,
                expiresAt = Instant.now().plus(properties.ttl),
            ),
        )
        return IssuedToken(hunterId, hunter.email, raw)
    }

    /**
     * Throw away an undelivered token, in a transaction of its own because the issuing one has
     * already committed by the time a send can fail.
     */
    @Transactional
    fun discard(hunterId: UUID) {
        tokens.deleteUnusedByHunterId(hunterId)
    }
}
