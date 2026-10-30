package com.wildalert.useraccount.session

import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.RegisterHunterRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The session round-trip against real Postgres.
 *
 * Needs a database: `docker compose up -d postgres`. What is worth proving here is that a *row*
 * behaves correctly -- it is stored hashed, it resolves, it stops resolving when it expires, and
 * the sweep removes exactly the dead ones. A mocked repository would only prove the mock was
 * called, which is why the token mechanics live in SessionServiceTest and this does not repeat
 * them.
 *
 * `@Transactional` so everything rolls back: the dev database is shared, and `hunter.email` is
 * unique, which is also why every email here is uniquified.
 */
@SpringBootTest
@Transactional
class SessionIntegrationTest {

    @Autowired
    lateinit var hunters: HunterService

    @Autowired
    lateinit var sessions: SessionService

    @Autowired
    lateinit var sessionRepository: HunterSessionRepository

    @Test
    fun `the token is stored hashed, so the table hands over nothing usable`() {
        val hunterId = newHunter()

        val token = sessions.open(hunters.getById(hunterId))

        val stored = sessionRepository.findAll().single { it.hunterId == hunterId }
        assertThat(stored.tokenHash).isEqualTo(SessionService.hash(token))
        // The assertion this test exists for.
        assertThat(stored.tokenHash).isNotEqualTo(token)
        assertThat(sessionRepository.findById(token)).isEmpty()
    }

    @Test
    fun `a fresh session resolves to its hunter`() {
        val hunterId = newHunter()

        val token = sessions.open(hunters.getById(hunterId))

        assertThat(sessions.resolve(token)).isEqualTo(hunterId)
    }

    @Test
    fun `an unknown token resolves to nobody`() {
        assertThat(sessions.resolve(SessionService.newToken())).isNull()
    }

    @Test
    fun `the expiry is the configured ttl, not a guess`() {
        val hunterId = newHunter()
        val before = Instant.now()

        val token = sessions.open(hunters.getById(hunterId))

        val stored = sessionRepository.findById(SessionService.hash(token)).orElseThrow()
        assertThat(stored.expiresAt).isAfter(before.plus(sessions.ttl).minusSeconds(60))
        assertThat(stored.expiresAt).isBefore(Instant.now().plus(sessions.ttl).plusSeconds(60))
    }

    @Test
    fun `closing a session stops it resolving`() {
        val hunterId = newHunter()
        val token = sessions.open(hunters.getById(hunterId))

        sessions.close(token)

        assertThat(sessions.resolve(token)).isNull()
        assertThat(sessionRepository.findById(SessionService.hash(token))).isEmpty()
    }

    @Test
    fun `closing an unknown session is not an error`() {
        // Logging out twice, or from a browser whose row has already been swept, is not a failure.
        sessions.close(SessionService.newToken())
    }

    @Test
    fun `an expired row resolves to nobody even though it is still there`() {
        val hunterId = newHunter()
        val token = SessionService.newToken()
        sessionRepository.saveAndFlush(
            HunterSession(
                tokenHash = SessionService.hash(token),
                hunterId = hunterId,
                expiresAt = Instant.now().minusSeconds(1),
            ),
        )

        assertThat(sessions.resolve(token)).isNull()
        // Resolving is a read-only path on purpose, so the dead row survives until the sweep.
        assertThat(sessionRepository.findById(SessionService.hash(token))).isPresent()
    }

    // The sweep itself is in ExpiredSessionSweeperTest, which has to commit -- see the note there.

    /** Registering through the service, so the hunter gets a credit row like any real one. */
    private fun newHunter(): UUID =
        hunters.register(
            RegisterHunterRequest(
                "session-test-${UUID.randomUUID()}@example.com",
                "+420123456789",
                "correct-horse",
            ),
        ).id!!
}
