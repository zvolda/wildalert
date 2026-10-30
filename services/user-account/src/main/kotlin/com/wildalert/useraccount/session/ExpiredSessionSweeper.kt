package com.wildalert.useraccount.session

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Deletes expired session rows, in a transaction of its own.
 *
 * A separate bean rather than a method on SessionService, and REQUIRES_NEW rather than joining
 * the caller, for two reasons that are the same reason twice: housekeeping must not be able to
 * affect the thing it rides along with, and must not be able to fail it.
 *
 * - The sweep is a bulk JPQL delete, which clears the persistence context (see the repository).
 *   Joining a login's transaction would detach whatever that transaction was holding -- harmless
 *   today, a confusing bug the first time someone wraps registration and login together.
 * - If the sweep throws, a hunter should still get logged in. A failed cleanup is not a failed
 *   login, and sharing a transaction would make it one.
 *
 * Called on login because that is rare and bounded, and because on Cloud Run with scale-to-zero a
 * `@Scheduled` task would run only while an instance happened to be alive -- which for a job
 * nothing depends on is worse than free, it is unpredictable.
 */
@Component
class ExpiredSessionSweeper(private val sessions: HunterSessionRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sweep(): Int {
        val swept = sessions.deleteExpiredAt(Instant.now())
        if (swept > 0) log.info("Swept {} expired session(s)", swept)
        return swept
    }
}
