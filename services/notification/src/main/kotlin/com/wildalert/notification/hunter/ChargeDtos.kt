package com.wildalert.notification.hunter

import java.util.UUID

/**
 * What happened to a charge attempt. Mirrors user-account's `ChargeOutcome`.
 *
 * These types are a **deliberate copy** of `com.wildalert.useraccount.credit.ChargeDtos` rather
 * than a shared module — the same call this project already makes for the event contracts and for
 * `UserAccountAuth`. If a third service charges, the copy becomes a module; until then the
 * duplication is cheaper than the coupling.
 *
 * **Deserialised strictly**: an outcome we do not know is a semantic change this service has to be
 * taught about, and guessing is the one thing we must not do — guess `CHARGED` and we send an SMS
 * nobody paid for, guess `INSUFFICIENT` and we swallow an alert that was paid for. An unknown value
 * therefore fails the push, which Pub/Sub redelivers and eventually dead-letters: loud, and in the
 * right place. Contrast [Notice], which is deliberately lenient.
 */
enum class ChargeOutcome {
    /** The balance was debited by this call. Send the alert. */
    CHARGED,

    /**
     * An earlier call already charged this event. **A success**, and the ordinary answer to a
     * redelivery — the charge means "this event is paid for", which stays true however many times
     * the event arrives. Send the alert.
     */
    ALREADY_CHARGED,

    /** Not enough credit. Nothing was written and nothing is sent. */
    INSUFFICIENT,

    /** The hunter is switched off. Nothing was written and nothing is sent. */
    INACTIVE,
}

/**
 * Something to tell the hunter about their balance, at most once per depletion cycle —
 * user-account decides *whether* to say it (the `warning_level` claim), we decide how it reads.
 *
 * **Deserialised leniently**: an unknown notice maps to [NONE]. A new notice added on
 * user-account's side is an additive change that should never cost an alert, and a strict enum here
 * would turn one into every alert dead-lettering until this service caught up. The asymmetry with
 * [ChargeOutcome] is the point: not knowing what to *add* to a message is survivable, not knowing
 * whether we may *send* it is not.
 */
enum class Notice {
    NONE,

    /** Near zero. Rides along on the alert that is going out anyway. */
    LOW_BALANCE,

    /** Just reached zero on this very charge. Also rides along — see `SmsPolicy`. */
    OUT_OF_CREDIT,

    ;

    companion object {

        /** Maps a wire value, treating anything unrecognised (or absent) as [NONE]. */
        fun ofWireValue(value: String?): Notice? =
            if (value == null) NONE else entries.firstOrNull { it.name == value }
    }
}

/**
 * Body of the charge call.
 *
 * The price travels from here because this service is the one that knows what an SMS costs, and
 * user-account charges what it is told. [entryType] is a string rather than a copy of
 * user-account's whole `EntryType` enum because notification only ever charges one kind of thing;
 * `recognition` will send its own.
 */
data class ChargeRequest(
    /**
     * The event's `sourceEventId`, which is the idempotency key. **Never `eventId`** — that is
     * fresh on every republish, so using it would charge one photo again and again.
     */
    val sourceEventId: String,
    val amountMinor: Long,
    val entryType: String = SMS_DEBIT,
) {
    companion object {
        /** The only entry type this service charges: an alert that was sent. */
        const val SMS_DEBIT = "SMS_DEBIT"
    }
}

/**
 * The wire shape of the answer. [notice] is a raw string so that an unrecognised value can be
 * mapped rather than thrown — see [Notice].
 */
internal data class ChargeResponse(
    val hunterId: UUID,
    val phone: String,
    val active: Boolean,
    val outcome: ChargeOutcome,
    val balanceAfterMinor: Long,
    val notice: String?,
)

/**
 * A hunter, resolved and charged, as the rest of this service sees them. Replaces the old
 * `HunterContact`: there is no longer a point in the flow where we know who the hunter is but not
 * whether they have paid for this alert, because one call answers both.
 */
data class HunterCharge(
    val id: UUID,
    val phone: String,
    val active: Boolean,
    val outcome: ChargeOutcome,
    /** The balance after this call, whatever the outcome. In haléře. */
    val balanceAfterMinor: Long,
    val notice: Notice,
) {

    /**
     * Whether this alert may go out. Both charged outcomes say yes; both refusals say no.
     * Expressed here rather than at the call site so the two-success-values rule lives in one
     * place — reading `ALREADY_CHARGED` as a failure is the easiest mistake to make against this
     * contract, and it would silence every redelivered alert.
     */
    val payable: Boolean
        get() = outcome == ChargeOutcome.CHARGED || outcome == ChargeOutcome.ALREADY_CHARGED
}
