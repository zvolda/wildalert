package com.wildalert.notification.event

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.wildalert.notification.alert.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Kafka adapter: consumes AnimalRecognized JSON and hands it to [NotificationService]. Active only
 * when events.provider=kafka, so the service still starts with no broker.
 *
 * Offsets: Spring Kafka commits after this method returns, so a crash mid-send means the event
 * is redelivered (at-least-once). Unreadable JSON is logged and skipped. Any other exception
 * (user-account or SMS provider down) goes to Spring Kafka's default error handler, which retries
 * the record a few times and then logs and skips it — a dead-letter topic comes in hardening.
 */
@Component
@ConditionalOnProperty(name = ["events.provider"], havingValue = "kafka")
class AnimalRecognizedListener(
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${events.topic.animal-recognized}"])
    fun onMessage(payload: String) {
        val event = try {
            objectMapper.readValue(payload, AnimalRecognized::class.java)
        } catch (ex: JacksonException) {
            log.warn("Skipping unreadable AnimalRecognized message: {}", ex.originalMessage)
            return
        }
        notificationService.notify(event)
    }
}
