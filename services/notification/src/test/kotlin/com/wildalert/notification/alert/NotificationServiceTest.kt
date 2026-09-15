package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.HunterClient
import com.wildalert.notification.hunter.HunterContact
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
    private val service = NotificationService(hunterClient, smsSender)

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
