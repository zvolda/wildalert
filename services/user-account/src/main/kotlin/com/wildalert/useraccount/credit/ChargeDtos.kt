package com.wildalert.useraccount.credit

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/** What happened to a charge attempt. */
enum class ChargeOutcome {
    /** The balance was debited by this call. */
    CHARGED,

    /**
     * This cause was already charged, by an earlier call. **A success, not an error** — it is the
     * normal answer to a redelivered event, and the caller should carry on and send the SMS. The
     * charge means "this event is paid for", which stays true across every redelivery.
     */
    ALREADY_CHARGED,

    /** Not enough credit. Nothing was written: no ledger row, no balance change. */
    INSUFFICIENT,

    /** The hunter exists but is switched off. Nothing was written and nothing should be sent. */
    INACTIVE,
}

/** Something the hunter should be told, at most once per depletion cycle. */
enum class Notice {
    NONE,

    /** The balance has fallen near zero. Appended to the alert being sent anyway. */
    LOW_BALANCE,

    /**
     * The balance has just reached zero. Worth its own SMS, which **we** pay for — there is
     * nothing left to charge, and for a product whose entire output is an SMS, silence is
     * indistinguishable from a bug.
     */
    OUT_OF_CREDIT,
}

/**
 * Body for the charge endpoints.
 *
 * The amount comes from the caller because `notification` and `recognition` are the services that
 * know what they cost, and those prices differ and will change. user-account charges what it is
 * told — but it is **input, not truth**, which is why `credit_ledger_amount_sane` bounds it in the
 * database as well as [Min] here.
 */
data class ChargeRequest(
    /**
     * The event this charge is for, used as the idempotency key. Must be the event's
     * `sourceEventId` — the id that survives Pub/Sub redelivery — and never `eventId`, which does
     * not, and would let one photo be charged repeatedly.
     */
    @field:NotBlank
    val sourceEventId: String,

    @field:Min(1)
    val amountMinor: Long,

    /**
     * What is being paid for. Part of the idempotency key, so one event can charge a
     * `RECOGNITION_DEBIT` and later an `SMS_DEBIT` without the second looking like a duplicate.
     */
    val entryType: EntryType,

    /**
     * Whether the caller can actually get a [Notice] to the hunter. **False means: charge, but do
     * not claim the warning.**
     *
     * A notice is issued at most once per depletion cycle (the `warning_level` claim), so handing
     * one to a caller that cannot deliver it does not delay the warning — it *loses* it. That is
     * exactly what would happen for every photo: `recognition` charges first, so it is the charge
     * that crosses the low-balance threshold, and recognition sends no SMS. It therefore asks for
     * no notice and the following `SMS_DEBIT` claims the warning, which is the charge that has an
     * alert to append it to.
     *
     * Defaults to true, which is right for `notification` (it is sending an SMS anyway) and keeps
     * the field optional on the wire.
     */
    val canDeliverNotice: Boolean = true,
)

/**
 * The answer: enough to decide whether to send, who to send to, and what to add about credit —
 * in one round trip, so the caller needs no second lookup.
 *
 * **Carries a phone number, so the endpoints returning it must never be exposed through the
 * gateway.** See `ApiRoutes.kt`.
 */
data class ChargeResponse(
    val hunterId: UUID,
    val phone: String,
    val active: Boolean,
    val outcome: ChargeOutcome,
    /** The balance after this call, whatever the outcome. */
    val balanceAfterMinor: Long,
    val notice: Notice,
)
