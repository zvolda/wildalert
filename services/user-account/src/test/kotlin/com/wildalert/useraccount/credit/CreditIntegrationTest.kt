package com.wildalert.useraccount.credit

import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.RegisterHunterRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The parts of credit that only a real database can answer: the check constraints, the partial
 * unique index, and that the cached balance agrees with the ledger. Mocks cannot test a
 * constraint.
 *
 * **Needs Postgres** (`docker compose up -d postgres`), like `contextLoads` — see AGENTS.md.
 * `@Transactional` rolls each test back, so the shared dev database is left as it was found.
 *
 * Everything flushes through a **repository** (`saveAndFlush`, `flush`) rather than a raw
 * `EntityManager`. That is not a style preference: Spring's exception translation wraps
 * repository beans, so a constraint violation surfaces as `DataIntegrityViolationException`
 * there, while `EntityManager.flush()` throws an untranslated JPA `PersistenceException`.
 */
// The grant is pinned rather than inherited: its default is 0 in production (a public
// registration form must not hand out credit), and these tests assert on the balance it
// produces, so they state the value they need instead of tracking a production default.
@SpringBootTest(properties = ["billing.signup-grant-minor=2500"])
@Transactional
class CreditIntegrationTest {

    @Autowired private lateinit var hunters: HunterService
    @Autowired private lateinit var credits: HunterCreditRepository
    @Autowired private lateinit var ledger: CreditLedgerRepository

    @Test
    fun `registering opens an account with the signup grant and exactly one ledger entry`() {
        val id = registerHunter()

        val credit = credits.findById(id).orElseThrow()
        assertThat(credit.balanceMinor).isEqualTo(2500)
        assertThat(credit.currency).isEqualTo("CZK")
        assertThat(credit.warningLevel).isEqualTo(0.toShort())

        val entries = ledger.findByHunterIdOrderByCreatedAtDesc(id)
        assertThat(entries).hasSize(1)
        val grant = entries.single()
        assertThat(grant.entryType).isEqualTo(EntryType.SIGNUP_GRANT)
        assertThat(grant.amountMinor).isEqualTo(2500)
        assertThat(grant.balanceAfterMinor).isEqualTo(2500)
        // A grant has no external cause, which is why the unique index has to be partial.
        assertThat(grant.externalRef).isNull()
    }

    @Test
    fun `the cached balance equals the sum of the ledger`() {
        val id = registerHunter()

        // The invariant the whole design rests on: balance_minor is a cache of this sum, and a
        // reconciliation job will later assert exactly this across every hunter.
        assertThat(credits.findById(id).orElseThrow().balanceMinor)
            .isEqualTo(ledger.sumAmountMinor(id))
    }

    @Test
    fun `the database refuses a negative balance`() {
        val id = registerHunter()
        val credit = credits.findById(id).orElseThrow()
        credit.balanceMinor = -1

        // hunter_credit_not_negative. Debits will be guarded in SQL, but the constraint is what
        // turns any future code that forgets the guard into a failed transaction rather than
        // silent debt.
        assertThatThrownBy { credits.saveAndFlush(credit) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the database refuses an implausible amount`() {
        val id = registerHunter()

        // credit_ledger_amount_sane: a debit amount will arrive over HTTP from another service,
        // so it is input, not truth. 10 000 001 haléře is over 100 000 CZK and can only be a bug.
        val absurd = CreditLedgerEntry(
            hunterId = id,
            amountMinor = 10_000_001,
            entryType = EntryType.TOPUP,
            externalRef = "pi_absurd",
            balanceAfterMinor = 10_000_001,
        )

        assertThatThrownBy { ledger.saveAndFlush(absurd) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the same cause cannot be charged twice`() {
        val id = registerHunter()
        val ref = "evt-" + UUID.randomUUID()
        ledger.saveAndFlush(debit(id, ref))

        // credit_ledger_external_ref_uk. This is the whole idempotency mechanism: a redelivered
        // event, a retried Stripe webhook, or two replicas racing all collapse to one row.
        assertThatThrownBy { ledger.saveAndFlush(debit(id, ref)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the same reference may be reused by a different entry type`() {
        val id = registerHunter()
        val ref = "evt-" + UUID.randomUUID()

        // The index is on (entry_type, external_ref), which matters once one event charges both
        // a RECOGNITION_DEBIT and an SMS_DEBIT under the same sourceEventId.
        ledger.saveAndFlush(debit(id, ref, EntryType.RECOGNITION_DEBIT, -100))
        ledger.saveAndFlush(debit(id, ref, EntryType.SMS_DEBIT, -150))

        assertThat(ledger.findByHunterIdOrderByCreatedAtDesc(id)).hasSize(3) // grant + two debits
    }

    @Test
    fun `two grants coexist because the uniqueness index is partial`() {
        val a = registerHunter()
        val b = registerHunter()

        // Both SIGNUP_GRANT with a null external_ref. A non-partial unique index would have made
        // the second registration fail.
        assertThat(ledger.findByHunterIdOrderByCreatedAtDesc(a)).hasSize(1)
        assertThat(ledger.findByHunterIdOrderByCreatedAtDesc(b)).hasSize(1)
    }

    /** Registers a hunter and flushes, so the rows are really in the database. */
    private fun registerHunter(): UUID {
        // The email is unique-constrained and this runs against a shared dev database.
        val hunter = hunters.register(
            RegisterHunterRequest("credit-test-${UUID.randomUUID()}@example.com", "+420123456789", "correct-horse"),
        )
        credits.flush()
        return hunter.id!!
    }

    private fun debit(
        hunterId: UUID,
        ref: String,
        type: EntryType = EntryType.SMS_DEBIT,
        amount: Long = -150,
    ) = CreditLedgerEntry(
        hunterId = hunterId,
        amountMinor = amount,
        entryType = type,
        externalRef = ref,
        balanceAfterMinor = 2500 + amount,
    )
}
