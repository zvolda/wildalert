package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.ChargeOutcome
import com.wildalert.notification.hunter.ChargeRequest
import com.wildalert.notification.hunter.HunterCharge
import com.wildalert.notification.hunter.HunterClient
import com.wildalert.notification.hunter.Notice
import com.wildalert.notification.idempotency.InMemoryProcessedEvents
import com.wildalert.notification.sms.SmsMessage
import com.wildalert.notification.sms.SmsResult
import com.wildalert.notification.sms.SmsSender
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
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
    private val service = NotificationService(hunterClient, smsSender, processedEvents, SMS_COST)

    private val hunterId = UUID.randomUUID()
    private val recipient = "7f3k9qabcdef@wildalert.local"

    @Test
    fun `texts the hunter's phone for a recognized animal`() {
        givenCharge(ChargeOutcome.CHARGED)

        service.notify(event())

        assertThat(smsSender.sent).containsExactly(
            SmsMessage("+420123456789", "WildAlert: wild boar detected (97% confidence)"),
        )
    }

    @Test
    fun `charges the configured price, keyed on the source event and not the event id`() {
        givenCharge(ChargeOutcome.CHARGED)
        val event = event()

        service.notify(event)

        val request = ArgumentCaptor.forClass(ChargeRequest::class.java)
        verify(hunterClient).chargeByInboundAddress(eqValue(recipient), capture(request))
        assertThat(request.value.amountMinor).isEqualTo(SMS_COST)
        assertThat(request.value.entryType).isEqualTo("SMS_DEBIT")
        // eventId is fresh on every republish; sourceEventId is what survives it. Charging on the
        // wrong one would bill one photo again and again.
        assertThat(request.value.sourceEventId).isEqualTo(event.sourceEventId.toString())
    }

    @Test
    fun `unknown sender is not charged or texted`() {
        service.notify(event(recipient = null))

        verifyNoInteractions(hunterClient)
        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `hunter that no longer exists is not texted`() {
        givenNoHunter()

        service.notify(event())

        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `inactive hunter is not texted`() {
        givenCharge(ChargeOutcome.INACTIVE, active = false)

        service.notify(event())

        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `a hunter with no credit is not texted`() {
        givenCharge(ChargeOutcome.INSUFFICIENT, balance = 0)

        service.notify(event())

        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `an unaffordable alert does not claim the event`() {
        // The refusal must not consume the source id. Nothing retries today -- we answer the push
        // 200 -- but a claim here would also silence the event if it were ever replayed after a
        // top-up, and would make the idempotency store disagree with the money.
        givenCharge(ChargeOutcome.INSUFFICIENT, balance = 0)
        val event = event()

        service.notify(event)

        assertThat(processedEvents.claim(event.sourceEventId)).isTrue()
    }

    @Test
    fun `an already-charged event is still texted, because the charge is a success`() {
        // The ordinary answer to a redelivery the far side has already billed. Reading this as a
        // failure is the easiest mistake to make against the contract, and it would silence every
        // redelivered alert.
        givenCharge(ChargeOutcome.ALREADY_CHARGED, balance = 1000)

        service.notify(event())

        assertThat(smsSender.sent).hasSize(1)
    }

    @Test
    fun `a low balance warning rides along on the alert`() {
        givenCharge(ChargeOutcome.CHARGED, balance = 350, notice = Notice.LOW_BALANCE)

        service.notify(event())

        assertThat(smsSender.sent.single().body).endsWith(" Credit low: 3.50 CZK left.")
    }

    @Test
    fun `running dry is told on the alert that emptied the balance, not in its own SMS`() {
        givenCharge(ChargeOutcome.CHARGED, balance = 0, notice = Notice.OUT_OF_CREDIT)

        service.notify(event())

        assertThat(smsSender.sent).hasSize(1)
        assertThat(smsSender.sent.single().body).contains("Credit used up")
    }

    @Test
    fun `user-account outage propagates so the event is retried, not dropped`() {
        givenChargeFails()

        assertThatThrownBy { service.notify(event()) }.isInstanceOf(ResourceAccessException::class.java)
        assertThat(smsSender.sent).isEmpty()
    }

    @Test
    fun `a failed charge leaves the event unclaimed so the redelivery can still send`() {
        // This is the ordering that matters. If the claim came before the charge, a cold
        // user-account would eat the event: the attempt throws, the claim is already taken, and
        // the redelivery that should have rescued the alert finds it handled and stays silent.
        givenChargeFails()
        val event = event()

        assertThatThrownBy { service.notify(event) }.isInstanceOf(ResourceAccessException::class.java)

        assertThat(processedEvents.claim(event.sourceEventId)).isTrue()
    }

    @Test
    fun `a redelivered event does not text the hunter twice`() {
        givenCharge(ChargeOutcome.CHARGED)
        val event = event()

        service.notify(event)
        service.notify(event.copy(eventId = UUID.randomUUID())) // republished result, same source photo

        assertThat(smsSender.sent).hasSize(1)
    }

    @Test
    fun `a redelivery still charges, and relies on the far side to make it a no-op`() {
        // We do not try to be clever and skip the call: only user-account can know whether this
        // event was billed, and its unique index is what makes the second charge free. Our own
        // claim is per-instance when the store is in memory, so it cannot be the money's guard.
        givenCharge(ChargeOutcome.CHARGED)
        val event = event()

        service.notify(event)
        service.notify(event.copy(eventId = UUID.randomUUID()))

        verify(hunterClient, times(2)).chargeByInboundAddress(eqValue(recipient), anyRequest())
    }

    @Test
    fun `two photos from the same hunter each get their own SMS`() {
        givenCharge(ChargeOutcome.CHARGED)

        service.notify(event())
        service.notify(event()) // different sourceEventId

        assertThat(smsSender.sent).hasSize(2)
    }

    @Test
    fun `a failed send releases the claim so the retry still texts the hunter`() {
        givenCharge(ChargeOutcome.CHARGED)
        val failing = object : SmsSender {
            var fail = true
            val sent = mutableListOf<SmsMessage>()
            override fun send(message: SmsMessage): SmsResult {
                if (fail) throw IllegalStateException("Twilio unavailable")
                sent.add(message)
                return SmsResult("after-retry")
            }
        }
        val retryingService = NotificationService(hunterClient, failing, processedEvents, SMS_COST)
        val event = event()

        assertThatThrownBy { retryingService.notify(event) }.isInstanceOf(IllegalStateException::class.java)
        failing.fail = false
        retryingService.notify(event) // Pub/Sub redelivers the same event

        // The failed attempt must not count as handled, or the hunter would never be told.
        assertThat(failing.sent).hasSize(1)
        // The charge is deliberately *not* given back: the retry reads ALREADY_CHARGED on the far
        // side and sends. One alert, charged once, however many attempts it took.
    }

    @Test
    fun `falls back to the hunter id on an event published before the change`() {
        // A dead-letter replay can deliver one of these days later; it must still reach its hunter.
        given(hunterClient.chargeById(eqValue(hunterId), anyRequest()))
            .willReturn(charge(ChargeOutcome.CHARGED))

        service.notify(legacyEvent(hunterId))

        assertThat(smsSender.sent).hasSize(1)
        verify(hunterClient, never()).chargeByInboundAddress(anyAddress(), anyRequest())
    }

    @Test
    fun `an address that belongs to nobody sends nothing`() {
        // email-ingestion forwards any address on our domain without checking it, so this is the
        // normal outcome for mail to a made-up name -- not an error.
        givenNoHunter()

        service.notify(event())

        assertThat(smsSender.sent).isEmpty()
    }

    private fun givenCharge(
        outcome: ChargeOutcome,
        balance: Long = 2350,
        notice: Notice = Notice.NONE,
        active: Boolean = true,
    ) {
        given(hunterClient.chargeByInboundAddress(eqValue(recipient), anyRequest()))
            .willReturn(charge(outcome, balance, notice, active))
    }

    private fun givenNoHunter() {
        given(hunterClient.chargeByInboundAddress(eqValue(recipient), anyRequest())).willReturn(null)
    }

    private fun givenChargeFails() {
        given(hunterClient.chargeByInboundAddress(eqValue(recipient), anyRequest()))
            .willThrow(ResourceAccessException("Connection refused"))
    }

    private fun charge(
        outcome: ChargeOutcome,
        balance: Long = 2350,
        notice: Notice = Notice.NONE,
        active: Boolean = true,
    ) = HunterCharge(
        id = hunterId,
        phone = "+420123456789",
        active = active,
        outcome = outcome,
        balanceAfterMinor = balance,
        notice = notice,
    )

    private fun event(recipient: String? = this.recipient) = AnimalRecognized(
        eventId = UUID.randomUUID(),
        sourceEventId = UUID.randomUUID(),
        recipient = recipient,
        storageKey = "inbound/2026/09/15/a.jpg",
        species = "wild boar",
        confidence = 0.97,
        lowConfidence = false,
        occurredAt = Instant.now(),
    )

    /** What recognition published before the recipient moved onto the event. */
    private fun legacyEvent(hunterId: UUID?) = event(recipient = null).copy(hunterId = hunterId)

    private companion object {
        const val SMS_COST = 150L

        // Mockito's matchers and captors return null, and the Kotlin compiler inserts a null check
        // at the call site when a non-null parameter of a *Kotlin* function is handed a nullable
        // expression -- so a plain `chargeByInboundAddress(eq(recipient), any())` throws
        // "must not be null" before Mockito is ever consulted. By the time the matcher call returns
        // it has already registered itself, so substituting any non-null placeholder for the
        // discarded return value is safe. Same helpers as DetectionControllerTest, same reason.
        private fun <T : Any> eqValue(value: T): T = ArgumentMatchers.eq(value) ?: value

        private fun anyRequest(): ChargeRequest =
            ArgumentMatchers.any(ChargeRequest::class.java) ?: PLACEHOLDER

        private fun anyAddress(): String = ArgumentMatchers.anyString() ?: ""

        private fun capture(captor: ArgumentCaptor<ChargeRequest>): ChargeRequest =
            captor.capture() ?: PLACEHOLDER

        private val PLACEHOLDER = ChargeRequest(sourceEventId = "", amountMinor = 0)
    }
}
