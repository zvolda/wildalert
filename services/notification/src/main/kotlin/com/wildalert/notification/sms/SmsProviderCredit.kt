package com.wildalert.notification.sms

/**
 * **Our** credit at the SMS gateway — the prepaid balance WildAlert pays from, not the hunter's.
 *
 * The two are easy to confuse and they protect opposite things. The hunter's balance
 * (`HunterCredit` in user-account) stops *one hunter* spending more than they bought. This one
 * stops *the product* going silent: GoSMS is prepaid, and once it is empty every alert fails —
 * including the ones hunters have already paid for. A product whose only output is an SMS then
 * looks healthy in every dashboard while delivering nothing, which is the worst shape an outage
 * can take.
 *
 * Only a provider with a balance we can read implements this. The fake logging sender and Twilio
 * do not, so the bean is simply absent for them and [SmsProviderCreditGuard] guards nothing.
 */
interface SmsProviderCredit {

    /**
     * Reads the balance now. Transport failures are **thrown**, not swallowed — the guard decides
     * what a failed read means, and it is the only caller.
     */
    fun remaining(): ProviderCredit
}

/** What a balance read found. */
sealed interface ProviderCredit {

    /** A real prepaid balance, in CZK haléře — the same minor units the hunter's credit uses. */
    data class Known(val remainingMinor: Long, val currency: String) : ProviderCredit

    /**
     * There is no balance to run out of: the account is invoiced after the fact (GoSMS calls this
     * `Postpaid`), so a message is never refused for want of credit. Nothing to guard.
     */
    data class Unmetered(val invoicingType: String) : ProviderCredit

    /**
     * The balance could not be established — the read failed, or the response did not state it in
     * units we can compare with our prices. Never blocks an alert; see [SmsProviderCreditGuard].
     */
    data class Unknown(val reason: String) : ProviderCredit
}
