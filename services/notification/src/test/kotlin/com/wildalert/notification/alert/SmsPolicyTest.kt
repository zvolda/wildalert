package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.HunterContact
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SmsPolicyTest {

    private val hunter = HunterContact(id = UUID.randomUUID(), phone = "+420123456789", active = true)

    @Test
    fun `confident result names the species with its confidence`() {
        val sms = SmsPolicy.compose(event(species = "wild boar", confidence = 0.97), hunter)

        assertThat(sms?.to).isEqualTo("+420123456789")
        assertThat(sms?.body).isEqualTo("WildAlert: wild boar detected (97% confidence)")
    }

    @Test
    fun `low confidence result is hedged instead of stated as fact`() {
        val sms = SmsPolicy.compose(
            event(species = "fallow deer", confidence = 0.65, lowConfidence = true),
            hunter,
        )

        assertThat(sms?.body).isEqualTo("WildAlert: animal detected, species uncertain (maybe fallow deer)")
    }

    @Test
    fun `confidence is rounded down so near-certain never reads 100 percent`() {
        val sms = SmsPolicy.compose(event(confidence = 0.9998869895935059), hunter)

        assertThat(sms?.body).contains("(99% confidence)")
    }

    @Test
    fun `inactive hunter gets no SMS`() {
        assertThat(SmsPolicy.compose(event(), hunter.copy(active = false))).isNull()
    }

    @Test
    fun `messages fit a single plain SMS segment`() {
        val longest = SmsPolicy.compose(
            event(species = "micromammal", lowConfidence = true, confidence = 0.1),
            hunter,
        )!!.body

        assertThat(longest.length).isLessThanOrEqualTo(160)
        assertThat(longest).matches("\\p{ASCII}+")
    }

    private fun event(
        species: String = "wild boar",
        confidence: Double = 0.97,
        lowConfidence: Boolean = false,
    ) = AnimalRecognized(
        eventId = UUID.randomUUID(),
        sourceEventId = UUID.randomUUID(),
        hunterId = hunter.id,
        storageKey = "inbound/2026/09/15/a.jpg",
        species = species,
        confidence = confidence,
        lowConfidence = lowConfidence,
        occurredAt = Instant.now(),
    )
}
