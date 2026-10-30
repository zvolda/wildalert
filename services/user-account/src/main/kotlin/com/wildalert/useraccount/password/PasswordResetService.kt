package com.wildalert.useraccount.password

import com.wildalert.useraccount.hunter.HunterRepository
import com.wildalert.useraccount.session.SessionService
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * "I forgot my password": issue a one-time link by email, then spend it.
 *
 * **The hard part of this flow is what it refuses to tell the caller.** `request` answers the
 * same way for an address that has an account and one that does not, because the endpoint is
 * public and anything else turns it into a tool for discovering which email addresses are
 * registered. That is the same reasoning `InvalidCredentialsException` already applies to login,
 * and it is why this class logs what happened but returns nothing.
 *
 * The token itself reuses [SessionService.newToken] and [SessionService.hash] rather than growing
 * a second copy: 32 bytes of randomness, and only the SHA-256 is stored. The rationale for an
 * unsalted fast digest is written out on those methods -- lookup is *by* the token, and 256 bits
 * of randomness has nothing to brute-force.
 */
@Service
class PasswordResetService(
    private val hunters: HunterRepository,
    private val tokens: PasswordResetTokenRepository,
    private val sessions: SessionService,
    private val sweeper: ExpiredResetTokenSweeper,
    private val passwordEncoder: PasswordEncoder,
    private val email: EmailSender,
    private val properties: PasswordResetProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Start a reset, if that address belongs to an active hunter who can log in.
     *
     * Returns nothing, deliberately. The controller answers 202 either way, so there is no value
     * here that could leak into a response by accident -- the type system carries the privacy
     * rule instead of a comment asking the caller to be careful.
     *
     * Every early return is a *silent* one, and each has its own reason:
     * - no such hunter: an existence oracle if visible;
     * - deactivated, or no password at all: both already answer login with the same generic 401,
     *   so revealing them here would route around that;
     * - throttled: a hunter who clicks twice gets one mail, and an attacker typing someone
     *   else's address cannot use us to flood their inbox.
     */
    @Transactional
    fun request(rawEmail: String) {
        // Housekeeping first, in its own transaction, so a backlog never accumulates on the one
        // path that is naturally rare. See ExpiredResetTokenSweeper.
        sweeper.sweep()

        val normalized = rawEmail.trim().lowercase()
        val hunter = hunters.findByEmail(normalized)
        if (hunter == null) {
            log.info("Password reset requested for an address with no account")
            return
        }
        if (!hunter.active || hunter.passwordHash == null) {
            log.info("Password reset requested for hunter {} which cannot log in", hunter.id)
            return
        }

        val hunterId = hunter.id!!
        val cutoff = Instant.now().minus(properties.throttle)
        if (tokens.existsByHunterIdAndUsedAtIsNullAndCreatedAtAfter(hunterId, cutoff)) {
            log.info("Password reset for hunter {} throttled; a fresh link was already sent", hunterId)
            return
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

        email.send(
            EmailMessage(
                to = hunter.email,
                subject = "Reset your WildAlert password",
                body = resetBody(raw),
            ),
        )
        log.info("Password reset link sent for hunter {}", hunterId)
    }

    /**
     * Spend a token and set the new password, or throw [InvalidResetTokenException].
     *
     * Three things happen together, in one transaction, and the second and third are not
     * optional extras:
     * 1. the new hash is written;
     * 2. the token is marked used and every *other* outstanding token for that hunter is deleted;
     * 3. **every session for that hunter is deleted.**
     *
     * (3) is the point of the whole flow. A hunter resetting a password may be doing it because
     * somebody else is in the account, and a reset that left the intruder's cookie working would
     * be security theatre. There is no cookie to re-issue here either: the caller is on the reset
     * screen, not signed in, and is sent to the sign-in form afterwards -- which also proves the
     * new password works.
     */
    @Transactional
    fun confirm(rawToken: String, newPassword: String) {
        val now = Instant.now()
        val token = tokens.findById(SessionService.hash(rawToken)).orElse(null)
            ?: throw InvalidResetTokenException()
        if (!token.isUsable(now)) {
            // Expired or already spent. One exception for both, so a replayed link and a stale
            // one cannot be told apart from outside.
            throw InvalidResetTokenException()
        }

        val hunter = hunters.findById(token.hunterId).orElse(null) ?: throw InvalidResetTokenException()
        if (!hunter.active) throw InvalidResetTokenException()

        hunter.setPassword(passwordEncoder.encode(newPassword))
        token.markUsed(now)
        tokens.deleteUnusedByHunterId(hunter.id!!)
        val ended = sessions.closeAll(hunter.id!!)

        log.info("Password reset completed for hunter {}; {} session(s) ended", hunter.id, ended)
    }

    /**
     * The mail body. Plain text, and it says what to do if the request was not theirs -- a reset
     * mail arriving unbidden is the earliest warning a hunter gets that someone is trying.
     *
     * **The link is a query parameter on the root, `/?reset=…`, not a path like `/reset`**, and
     * that is load-bearing rather than cosmetic. The gateway serves the React app from
     * `static-locations` with **no SPA fallback** -- any unmatched path there is a 404 -- so a
     * path-shaped link would have arrived in hunters' inboxes already broken. The React app has no
     * router for the same reason (see App.tsx), and a query parameter needs neither.
     *
     * The token is URL-encoded even though base64url needs no escaping: that is a property of the
     * current generator, not of this template, and a future token format with `+` or `/` in it
     * would otherwise break links silently.
     */
    private fun resetBody(rawToken: String): String {
        val encoded = URLEncoder.encode(rawToken, StandardCharsets.UTF_8)
        val link = "${properties.publicBaseUrl.trimEnd('/')}/?reset=$encoded"
        val minutes = properties.ttl.toMinutes()
        return """
            Someone asked to reset the password for your WildAlert account.

            Open this link to choose a new one:
            $link

            The link works once and expires in $minutes minutes.

            If this was not you, you can ignore this email -- your password has not changed. Your
            account is only at risk if someone else can read this message.
        """.trimIndent()
    }
}
