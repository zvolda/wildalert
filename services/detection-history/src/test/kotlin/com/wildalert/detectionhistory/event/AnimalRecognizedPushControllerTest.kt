package com.wildalert.detectionhistory.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.wildalert.detectionhistory.history.DetectionHistoryService
import com.wildalert.detectionhistory.history.InMemoryDetectionStore
import com.wildalert.detectionhistory.history.UnattributableDetectionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The payload strings here are real messages captured from the Python recognition service, so this
 * test is what keeps the hand-duplicated event contract (Kotlin `AnimalRecognized` vs Python
 * `events.py`) honest for this consumer too. notification has the same test against its own copy —
 * duplicated deliberately, because the point of each service owning its copy of the wire format is
 * that each one can be checked against the real bytes independently.
 */
class AnimalRecognizedPushControllerTest {

    // Configured like Spring Boot's ObjectMapper (Kotlin + java.time, unknown fields ignored).
    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    /** Records what it was asked to store. (Mockito captors return null for Kotlin non-null
     *  parameters, so a small fake is simpler.) */
    private class RecordingHistoryService : DetectionHistoryService(InMemoryDetectionStore()) {
        val received = mutableListOf<AnimalRecognized>()
        var failWith: RuntimeException? = null

        override fun record(event: AnimalRecognized) {
            failWith?.let { throw it }
            received.add(event)
        }
    }

    private val historyService = RecordingHistoryService()
    private val controller = AnimalRecognizedPushController(objectMapper, historyService)

    private fun push(json: String) = PubSubPushEnvelope(
        message = PubSubPushMessage(
            data = Base64.getEncoder().encodeToString(json.toByteArray()),
            messageId = "1",
        ),
        subscription = "projects/wildalert-local/subscriptions/animal.recognized.history",
    )

    @Test
    fun `parses the JSON published by the Python recognition service`() {
        val payload = """{"eventId":"afc3e286-b870-4787-a5aa-b097a08bed2e","sourceEventId":"aaaaaaaa-0000-4000-8000-000000000001","recipient":"7f3k9qabcdef@in.wildalert.local","hunterId":null,"storageKey":"inbound/2026/09/15/11111111-1111-4111-8111-111111111111.jpg","species":"wild boar","confidence":0.9998869895935059,"lowConfidence":false,"occurredAt":"2026-09-15T11:40:58.981643Z"}"""

        val response = controller.onPush(push(payload))

        assertThat(response.statusCode.value()).isEqualTo(204)
        assertThat(historyService.received).containsExactly(
            AnimalRecognized(
                eventId = UUID.fromString("afc3e286-b870-4787-a5aa-b097a08bed2e"),
                sourceEventId = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"),
                recipient = "7f3k9qabcdef@in.wildalert.local",
                storageKey = "inbound/2026/09/15/11111111-1111-4111-8111-111111111111.jpg",
                species = "wild boar",
                confidence = 0.9998869895935059,
                lowConfidence = false,
                occurredAt = Instant.parse("2026-09-15T11:40:58.981643Z"),
            ),
        )
    }

    @Test
    fun `still parses a pre-change payload that carries a hunter id and no recipient`() {
        // Replay from a dead-letter topic: published before the recipient moved onto the event.
        val payload = """{"eventId":"afc3e286-b870-4787-a5aa-b097a08bed2e","sourceEventId":"aaaaaaaa-0000-4000-8000-000000000001","hunterId":"5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60","storageKey":"inbound/2026/09/15/11111111-1111-4111-8111-111111111111.jpg","species":"wild boar","confidence":0.9998869895935059,"lowConfidence":false,"occurredAt":"2026-09-15T11:40:58.981643Z"}"""

        val response = controller.onPush(push(payload))

        assertThat(response.statusCode.value()).isEqualTo(204)
        val event = historyService.received.single()
        assertThat(event.recipient).isNull()
        assertThat(event.hunterId).isEqualTo(UUID.fromString("5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60"))
    }

    @Test
    fun `a low-confidence result is still recorded`() {
        // "We saw something and were unsure" is history worth keeping, and it is what the
        // confidence threshold gets tuned against.
        val payload = """{"eventId":"aec43fb5-491a-4ddc-a1b0-31b449614b4b","sourceEventId":"aaaaaaaa-0000-4000-8000-000000000002","recipient":"7f3k9qabcdef@in.wildalert.local","hunterId":null,"storageKey":"inbound/x.jpg","species":"fallow deer","confidence":0.65,"lowConfidence":true,"occurredAt":"2026-09-15T11:41:00.763291Z","cameraId":"north"}"""

        val response = controller.onPush(push(payload))

        assertThat(response.statusCode.value()).isEqualTo(204)
        assertThat(historyService.received.single().lowConfidence).isTrue()
    }

    /** Unreadable messages are nacked (400) so Pub/Sub dead-letters them with their original bytes. */
    @Test
    fun `unreadable message is dead-lettered without recording`() {
        val notJson = controller.onPush(push("this is not json"))
        val partial = controller.onPush(push("""{"species":"wild boar"}"""))

        assertThat(notJson.statusCode.value()).isEqualTo(400)
        assertThat(partial.statusCode.value()).isEqualTo(400)
        assertThat(historyService.received).isEmpty()
    }

    @Test
    fun `data that is not valid base64 is dead-lettered`() {
        val envelope = PubSubPushEnvelope(message = PubSubPushMessage(data = "!!!not base64!!!"))

        assertThat(controller.onPush(envelope).statusCode.value()).isEqualTo(400)
        assertThat(historyService.received).isEmpty()
    }

    @Test
    fun `an envelope with no message data is acked and ignored`() {
        val empty = controller.onPush(PubSubPushEnvelope(message = PubSubPushMessage(data = "")))
        val missing = controller.onPush(PubSubPushEnvelope())

        assertThat(empty.statusCode.value()).isEqualTo(204)
        assertThat(missing.statusCode.value()).isEqualTo(204)
        assertThat(historyService.received).isEmpty()
    }

    /**
     * An event naming no hunter can never be handled, so retrying it is pointless: 400 sends it
     * to the dead-letter topic instead of burning every delivery attempt first.
     */
    @Test
    fun `an unattributable event is dead-lettered`() {
        historyService.failWith = UnattributableDetectionException("no recipient and no hunterId")
        val payload = """{"eventId":"afc3e286-b870-4787-a5aa-b097a08bed2e","sourceEventId":"aaaaaaaa-0000-4000-8000-000000000003","recipient":null,"hunterId":null,"storageKey":"inbound/y.jpg","species":"roe deer","confidence":0.9,"lowConfidence":false,"occurredAt":"2026-09-15T11:41:00.763291Z"}"""

        assertThat(controller.onPush(push(payload)).statusCode.value()).isEqualTo(400)
    }

    /**
     * A transient failure (the database being down) must reach Pub/Sub as a 5xx so the event is
     * redelivered. Losing history silently is the one outcome worse than a delayed write.
     */
    @Test
    fun `a failure while recording propagates`() {
        historyService.failWith = IllegalStateException("the database is down")
        val payload = """{"eventId":"afc3e286-b870-4787-a5aa-b097a08bed2e","sourceEventId":"aaaaaaaa-0000-4000-8000-000000000004","recipient":"7f3k9qabcdef@in.wildalert.local","hunterId":null,"storageKey":"inbound/y.jpg","species":"roe deer","confidence":0.9,"lowConfidence":false,"occurredAt":"2026-09-15T11:41:00.763291Z"}"""

        assertThatThrownBy { controller.onPush(push(payload)) }
            .hasMessage("the database is down")
    }
}
