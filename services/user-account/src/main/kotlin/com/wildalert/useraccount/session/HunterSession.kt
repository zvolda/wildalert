package com.wildalert.useraccount.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

/**
 * One logged-in browser. Maps to `hunter_session` (V5__add_hunter_password_and_session.sql).
 *
 * The primary key is the SHA-256 hex of the session token, so a row cannot be turned back into a
 * usable credential by anyone who reads the table. Resolving a session is therefore a primary-key
 * hit on a value the server computes from the cookie it was handed.
 */
@Entity
@Table(name = "hunter_session")
class HunterSession(
    @Id
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    val tokenHash: String,

    @Column(name = "hunter_id", nullable = false, updatable = false)
    val hunterId: UUID,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
) {
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    /** Expiry is inclusive: a session whose instant has arrived is already gone. */
    fun isExpired(now: Instant): Boolean = !expiresAt.isAfter(now)
}
