package com.wildalert.useraccount.credit

/**
 * Why credit moved. Stored as its name, and mirrored by the `credit_ledger_type_known` check
 * constraint in V4 — add a value here and the migration has to grow one too, or the insert fails.
 *
 * Kept distinguishable rather than collapsed into "credit" and "debit" because the distinctions
 * matter outside the code: a grant is never refundable and is not a sale for VAT, and a hunter
 * looking at a falling balance needs to see *what* they paid for (a recognition that alerted
 * nobody still costs money).
 */
enum class EntryType {
    /** The free credit a hunter is given on registration. Not a sale. */
    SIGNUP_GRANT,

    /** Money the hunter paid us. `external_ref` is the Stripe payment-intent id. */
    TOPUP,

    /** An alert that was sent. `external_ref` is the event's `sourceEventId`. */
    SMS_DEBIT,

    /** A photo that was classified, whether or not it alerted. `external_ref` as above. */
    RECOGNITION_DEBIT,

    /** A correction made by hand, because something leaked. No `external_ref`. */
    ADJUSTMENT,

    /** A refund or chargeback. */
    REVERSAL,
}
