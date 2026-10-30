package com.wildalert.notification.sms

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Stops an alert before it costs anybody anything when **our** gateway credit cannot pay for it,
 * and warns while there is still time to top up.
 *
 * Why this sits in front of the whole handler rather than around the send:
 *
 * - **The hunter must not be charged for an SMS the gateway will refuse.** The charge comes first in
 *   [com.wildalert.notification.alert.NotificationService] and there is no refund path in this
 *   design, so a send that fails on *our* empty balance would take 150 haléře and deliver silence.
 *   Checked here, no money moves at all.
 * - **An empty gateway is retried, not acked** — the opposite of a hunter's `INSUFFICIENT`. That
 *   refusal is final: nothing we do changes it, so slice 4 acks it and keeps the dead-letter topic
 *   clean. This one we can fix by topping up, and the alert is worth keeping until we do, so it
 *   throws: the push answers 5xx, Pub/Sub retries with backoff, and whatever is still unsent lands
 *   in the dead-letter topic where it can be replayed. A filling dead-letter topic is the correct
 *   alarm for "the company credit card needs attention".
 *
 * Two rules keep the guard from becoming a new way to lose alerts:
 *
 * 1. **An unreadable balance never blocks.** A broken or changed credit endpoint is our problem,
 *    not the hunter's, and the send itself is the real test — GoSMS refuses what it cannot bill.
 *    So [ProviderCredit.Unknown] passes, loudly.
 * 2. **A blocking balance is never cached.** Good news is cached for
 *    [SmsProviderCreditProperties.ttl]; bad news is re-read every time, so the first alert after a
 *    top-up goes out immediately instead of waiting out a stale "empty".
 */
@Component
class SmsProviderCreditGuard(
    /** Absent unless the configured provider can report a balance (only GoSMS can today). */
    private val provider: SmsProviderCredit? = null,
    private val props: SmsProviderCreditProperties = SmsProviderCreditProperties(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A price of zero would divide by zero in [messagesLeft] and make [blocks] unreachable, so a
     * misconfigured one is coerced to 1 haléř — which guards nothing, the same direction every
     * other uncertainty here resolves in. Never silently: the init block says so.
     */
    private val messagePriceMinor = props.messagePriceMinor.coerceAtLeast(1)

    @Volatile
    private var cached: Cached? = null

    init {
        if (props.messagePriceMinor <= 0) {
            log.warn(
                "sms.provider-credit.message-price-minor is {}, which cannot guard anything; using 1",
                props.messagePriceMinor,
            )
        }
        if (provider == null) {
            log.info("SMS provider credit is not readable for this provider; nothing to guard")
        } else {
            log.info(
                "Guarding SMS provider credit (a message costs us {} haléře, warning at {}, re-read every {})",
                messagePriceMinor, props.warnMinor, props.ttl,
            )
        }
    }

    /**
     * Throws [SmsProviderOutOfCreditException] when we know the gateway cannot pay for one more
     * message. Returns normally in every other case, including "we could not tell".
     */
    fun requireCreditForOneMessage() {
        when (val credit = current() ?: return) {
            is ProviderCredit.Known -> {
                if (credit.blocks()) {
                    throw SmsProviderOutOfCreditException(credit.remainingMinor, messagePriceMinor)
                }
                if (credit.remainingMinor <= props.warnMinor) {
                    // At most one of these per ttl, because the reads are cached — so a low balance
                    // is a steady drumbeat in the logs rather than a line per alert.
                    log.warn(
                        "SMS provider credit is low: {} haléře left, about {} more messages. Top up.",
                        credit.remainingMinor, messagesLeft(credit),
                    )
                }
            }

            // Logged once per ttl rather than ignored silently: if this is unexpected, the log is
            // where it shows up.
            is ProviderCredit.Unmetered ->
                log.debug("SMS provider credit is unmetered ({}); nothing to guard", credit.invoicingType)

            is ProviderCredit.Unknown ->
                log.warn("SMS provider credit unknown ({}); sending anyway", credit.reason)
        }
    }

    /**
     * A fresh read for an operator (`GET /api/sms/credit`), bypassing the cache — someone asking is
     * asking about now. Null when the provider has no balance to report. The result refreshes the
     * cache too, since a just-read value is strictly better than the stored one.
     */
    fun inspect(): ProviderCredit? {
        val provider = provider ?: return null
        return read(provider)
    }

    /** How many more messages a balance buys, for logs and the operator endpoint. */
    fun messagesLeft(credit: ProviderCredit.Known): Long =
        credit.remainingMinor / messagePriceMinor

    private fun current(): ProviderCredit? {
        cached?.takeIf { it.isFresh() }?.let { return it.credit }
        return inspect()
    }

    private fun read(provider: SmsProviderCredit): ProviderCredit {
        val credit = try {
            provider.remaining().also {
                if (it is ProviderCredit.Known) {
                    log.info(
                        "SMS provider credit: {} haléře ({}), about {} messages",
                        it.remainingMinor, it.currency, messagesLeft(it),
                    )
                }
            }
        } catch (ex: Exception) {
            // Deliberately broad. Every failure mode here — gateway down, a read timeout, an auth
            // problem, a response shape we cannot parse — means the same thing: we do not know, so
            // do not block. Narrowing it would risk an exception type we did not think of escaping
            // and dead-lettering an alert over a *balance check*.
            ProviderCredit.Unknown("read failed: ${ex.javaClass.simpleName}: ${ex.message}")
        }

        // Rule 2: only cache what lets alerts through.
        if (!credit.blocks()) {
            cached = Cached(credit, Instant.now().plus(props.ttl))
        }
        return credit
    }

    private fun ProviderCredit.blocks() =
        this is ProviderCredit.Known && remainingMinor < messagePriceMinor

    private data class Cached(val credit: ProviderCredit, val expiresAt: Instant) {
        fun isFresh() = Instant.now().isBefore(expiresAt)
    }
}

/**
 * Our own gateway balance cannot pay for one more message. Thrown, so the push 5xxs and the alert
 * survives in Pub/Sub (and then the dead-letter topic) until the account is topped up.
 */
class SmsProviderOutOfCreditException(
    val remainingMinor: Long,
    val messagePriceMinor: Long,
) : RuntimeException(
    "The SMS gateway has $remainingMinor haléře left, less than the $messagePriceMinor a message " +
        "costs us. Alerts are being retried, not dropped — top up the gateway account.",
)
