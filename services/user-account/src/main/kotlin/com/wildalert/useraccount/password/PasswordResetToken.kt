package com.wildalert.useraccount.password

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

/**
 * One outstanding "forgot my password" link. Maps to `password_reset_token`
 * (V6__create_password_reset_token.sql).
 *
 * Deliberately shaped like [com.wildalert.useraccount.session.HunterSession]: the primary key is
 * the SHA-256 hex of the token the hunter received, so the table cannot be turned back into
 * working reset links by anyone who reads it.
 *
 * The one structural difference is [usedAt]. A session ends by having its row deleted; a reset
 * token is *spent*, and keeping the spent row is what lets a replayed link be recognised rather
 * than merely unknown. Both answer the caller the same way.
 */
@Entity
@Table(name = "password_reset_token")
class PasswordResetToken(
    @Id
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    val tokenHash: String,

    @Column(name = "hunter_id", nullable = false, updatable = false)
    val hunterId: UUID,

    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) {
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @Column(name = "used_at")
    var usedAt: Instant? = null
        protected set

    /** Expiry is inclusive, as with sessions: a token whose instant has arrived is already gone. */
    fun isExpired(now: Instant): Boolean = !expiresAt.isAfter(now)

    /**
     * Usable exactly once, and only before it expires.
     *
     * Both conditions are checked here rather than at the call site so that "is this token any
     * good?" has one answer in one place -- a second copy of this logic is how a replay slips
     * through.
     */
    fun isUsable(now: Instant): Boolean = usedAt == null && !isExpired(now)

    /** Spend it. Idempotent in effect: the first use is the one that counts. */
    fun markUsed(now: Instant) {
        if (usedAt == null) usedAt = now
    }
}
