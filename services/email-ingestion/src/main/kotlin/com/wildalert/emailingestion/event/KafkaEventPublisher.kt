package com.wildalert.emailingestion.event

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * Real EventPublisher that sends events to Kafka as JSON. Active only when events.provider=kafka,
 * so the fake logging publisher is used until a broker is configured. Events are keyed by hunter
 * (falling back to the event id) so a given hunter's events stay ordered within a partition.
 */
@Component
@ConditionalOnProperty(name = ["events.provider"], havingValue = "kafka")
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${events.topic.image-received}") private val topic: String,
) : EventPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: ImageReceived) {
        val key = (event.hunterId ?: event.eventId).toString()
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(topic, key, payload)
        log.info("Published ImageReceived id={} key={} to topic {}", event.eventId, key, topic)
    }
}
