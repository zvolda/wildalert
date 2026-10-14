package com.wildalert.notification.event

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.wildalert.notification.alert.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

/**
 * Pub/Sub push adapter: receives AnimalRecognized and hands it to [NotificationService]. Active only
 * when events.provider=pubsub, so the service still starts with no broker configured.
 *
 * **The HTTP status is the acknowledgement**, which is the whole reason this is a controller and not
 * a client library:
 * - 204 — handled (an SMS was sent, or deliberately not sent). Pub/Sub acks and moves on.
 * - 400 — the message can never be understood. Pub/Sub retries it, then parks it in the
 *   subscription's dead-letter topic, which is the only place its original bytes survive. (Under
 *   Kafka this case was logged and skipped, because a poison record blocked a partition; without
 *   ordering keys Pub/Sub has no head-of-line blocking, so retrying costs nothing.)
 * - 500 — a transient failure (user-account down, SMS provider failing). The exception propagates
 *   from [NotificationService], Pub/Sub retries with backoff, then dead-letters.
 *
 * Delivery is at-least-once, so the same event can arrive twice; [NotificationService] claims
 * `sourceEventId` before sending, which is what stops a second SMS.
 *
 * Authentication is Cloud Run's job: the subscription sends a Google-signed OIDC token and only the
 * push service account holds roles/run.invoker on this service, so an unauthenticated request never
 * reaches this method. There is deliberately no JWT verification here.
 */
@RestController
@Component
@ConditionalOnProperty(name = ["events.provider"], havingValue = "pubsub")
class AnimalRecognizedPushController(
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/events/animal-recognized")
    fun onPush(@RequestBody envelope: PubSubPushEnvelope): ResponseEntity<Void> {
        val data = envelope.message?.data
        if (data.isNullOrBlank()) {
            // Nothing to preserve, so acking is kinder than filling the dead-letter topic.
            log.warn("Push with no message data (subscription={}); acking", envelope.subscription)
            return ResponseEntity.noContent().build()
        }

        val json = try {
            Base64.getDecoder().decode(data).toString(Charsets.UTF_8)
        } catch (ex: IllegalArgumentException) {
            log.warn("Push message data is not valid base64; dead-lettering: {}", ex.message)
            return ResponseEntity.badRequest().build()
        }

        val event = try {
            objectMapper.readValue(json, AnimalRecognized::class.java)
        } catch (ex: JacksonException) {
            log.warn(
                "Unreadable AnimalRecognized message (messageId={}); dead-lettering: {}",
                envelope.message.messageId, ex.originalMessage,
            )
            return ResponseEntity.badRequest().build()
        }

        log.info(
            "Received AnimalRecognized id={} source={} (messageId={} attempt={})",
            event.eventId, event.sourceEventId, envelope.message.messageId, envelope.attempt(),
        )
        notificationService.notify(event)
        return ResponseEntity.noContent().build()
    }
}
