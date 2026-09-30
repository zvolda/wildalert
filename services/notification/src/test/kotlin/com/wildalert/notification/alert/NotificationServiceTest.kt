package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.HunterClient
import com.wildalert.notification.hunter.HunterContact
import com.wildalert.notification.idempotency.InMemoryProcessedEvents
import com.wildalert.notification.sms.SmsMessage
import com.wildalert.notification.sms.SmsResult
import com.wildalert.notification.sms.SmsSender
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.web.client.ResourceAccessException
import java.time.Instant
import java.util.UUID

class NotificationServiceTest {

    /** Fake SmsSender that records what would have been sent. */
    private class RecordingSmsSender : SmsSender {
        val sent = mutableListOf<SmsMessage>()
        override fun send(message: SmsMessage): SmsResult {
            sent.add(message)
            return SmsResult("fake-${sent.size}")
        }
    }

    private val hunterClient: HunterClient = mock(HunterClient::class.java)
    private val smsSender = RecordingSmsSender()
    private val processedEvents = InMemoryProcessedEvents()
    private val service = NotificationService(hunterClient, smsSender, processedEvents)

    private val hunterId = UUID.randomUUID()

    @Test
    fun `texts the hunter's phone for a recognized animal`() {
        given(hunterClient.findById(hunterId)).willReturn(HunterContact(hunterId, "+420123456789", active = true))

        service.notify(event(hunterId))

        assertThat(smsSender.sent).containsExactly(
            SmsMessage("+420123456789", "WildAlert: wild boar detected (97% confidence)"),
        )
    }

    @Test
    fun `unknown sender is not looked up or texted`() {
        service.notify(event(hunterId = null))

        verifyNoInteractions(hunterClient)
        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `hunter that no longer exists is not texted`() {
        given(hunterClient.findById(hunterId)).willReturn(null)

        service.notify(event(hunterId))

        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `inactive hunter is not texted`() {
        given(hunterClient.findById(hunterId)).willReturn(HunterContact(hunterId, "+420123456789", active = false))

        service.notify(event(hunterId))

        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `user-account outage propagates so the event is retried, not dropped`() {
        given(hunterClient.findById(hunterId)).willThrow(ResourceAccessException("Connection refused"))

        assertThatThrownBy { service.notify(event(hunterId)) }.isInstanceOf(ResourceAccessException::class.java)
        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `a redelivered event does not text the hunter twice`() {
        given(hunterClient.findById(hunterId)).willReturn(HunterContact(hunterId, "+420123456789", active = true))
        val event = event(hunterId)

        service.notify(event)
        service.notify(event.copy(eventId = UUID.randomUUID())) // republished result, same source photo

        assertThat(smsSender.sent).hasSize(1)
    }

    @Test
    fun `two photos from the same hunter each get their own SMS`() {
        given(hunterClient.findById(hunterId)).willReturn(HunterContact(hunterId, "+420123456789", active = true))

        service.notify(event(hunterId))
        service.notify(event(hunterId)) // different sourceEventId

        assertThat(smsSender.sent).hasSize(2)
    }

    @Test
    fun `a failed send releases the claim so the retry still texts the hunter`() {
        given(hunterClient.findById(hunterId)).willReturn(HunterContact(hunterId, "+420123456789", active = true))
        val failing = object : SmsSender {
            var fail = true
            val sent = mutableListOf<SmsMessage>()
            override fun send(message: SmsMessage): SmsResult {
                if (fail) throw IllegalStateException("Twilio unavailable")
                sent.add(message)
                return SmsResult("after-retry")
            }
        }
        val retryingService = NotificationService(hunterClient, failing, processedEvents)
        val event = event(hunterId)

        assertThatThrownBy { retryingService.notify(event) }.isInstanceOf(IllegalStateException::class.java)
        failing.fail = false
        retryingService.notify(event) // Kafka redelivers the same event

        // The failed attempt must not count as handled, or the hunter would never be told.
        assertThat(failing.sent).hasSize(1)
    }

    private fun event(hunterId: UUID?) = AnimalRecognized(
        eventId = UUID.randomUUID(),
        sourceEventId = UUID.randomUUID(),
        hunterId = hunterId,
        storageKey = "inbound/2026/09/15/a.jpg",
        species = "wild boar",
        confidence = 0.97,
        lowConfidence = false,
        occurredAt = Instant.now(),
    )
}
