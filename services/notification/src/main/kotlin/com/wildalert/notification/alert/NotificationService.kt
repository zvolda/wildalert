package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.HunterClient
import com.wildalert.notification.idempotency.ProcessedEvents
import com.wildalert.notification.sms.SmsSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Turns a recognition result into an SMS: find the hunter, apply the SMS policy, send.
 * Independent of Kafka — the listener just calls [notify] for each event.
 */
@Service
class NotificationService(
    private val hunterClient: HunterClient,
    private val smsSender: SmsSender,
    private val processedEvents: ProcessedEvents,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notify(event: AnimalRecognized) {
        val hunterId = event.hunterId
        if (hunterId == null) {
            log.info("No SMS for event {}: photo came from an unknown sender", event.eventId)
            return
        }
        val hunter = hunterClient.findById(hunterId)
        if (hunter == null) {
            log.warn("No SMS for event {}: hunter {} no longer exists", event.eventId, hunterId)
            return
        }
        val message = SmsPolicy.compose(event, hunter)
        if (message == null) {
            log.info("No SMS for event {}: hunter {} is inactive", event.eventId, hunterId)
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
            event.eventId, event.sourceEventId, hunterId, result.messageId,
        )
    }
}
