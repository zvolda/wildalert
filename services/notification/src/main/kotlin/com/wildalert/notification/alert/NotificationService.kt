package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.ChargeRequest
import com.wildalert.notification.hunter.HunterCharge
import com.wildalert.notification.hunter.HunterClient
import com.wildalert.notification.idempotency.ProcessedEvents
import com.wildalert.notification.sms.SmsSender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Turns a recognition result into an SMS: resolve-and-charge the hunter, apply the SMS policy,
 * send. Independent of the transport — the push controller just calls [notify] for each event.
 *
 * This is where the hunter's credit is actually spent. Before this, the ledger and the charge
 * endpoint existed but nothing called them, so a balance of 0 and a balance of 2500 behaved
 * identically end to end.
 */
@Service
class NotificationService(
    private val hunterClient: HunterClient,
    private val smsSender: SmsSender,
    private val processedEvents: ProcessedEvents,
    /** What one alert costs the hunter, in haléře. */
    @Value("\${billing.sms-cost-minor}") private val smsCostMinor: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notify(event: AnimalRecognized) {
        val charge = chargeFor(event)
        if (charge == null) {
            log.info("No SMS for event {}: nobody to text", event.eventId)
            return
        }
        if (!charge.payable) {
            // A refusal, not a failure: user-account wrote nothing, so there is nothing to undo
            // and nothing to retry. Answering the push 200 is right — redelivering would only
            // produce the same refusal.
            log.info(
                "No SMS for event {}: hunter {} was not charged ({}), balance {} haléře",
                event.eventId, charge.id, charge.outcome, charge.balanceAfterMinor,
            )
            return
        }
        val message = SmsPolicy.compose(event, charge)
        if (message == null) {
            log.info("No SMS for event {}: hunter {} is inactive", event.eventId, charge.id)
            return
        }
        // Claim before sending: two replicas can't both text, and a redelivery of an event we
        // already sent finds the claim and stops here.
        //
        // **The claim deliberately comes after the charge, and the money is safe anyway.** A claim
        // can only exist because an earlier attempt got past the charge for this same
        // sourceEventId — so this call would have answered ALREADY_CHARGED, having moved nothing.
        // The reverse order is what would be unsafe: claiming first means a charge that throws
        // (user-account cold, 403, read timeout) has eaten the event, and the redelivery that
        // would have rescued the alert finds it already handled and stays silent.
        if (!processedEvents.claim(event.sourceEventId)) {
            log.info(
                "No SMS for event {}: source event {} was already handled (duplicate delivery)",
                event.eventId, event.sourceEventId,
            )
            return
        }
        val result = try {
            smsSender.send(message)
        } catch (ex: Exception) {
            // Sending failed, so the claim would block the retry from ever sending. Give it back.
            // The charge is *not* given back: it stays, the retry reads ALREADY_CHARGED and sends.
            // One alert, charged once, however many attempts it took.
            processedEvents.release(event.sourceEventId)
            throw ex
        }
        log.info(
            "Sent SMS for event {} (source {}) to hunter {}: messageId={}, charged={}, balance={} haléře",
            event.eventId, event.sourceEventId, charge.id, result.messageId,
            charge.outcome, charge.balanceAfterMinor,
        )
    }

    /**
     * Resolves who the alert is for and charges them for it, in one call. The recipient address is
     * the live path; [AnimalRecognized.hunterId] is the fallback for events published before the
     * lookup moved here, including anything replayed from a dead-letter topic.
     *
     * A failure is **not** caught: it propagates, the push endpoint answers 5xx and Pub/Sub
     * redelivers with backoff. That is the whole point of resolving here rather than in the
     * webhook — user-account scaling to zero costs a retry, not the alert. A call that fails has
     * taken no money, because on the far side the resolve and the debit are one transaction.
     */
    private fun chargeFor(event: AnimalRecognized): HunterCharge? {
        // Keyed on sourceEventId, never eventId: the source id survives redelivery and a republish,
        // so one photo is charged once no matter how many times its result is delivered.
        val request = ChargeRequest(
            sourceEventId = event.sourceEventId.toString(),
            amountMinor = smsCostMinor,
        )

        val recipient = event.recipient
        if (recipient != null) {
            val charge = hunterClient.chargeByInboundAddress(recipient, request)
            if (charge == null) {
                log.info("Event {}: {} is not a hunter's address", event.eventId, recipient)
            }
            return charge
        }
        val legacyId = event.hunterId
        if (legacyId != null) {
            log.info("Event {} carries no recipient; falling back to hunter id {}", event.eventId, legacyId)
            return hunterClient.chargeById(legacyId, request)
        }
        return null
    }
}
