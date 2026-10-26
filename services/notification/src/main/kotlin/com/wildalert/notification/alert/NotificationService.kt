package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.HunterClient
import com.wildalert.notification.hunter.HunterContact
import com.wildalert.notification.idempotency.ProcessedEvents
import com.wildalert.notification.sms.SmsSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Turns a recognition result into an SMS: find the hunter, apply the SMS policy, send.
 * Independent of the transport — the push controller just calls [notify] for each event.
 */
@Service
class NotificationService(
    private val hunterClient: HunterClient,
    private val smsSender: SmsSender,
    private val processedEvents: ProcessedEvents,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notify(event: AnimalRecognized) {
        val hunter = resolveHunter(event)
        if (hunter == null) {
            log.info("No SMS for event {}: nobody to text", event.eventId)
            return
        }
        val message = SmsPolicy.compose(event, hunter)
        if (message == null) {
            log.info("No SMS for event {}: hunter {} is inactive", event.eventId, hunter.id)
            return
        }
        // Claim before sending: two replicas can't both text, and a redelivery of an event we
        // already sent finds the claim and stops here.
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
            processedEvents.release(event.sourceEventId)
            throw ex
        }
        log.info(
            "Sent SMS for event {} (source {}) to hunter {}: messageId={}",
            event.eventId, event.sourceEventId, hunter.id, result.messageId,
        )
    }

    /**
     * Who the alert is for. The recipient address is the live path; [AnimalRecognized.hunterId] is
     * the fallback for events published before the lookup moved here, including anything replayed
     * from a dead-letter topic.
     *
     * A lookup failure is **not** caught: it propagates, the push endpoint answers 5xx and Pub/Sub
     * redelivers with backoff. That is the whole point of resolving here rather than in the
     * webhook — user-account scaling to zero costs a retry, not the alert.
     */
    private fun resolveHunter(event: AnimalRecognized): HunterContact? {
        val recipient = event.recipient
        if (recipient != null) {
            val hunter = hunterClient.findByInboundAddress(recipient)
            if (hunter == null) {
                log.info("Event {}: {} is not a hunter's address", event.eventId, recipient)
            }
            return hunter
        }
        val legacyId = event.hunterId
        if (legacyId != null) {
            log.info("Event {} carries no recipient; falling back to hunter id {}", event.eventId, legacyId)
            return hunterClient.findById(legacyId)
        }
        return null
    }
}
