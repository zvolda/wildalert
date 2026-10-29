package com.wildalert.useraccount.credit

import com.wildalert.useraccount.hunter.HunterRepository
import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.RegisterHunterRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The charge transaction, against a real Postgres — the guard, the idempotency and the notice
 * transitions are all database behaviour, so none of it can be tested with mocks.
 *
 * **Needs Postgres** (`docker compose up -d postgres`). `@Transactional` rolls each test back.
 * The racing-replicas case cannot live here, because threads need committed state — see
 * `CreditChargeConcurrencyTest`.
 */
// The grant is pinned rather than inherited: its default is 0 in production (a public
// registration form must not hand out credit), and these tests assert on the balance it
// produces, so they state the value they need instead of tracking a production default.
@SpringBootTest(properties = ["billing.signup-grant-minor=2500"])
@Transactional
class CreditChargeServiceTest {

    @Autowired private lateinit var hunters: HunterService
    @Autowired private lateinit var hunterRepository: HunterRepository
    @Autowired private lateinit var charges: CreditChargeService
    @Autowired private lateinit var credits: HunterCreditRepository
    @Autowired private lateinit var ledger: CreditLedgerRepository

    @Test
    fun `a charge debits the balance and records one ledger entry`() {
        val hunter = register()

        val result = charges.charge(hunter, request(amountMinor = 250))

        assertThat(result.outcome).isEqualTo(ChargeOutcome.CHARGED)
        assertThat(result.balanceAfterMinor).isEqualTo(2250)
        assertThat(result.phone).isEqualTo("+420123456789")
        assertThat(result.notice).isEqualTo(Notice.NONE)

        val debits = ledger.findByHunterIdOrderByCreatedAtDesc(hunter.id!!)
            .filter { it.entryType == EntryType.SMS_DEBIT }
        assertThat(debits).hasSize(1)
        // Stored negative: positive adds credit, negative spends it.
        assertThat(debits.single().amountMinor).isEqualTo(-250)
        assertThat(debits.single().balanceAfterMinor).isEqualTo(2250)
    }

    @Test
    fun `charging the same event twice debits once`() {
        val hunter = register()
        val req = request(amountMinor = 250)

        val first = charges.charge(hunter, req)
        val second = charges.charge(hunter, req)

        // The double-charge test. Pub/Sub is at-least-once, so this is the ordinary case, not an
        // edge case -- and ALREADY_CHARGED is a success: the caller should still send the SMS.
        assertThat(first.outcome).isEqualTo(ChargeOutcome.CHARGED)
        assertThat(second.outcome).isEqualTo(ChargeOutcome.ALREADY_CHARGED)
        assertThat(second.balanceAfterMinor).isEqualTo(first.balanceAfterMinor)
        assertThat(ledger.findByHunterIdOrderByCreatedAtDesc(hunter.id!!))
            .filteredOn { it.externalRef == req.sourceEventId }
            .hasSize(1)
    }

    @Test
    fun `one event can pay for a recognition and then an alert`() {
        val hunter = register()
        val eventId = "evt-" + UUID.randomUUID()

        val recognition = charges.charge(
            hunter,
            ChargeRequest(eventId, 100, EntryType.RECOGNITION_DEBIT),
        )
        val alert = charges.charge(hunter, ChargeRequest(eventId, 150, EntryType.SMS_DEBIT))

        // The idempotency key is (entry_type, external_ref), not the event id alone -- which is
        // what lets the two-part price work without the second charge looking like a duplicate.
        assertThat(recognition.outcome).isEqualTo(ChargeOutcome.CHARGED)
        assertThat(alert.outcome).isEqualTo(ChargeOutcome.CHARGED)
        assertThat(alert.balanceAfterMinor).isEqualTo(2250)
    }

    @Test
    fun `a charge larger than the balance changes nothing at all`() {
        val hunter = register()
        val before = credits.balanceMinorOf(hunter.id!!)
        val entriesBefore = ledger.findByHunterIdOrderByCreatedAtDesc(hunter.id!!).size

        val result = charges.charge(hunter, request(amountMinor = 9_999))

        assertThat(result.outcome).isEqualTo(ChargeOutcome.INSUFFICIENT)
        // A refusal is not a ledger event. Nothing was written, so there is nothing to roll back
        // and nothing to explain in a statement.
        assertThat(credits.balanceMinorOf(hunter.id!!)).isEqualTo(before)
        assertThat(ledger.findByHunterIdOrderByCreatedAtDesc(hunter.id!!)).hasSize(entriesBefore)
    }

    @Test
    fun `spending the last of the balance reports OUT_OF_CREDIT once`() {
        val hunter = register()

        // Exactly to zero: the boundary, where `>=` matters rather than `>`.
        val toZero = charges.charge(hunter, request(amountMinor = 2500))
        assertThat(toZero.outcome).isEqualTo(ChargeOutcome.CHARGED)
        assertThat(toZero.balanceAfterMinor).isEqualTo(0)
        assertThat(toZero.notice).isEqualTo(Notice.OUT_OF_CREDIT)

        // The next photo is refused, and says nothing: the hunter has already been told. Thirty
        // photos on an empty balance must not produce thirty SMS.
        val refused = charges.charge(hunter, request(amountMinor = 250))
        assertThat(refused.outcome).isEqualTo(ChargeOutcome.INSUFFICIENT)
        assertThat(refused.notice).isEqualTo(Notice.NONE)
    }

    @Test
    fun `a charge that lands near zero reports LOW_BALANCE once`() {
        val hunter = register()

        // Threshold is 500 by default; 2500 - 2100 = 400.
        val low = charges.charge(hunter, request(amountMinor = 2100))
        assertThat(low.outcome).isEqualTo(ChargeOutcome.CHARGED)
        assertThat(low.notice).isEqualTo(Notice.LOW_BALANCE)

        // Still low, but already warned.
        val again = charges.charge(hunter, request(amountMinor = 100))
        assertThat(again.notice).isEqualTo(Notice.NONE)
    }

    @Test
    fun `the warning level only ever rises, and each notice fires once`() {
        val hunter = register()
        val id = hunter.id!!

        charges.charge(hunter, request(amountMinor = 2100)) // -> 400, LOW_BALANCE, level 1
        // Read natively: raiseWarningLevel is a native update, so a cached entity would still
        // report 0 here and the assertion would be testing JPA's cache rather than the database.
        assertThat(credits.warningLevelOf(id)).isEqualTo(1.toShort())

        val empty = charges.charge(hunter, request(amountMinor = 400)) // -> 0, level 2
        assertThat(empty.notice).isEqualTo(Notice.OUT_OF_CREDIT)
        assertThat(credits.warningLevelOf(id)).isEqualTo(2.toShort())

        // Monotonic: a level-1 claim cannot win once the level is 2, so a hunter who has been
        // told they are empty is not then told they are merely low.
        assertThat(credits.raiseWarningLevel(id, 1)).isEqualTo(0)
        assertThat(credits.raiseWarningLevel(id, 2)).isEqualTo(0)
        // The reset on top-up arrives with the Stripe slice; nothing resets it yet.
    }

    @Test
    fun `an inactive hunter is not charged`() {
        val hunter = register()
        hunter.active = false
        hunterRepository.saveAndFlush(hunter)
        val before = credits.balanceMinorOf(hunter.id!!)

        val result = charges.charge(hunter, request(amountMinor = 250))

        assertThat(result.outcome).isEqualTo(ChargeOutcome.INACTIVE)
        assertThat(result.active).isFalse()
        assertThat(credits.balanceMinorOf(hunter.id!!)).isEqualTo(before)
        assertThat(ledger.findByHunterIdOrderByCreatedAtDesc(hunter.id!!))
            .noneMatch { it.entryType == EntryType.SMS_DEBIT }
    }

    private fun register() = hunters.register(
        RegisterHunterRequest("charge-test-${UUID.randomUUID()}@example.com", "+420123456789"),
    ).also { credits.flush() }

    private fun request(amountMinor: Long, type: EntryType = EntryType.SMS_DEBIT) =
        ChargeRequest("evt-" + UUID.randomUUID(), amountMinor, type)
}
