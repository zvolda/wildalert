package com.wildalert.useraccount.credit

import com.wildalert.useraccount.hunter.Hunter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Takes money off a hunter's balance for one event, exactly once, in one transaction.
 *
 * This is the piece the whole feature rests on, so the ordering below is deliberate and the
 * comments say why. Both consumers call it: `recognition` charges a `RECOGNITION_DEBIT` per photo
 * *before* it runs the model, and `notification` an `SMS_DEBIT` for every alert it sends. The two
 * land under the same `external_ref` (the photo's event id) and different entry types, which is
 * what the partial unique index on `(entry_type, external_ref)` is for.
 *
 * **Three properties it has to have:**
 *
 * 1. *A redelivered event must not be charged twice.* Pub/Sub is at-least-once, so the same photo
 *    will arrive again. The `(entry_type, external_ref)` unique index makes a second charge for
 *    the same cause physically impossible, whatever any caller does.
 * 2. *Two replicas charging different events for the same hunter must not overdraw.* The guarded
 *    `update … where balance_minor >= :cost` does the arithmetic and the check in one statement,
 *    under the row lock the update itself takes. There is no read-then-write in Kotlin, so there
 *    is no window to lose.
 * 3. *A refusal must leave no trace.* `INSUFFICIENT` and `INACTIVE` write nothing at all — not a
 *    ledger row, not a balance change — so there is nothing to roll back and nothing to explain
 *    in a statement. A refusal is logged, not recorded.
 */
@Service
class CreditChargeService(
    private val credits: HunterCreditRepository,
    private val ledger: CreditLedgerRepository,
    /** Below this, an alert carries a low-balance warning. In haléře. */
    @Value("\${billing.low-balance-threshold-minor}") private val lowBalanceThresholdMinor: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun charge(hunter: Hunter, request: ChargeRequest): ChargeResponse {
        // Read what we need off the entity up front. Everything after this point talks to the
        // database in native SQL, so the entity's state is not consulted again.
        val hunterId = hunter.id!!
        val phone = hunter.phone
        val active = hunter.active

        // An inactive hunter is not charged and is not texted. This check lives inside the charge
        // transaction rather than in the caller because this is the only place it can be made
        // without charging first and deciding afterwards.
        if (!active) {
            log.debug("No charge for hunter {}: inactive", hunterId)
            return answer(hunterId, phone, active, ChargeOutcome.INACTIVE, balanceOf(hunterId))
        }

        // Fast path for the ordinary redelivery: answer without touching the balance at all.
        // Advisory only — see below for what actually guarantees it.
        val existing = ledger.findByEntryTypeAndExternalRef(request.entryType, request.sourceEventId)
        if (existing != null) {
            log.debug(
                "Event {} already charged {} haléře as {}",
                request.sourceEventId, -existing.amountMinor, request.entryType,
            )
            return answer(
                hunterId, phone, active, ChargeOutcome.ALREADY_CHARGED, balanceOf(hunterId),
            )
        }

        if (credits.debitIfSufficient(hunterId, request.amountMinor) == 0) {
            val balance = balanceOf(hunterId)
            log.info(
                "Refused a charge of {} haléře for hunter {}: balance is {}",
                request.amountMinor, hunterId, balance,
            )
            // Nothing was written, so there is nothing to undo. The notice for running dry was
            // already sent by the charge that *reached* zero, which is why this is NONE.
            return answer(hunterId, phone, active, ChargeOutcome.INSUFFICIENT, balance)
        }

        // Our own update is visible to this transaction, so this is the balance we just produced.
        val balanceAfter = balanceOf(hunterId)

        // The ledger row goes in after the debit because its `balance_after_minor` is only known
        // now. If a racing replica charged the same event between the fast-path check and here,
        // this insert violates the unique index, the whole transaction rolls back — debit
        // included — and the retry takes the ALREADY_CHARGED path. **That index, not the check
        // above, is what makes double-charging impossible.**
        ledger.save(
            CreditLedgerEntry(
                hunterId = hunterId,
                amountMinor = -request.amountMinor,
                entryType = request.entryType,
                externalRef = request.sourceEventId,
                balanceAfterMinor = balanceAfter,
            ),
        )

        // Only a caller that can deliver the warning is allowed to claim it. A claim by anyone
        // else discards it: recognition charges before notification does, so it is recognition's
        // debit that crosses the threshold, and recognition has no SMS to put the warning on.
        val notice =
            if (request.canDeliverNotice) noticeFor(hunterId, balanceAfter) else Notice.NONE

        return answer(hunterId, phone, active, ChargeOutcome.CHARGED, balanceAfter, notice)
    }

    /**
     * Decides what the hunter should be told, and claims the right to tell them.
     *
     * The claim is the `warning_level` move: only the transaction that actually raises the level
     * gets a notice back, so thirty photos arriving on an empty balance produce one message
     * rather than thirty. Any top-up resets the level, so a hunter who runs dry twice is told
     * twice.
     */
    private fun noticeFor(hunterId: UUID, balanceAfter: Long): Notice = when {
        balanceAfter <= 0 ->
            if (credits.raiseWarningLevel(hunterId, OUT_OF_CREDIT_LEVEL) == 1) {
                log.info("Hunter {} has run out of credit", hunterId)
                Notice.OUT_OF_CREDIT
            } else {
                Notice.NONE
            }

        balanceAfter <= lowBalanceThresholdMinor ->
            if (credits.raiseWarningLevel(hunterId, LOW_BALANCE_LEVEL) == 1) {
                log.info("Hunter {} is low on credit: {} haléře", hunterId, balanceAfter)
                Notice.LOW_BALANCE
            } else {
                Notice.NONE
            }

        else -> Notice.NONE
    }

    /**
     * A native read on purpose: it bypasses JPA's first-level cache, which the native debit above
     * does not update and which would otherwise hand back the pre-charge balance.
     *
     * A hunter with no credit row reports 0 rather than throwing. That should be impossible —
     * `CreditService.openAccount` runs inside registration — and if it ever happens, reporting no
     * credit is the safe failure: it refuses to spend money we cannot account for.
     */
    private fun balanceOf(hunterId: UUID): Long = credits.balanceMinorOf(hunterId) ?: 0

    private fun answer(
        hunterId: UUID,
        phone: String,
        active: Boolean,
        outcome: ChargeOutcome,
        balanceAfterMinor: Long,
        notice: Notice = Notice.NONE,
    ) = ChargeResponse(hunterId, phone, active, outcome, balanceAfterMinor, notice)

    private companion object {
        const val LOW_BALANCE_LEVEL = 1
        const val OUT_OF_CREDIT_LEVEL = 2
    }
}
