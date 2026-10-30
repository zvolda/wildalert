package com.wildalert.useraccount.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface HunterSessionRepository : JpaRepository<HunterSession, String> {

    /** Log a hunter out of every browser at once. The hunter_id index exists for this. */
    @Modifying
    fun deleteByHunterId(hunterId: UUID): Int

    /**
     * The expired-session sweep. A delete-by-query rather than find-then-delete, so a backlog is
     * one statement instead of a result set we have to hold in memory.
     *
     * `clearAutomatically` because a bulk JPQL delete goes straight to the database and leaves
     * the persistence context believing the rows are still there -- so a later `findById` in the
     * same transaction cheerfully returns a session that no longer exists. Found by
     * SessionIntegrationTest, which is exactly the bug a mocked repository could not have shown.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from HunterSession s where s.expiresAt <= :cutoff")
    fun deleteExpiredAt(cutoff: Instant): Int
}
