package com.wildalert.detectionhistory.event

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.wildalert.detectionhistory.history.DetectionHistoryService
import com.wildalert.detectionhistory.history.UnattributableDetectionException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

/**
 * Pub/Sub push adapter: receives AnimalRecognized and hands it to [DetectionHistoryService].
 * Active only when events.provider=pubsub, so the service still starts with no broker configured.
 *
 * This is the *second* subscription on `animal.recognized` — notification has the first. Neither
 * knows about the other: each gets its own copy of every event and acknowledges independently, so
 * a failure here redelivers to here only and cannot delay or duplicate an SMS.
 *
 * **The HTTP status is the acknowledgement**, which is why this is a controller and not a client
 * library:
 * - 204 — stored, or already stored. Pub/Sub acks and moves on.
 * - 400 — the message can never be handled: unreadable, or naming no hunter. Pub/Sub retries it,
 *   then parks it in the dead-letter topic, which is the only place its original bytes survive.
 * - 500 — a transient failure (the database is down). The exception propagates, Pub/Sub retries
 *   with backoff, then dead-letters. History is worth retrying for; losing a row silently is not
 *   an option, which is why nothing here catches a database error.
 *
 * Authentication is Cloud Run's job: the subscription sends a Google-signed OIDC token and only
 * the push service account holds roles/run.invoker on this service, so an unauthenticated request
 * never reaches this method. There is deliberately no JWT verification here.
 */
@RestController
@ConditionalOnProperty(name = ["events.provider"], havingValue = "pubsub")
class AnimalRecognizedPushController(
    private val objectMapper: ObjectMapper,
    private val detectionHistoryService: DetectionHistoryService,
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

        return try {
            detectionHistoryService.record(event)
            ResponseEntity.noContent().build()
        } catch (ex: UnattributableDetectionException) {
            // Retrying cannot add a hunter to an event that has none, so dead-letter it instead of
            // burning delivery attempts. A database failure is NOT caught here — that one should
            // retry, so it propagates as a 500.
            log.warn("Cannot attribute detection; dead-lettering: {}", ex.message)
            ResponseEntity.badRequest().build()
        }
    }
}
