package com.wildalert.emailingestion.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.cloud.spring.pubsub.core.PubSubOperations
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PubSubEventPublisherTest {

    private val pubSub = mock(PubSubOperations::class.java)
    private val objectMapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val publisher = PubSubEventPublisher(pubSub, objectMapper, "image.received", 5)

    @Test
    fun `publishes ImageReceived as JSON to the topic`() {
        given(pubSub.publish(anyString(), anyString()))
            .willReturn(CompletableFuture.completedFuture("message-1"))
        val hunterId = UUID.randomUUID()
        val event = ImageReceived(
            storageKey = "inbound/2026/09/15/abc.jpg",
            contentType = "image/jpeg",
            senderEmail = "hunter@example.com",
            hunterId = hunterId,
        )

        publisher.publish(event)

        val topic = ArgumentCaptor.forClass(String::class.java)
        val payload = ArgumentCaptor.forClass(String::class.java)
        verify(pubSub).publish(topic.capture(), payload.capture())
        assertThat(topic.value).isEqualTo("image.received")
        assertThat(payload.value)
            .contains("\"storageKey\":\"inbound/2026/09/15/abc.jpg\"")
            .contains("\"hunterId\":\"$hunterId\"")
            .contains(event.eventId.toString())
    }

    @Test
    fun `publishes an event with no hunter`() {
        given(pubSub.publish(anyString(), anyString()))
            .willReturn(CompletableFuture.completedFuture("message-2"))
        val event = ImageReceived(
            storageKey = "inbound/x.jpg",
            contentType = "image/jpeg",
            senderEmail = null,
            hunterId = null,
        )

        publisher.publish(event)

        val payload = ArgumentCaptor.forClass(String::class.java)
        verify(pubSub).publish(anyString(), payload.capture())
        assertThat(payload.value).contains("\"hunterId\":null")
    }

    /**
     * The point of blocking on the publish: a broker failure must reach the caller, so the webhook
     * answers 5xx instead of 200 for a photo nobody will ever recognise.
     */
    @Test
    fun `a failed publish propagates instead of being swallowed`() {
        given(pubSub.publish(anyString(), anyString()))
            .willReturn(CompletableFuture.failedFuture(IllegalStateException("pubsub is down")))
        val event = ImageReceived(
            storageKey = "inbound/y.jpg",
            contentType = "image/jpeg",
            senderEmail = null,
            hunterId = null,
        )

        assertThatThrownBy { publisher.publish(event) }
            .hasRootCauseMessage("pubsub is down")
    }
}
