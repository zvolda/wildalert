package com.wildalert.useraccount.credit

import com.wildalert.useraccount.hunter.HunterRepository
import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.RegisterHunterRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The race the guarded update exists to prevent: several replicas charging *different* events for
 * the same hunter at the same moment, with only enough credit for one of them.
 *
 * **Deliberately not `@Transactional`.** Each thread needs its own committed transaction, which
 * is the whole point — a rolled-back test transaction would make every thread see the same
 * uncommitted state and prove nothing. So this test commits, and cleans up after itself in
 * [cleanUp].
 *
 * **Needs a real Postgres.** H2 will not reproduce the row-level locking this depends on.
 */
@SpringBootTest
class CreditChargeConcurrencyTest {

    @Autowired private lateinit var hunters: HunterService
    @Autowired private lateinit var hunterRepository: HunterRepository
    @Autowired private lateinit var charges: CreditChargeService
    @Autowired private lateinit var credits: HunterCreditRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val created = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        // This test commits, so it has to tidy up or it leaves rows in the dev database.
        // Foreign-key order: ledger, then credit, then the hunter.
        created.forEach { id ->
            jdbc.update("delete from credit_ledger where hunter_id = ?", id)
            jdbc.update("delete from hunter_credit where hunter_id = ?", id)
            jdbc.update("delete from hunter where id = ?", id)
        }
        created.clear()
    }

    @Test
    fun `with credit for exactly one alert, exactly one of eight racing charges succeeds`() {
        val threads = 8
        val cost = 250L
        val hunterId = hunterWithBalance(cost)

        val hunter = hunterRepository.findById(hunterId).orElseThrow()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        // Distinct event ids, so idempotency is not what is being tested here -- the balance
        // guard is. All eight are legitimate, different photos.
        val futures = (1..threads).map {
            pool.submit<ChargeOutcome> {
                start.await()
                charges.charge(
                    hunter,
                    ChargeRequest("race-" + UUID.randomUUID(), cost, EntryType.SMS_DEBIT),
                ).outcome
            }
        }
        start.countDown() // release them together, to actually contend
        pool.shutdown()
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue()

        val outcomes = futures.map { it.get() }

        // The property that matters: the balance held one alert, so exactly one was sold.
        assertThat(outcomes.count { it == ChargeOutcome.CHARGED }).isEqualTo(1)
        assertThat(outcomes.count { it == ChargeOutcome.INSUFFICIENT }).isEqualTo(threads - 1)

        // Never negative -- and the check constraint would have failed the transaction if the
        // guard had let it through, so this also proves the guard did the work rather than the
        // constraint catching it afterwards.
        assertThat(credits.balanceMinorOf(hunterId)).isEqualTo(0)

        // One debit, seven refusals that left no trace.
        val debits = jdbc.queryForObject(
            "select count(*) from credit_ledger where hunter_id = ? and entry_type = 'SMS_DEBIT'",
            Int::class.java,
            hunterId,
        )
        assertThat(debits).isEqualTo(1)
    }

    @Test
    fun `the same event charged by eight replicas at once debits once`() {
        val threads = 8
        val cost = 250L
        val hunterId = hunterWithBalance(2500)
        val hunter = hunterRepository.findById(hunterId).orElseThrow()

        // One event id shared by every thread: the redelivery storm, where the fast-path check
        // cannot help because none of them has committed yet. The partial unique index is what
        // has to hold, and a loser's whole transaction rolls back -- debit included.
        val sharedEventId = "race-shared-" + UUID.randomUUID()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        val futures = (1..threads).map {
            pool.submit<ChargeOutcome?> {
                start.await()
                runCatching {
                    charges.charge(
                        hunter,
                        ChargeRequest(sharedEventId, cost, EntryType.SMS_DEBIT),
                    ).outcome
                }.getOrNull() // a loser's constraint violation surfaces as an exception
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue()

        val outcomes = futures.map { it.get() }

        // Whatever each caller saw, the money moved once and once only.
        val debits = jdbc.queryForObject(
            "select count(*) from credit_ledger where hunter_id = ? and external_ref = ?",
            Int::class.java,
            hunterId,
            sharedEventId,
        )
        assertThat(debits).isEqualTo(1)
        assertThat(credits.balanceMinorOf(hunterId)).isEqualTo(2500 - cost)

        // At least one caller got a usable answer; the rest either saw ALREADY_CHARGED or had
        // their transaction rolled back, and a redelivery of those will see ALREADY_CHARGED.
        assertThat(outcomes.filterNotNull()).isNotEmpty()
        assertThat(outcomes.count { it == ChargeOutcome.CHARGED }).isEqualTo(1)
    }

    /** A committed hunter whose balance is exactly [balanceMinor]. */
    private fun hunterWithBalance(balanceMinor: Long): UUID {
        val hunter = hunters.register(
            RegisterHunterRequest("race-test-${UUID.randomUUID()}@example.com", "+420123456789"),
        )
        val id = hunter.id!!
        created += id
        jdbc.update("update hunter_credit set balance_minor = ? where hunter_id = ?", balanceMinor, id)
        return id
    }
}
