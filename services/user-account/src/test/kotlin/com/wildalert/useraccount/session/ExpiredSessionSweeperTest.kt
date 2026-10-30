package com.wildalert.useraccount.session

import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.RegisterHunterRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * The expired-session sweep, against real Postgres.
 *
 * **Deliberately not `@Transactional`**, and for a reason worth writing down because it cost a
 * failing test to work out: the sweeper runs `REQUIRES_NEW`, so it suspends the caller's
 * transaction and opens its own. Inside a rolled-back test transaction it therefore cannot *see*
 * the rows the test just inserted, finds nothing to delete, and the test fails while the
 * production code is correct. The same isolation that stops housekeeping disturbing a login is
 * what makes a transactional test of it meaningless.
 *
 * So this commits, like CreditChargeConcurrencyTest, and tidies up in [cleanUp].
 */
@SpringBootTest
class ExpiredSessionSweeperTest {

    @Autowired private lateinit var hunters: HunterService
    @Autowired private lateinit var sessions: SessionService
    @Autowired private lateinit var sessionRepository: HunterSessionRepository
    @Autowired private lateinit var sweeper: ExpiredSessionSweeper
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val created = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        // Foreign-key order: sessions and ledger, then credit, then the hunter.
        created.forEach { id ->
            jdbc.update("delete from hunter_session where hunter_id = ?", id)
            jdbc.update("delete from credit_ledger where hunter_id = ?", id)
            jdbc.update("delete from hunter_credit where hunter_id = ?", id)
            jdbc.update("delete from hunter where id = ?", id)
        }
        created.clear()
    }

    @Test
    fun `the sweep deletes expired sessions and leaves live ones alone`() {
        val hunterId = newHunter()
        val live = sessions.open(hunters.getById(hunterId))
        val dead = expiredSession(hunterId)

        val swept = sweeper.sweep()

        assertThat(swept).isGreaterThanOrEqualTo(1)
        assertThat(sessionRepository.findById(SessionService.hash(dead))).isEmpty()
        // The one that matters: a sweep must never log anybody out.
        assertThat(sessions.resolve(live)).isEqualTo(hunterId)
    }

    @Test
    fun `a swept session is really gone, not just invisible`() {
        // The bug this pins: a bulk JPQL delete bypasses the persistence context, so without
        // clearAutomatically a findById in the same transaction still returns the deleted row.
        val hunterId = newHunter()
        val dead = expiredSession(hunterId)

        sweeper.sweep()

        assertThat(sessionRepository.findById(SessionService.hash(dead))).isEmpty()
        assertThat(sessions.resolve(dead)).isNull()
    }

    @Test
    fun `sweeping with nothing to sweep is a no-op`() {
        val hunterId = newHunter()
        val live = sessions.open(hunters.getById(hunterId))

        sweeper.sweep()

        assertThat(sessions.resolve(live)).isEqualTo(hunterId)
    }

    private fun expiredSession(hunterId: UUID): String {
        val token = SessionService.newToken()
        sessionRepository.saveAndFlush(
            HunterSession(
                tokenHash = SessionService.hash(token),
                hunterId = hunterId,
                expiresAt = Instant.now().minusSeconds(1),
            ),
        )
        return token
    }

    private fun newHunter(): UUID =
        hunters.register(
            RegisterHunterRequest(
                "sweep-test-${UUID.randomUUID()}@example.com",
                "+420123456789",
                "correct-horse",
            ),
        ).id!!.also { created.add(it) }
}
