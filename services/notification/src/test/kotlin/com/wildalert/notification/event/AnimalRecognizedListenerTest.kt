package com.wildalert.notification.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.wildalert.notification.alert.NotificationService
import com.wildalert.notification.hunter.HunterClient
import com.wildalert.notification.idempotency.InMemoryProcessedEvents
import com.wildalert.notification.sms.SmsSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Instant
import java.util.UUID

class AnimalRecognizedListenerTest {

    // Configured like Spring Boot's ObjectMapper (Kotlin + java.time, unknown fields ignored).
    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    /** Records the events it is asked to notify about. (Mockito captors return null for Kotlin
     *  non-null parameters, so a small fake is simpler.) */
    private class RecordingNotificationService :
        NotificationService(
            mock(HunterClient::class.java),
            mock(SmsSender::class.java),
            InMemoryProcessedEvents(),
        ) {
        val received = mutableListOf<AnimalRecognized>()
        override fun notify(event: AnimalRecognized) {
            received.add(event)
        }
    }

    private val notificationService = RecordingNotificationService()
    private val listener = AnimalRecognizedListener(objectMapper, notificationService)

    @Test
    fun `parses the JSON published by the Python recognition worker`() {
        // Copied from a real message on animal.recognized (verified run of the recognition worker).
        val payload = """{"eventId":"afc3e286-b870-4787-a5aa-b097a08bed2e","sourceEventId":"aaaaaaaa-0000-4000-8000-000000000001","hunterId":"5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60","storageKey":"inbound/2026/09/15/11111111-1111-4111-8111-111111111111.jpg","species":"wild boar","confidence":0.9998869895935059,"lowConfidence":false,"occurredAt":"2026-09-15T11:40:58.981643Z"}"""

        listener.onMessage(payload)

        assertThat(notificationService.received).containsExactly(
            AnimalRecognized(
                eventId = UUID.fromString("afc3e286-b870-4787-a5aa-b097a08bed2e"),
                sourceEventId = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"),
                hunterId = UUID.fromString("5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60"),
                storageKey = "inbound/2026/09/15/11111111-1111-4111-8111-111111111111.jpg",
                species = "wild boar",
                confidence = 0.9998869895935059,
                lowConfidence = false,
                occurredAt = Instant.parse("2026-09-15T11:40:58.981643Z"),
            ),
        )
    }

    @Test
    fun `unknown sender (null hunterId) and extra fields are accepted`() {
        val payload = """{"eventId":"aec43fb5-491a-4ddc-a1b0-31b449614b4b","sourceEventId":"aaaaaaaa-0000-4000-8000-000000000002","hunterId":null,"storageKey":"inbound/x.jpg","species":"fallow deer","confidence":0.65,"lowConfidence":true,"occurredAt":"2026-09-15T11:41:00.763291Z","cameraId":"north"}"""

        listener.onMessage(payload)

        assertThat(notificationService.received.single().hunterId).isNull()
    }

    @Test
    fun `unreadable message is skipped without notifying`() {
        listener.onMessage("this is not json")
        listener.onMessage("""{"species":"wild boar"}""")

        assertThat(notificationService.received).isEmpty()
    }
}
