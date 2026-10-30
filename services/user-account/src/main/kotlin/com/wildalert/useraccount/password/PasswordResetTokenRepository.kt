package com.wildalert.useraccount.password

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, String> {

    /**
     * Does this hunter already have a fresh, unspent token? The throttle's whole question.
     *
     * `exists` rather than a count or a fetch: we never need the row, only whether to send
     * another mail. The `hunter_id` index exists for this.
     */
    fun existsByHunterIdAndUsedAtIsNullAndCreatedAtAfter(hunterId: UUID, cutoff: Instant): Boolean

    /**
     * Throw away every outstanding token for a hunter. Called when one is spent, and when a new
     * one is issued -- a hunter who asks twice should not end up with two working links.
     *
     * `clearAutomatically` for the reason the session sweep documents: a bulk JPQL delete goes
     * straight to the database and leaves the persistence context believing the rows are still
     * there, so a later read in the same transaction returns a token that no longer exists.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PasswordResetToken t where t.hunterId = :hunterId and t.usedAt is null")
    fun deleteUnusedByHunterId(@Param("hunterId") hunterId: UUID): Int

    /**
     * The sweep. Spent tokens go too, not just expired ones: once `used_at` is set the row has no
     * job left, and a reset table that only ever grows is a table nobody prunes.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PasswordResetToken t where t.expiresAt <= :cutoff or t.usedAt is not null")
    fun deleteSpentOrExpiredAt(@Param("cutoff") cutoff: Instant): Int
}
