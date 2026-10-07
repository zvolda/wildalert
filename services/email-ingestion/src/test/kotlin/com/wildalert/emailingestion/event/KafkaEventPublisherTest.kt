package com.wildalert.emailingestion.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.kafka.core.KafkaTemplate
import java.util.UUID

class KafkaEventPublisherTest {

    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val publisher = KafkaEventPublisher(kafkaTemplate, objectMapper, "image.received")

    @Test
    fun `publishes ImageReceived as JSON keyed by hunter to the topic`() {
        val sent = mutableListOf<Triple<String, String, String>>()
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willAnswer { inv ->
            sent.add(Triple(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))
            null
        }
        val hunterId = UUID.randomUUID()
        val event = ImageReceived(
            storageKey = "inbound/2026/09/15/abc.jpg",
            contentType = "image/jpeg",
            senderEmail = "hunter@example.com",
            hunterId = hunterId,
        )

        publisher.publish(event)

        assertThat(sent).hasSize(1)
        val (topic, key, payload) = sent.single()
        assertThat(topic).isEqualTo("image.received")
        assertThat(key).isEqualTo(hunterId.toString())
        assertThat(payload)
            .contains("\"storageKey\":\"inbound/2026/09/15/abc.jpg\"")
            .contains("\"hunterId\":\"$hunterId\"")
            .contains(event.eventId.toString())
    }

    @Test
    fun `falls back to the event id as key when there is no hunter`() {
        val sent = mutableListOf<Triple<String, String, String>>()
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willAnswer { inv ->
            sent.add(Triple(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))
            null
        }
        val event = ImageReceived(
            storageKey = "inbound/x.jpg",
            contentType = "image/jpeg",
            senderEmail = null,
            hunterId = null,
        )

        publisher.publish(event)

        assertThat(sent.single().second).isEqualTo(event.eventId.toString())
    }
}
