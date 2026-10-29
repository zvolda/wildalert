package com.wildalert.notification.alert

import com.wildalert.notification.event.AnimalRecognized
import com.wildalert.notification.hunter.ChargeOutcome
import com.wildalert.notification.hunter.HunterCharge
import com.wildalert.notification.hunter.Notice
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SmsPolicyTest {

    private val hunter = HunterCharge(
        id = UUID.randomUUID(),
        phone = "+420123456789",
        active = true,
        outcome = ChargeOutcome.CHARGED,
        balanceAfterMinor = 2350,
        notice = Notice.NONE,
    )

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
    fun `a healthy balance says nothing about credit`() {
        val sms = SmsPolicy.compose(event(), hunter)!!

        assertThat(sms.body).doesNotContain("Credit")
    }

    @Test
    fun `a low balance is appended to the alert, with the amount in CZK`() {
        val sms = SmsPolicy.compose(
            event(),
            hunter.copy(notice = Notice.LOW_BALANCE, balanceAfterMinor = 350),
        )!!

        assertThat(sms.body)
            .isEqualTo("WildAlert: wild boar detected (97% confidence) Credit low: 3.50 CZK left.")
    }

    @Test
    fun `running out is appended to the alert that emptied the balance`() {
        val sms = SmsPolicy.compose(
            event(),
            hunter.copy(notice = Notice.OUT_OF_CREDIT, balanceAfterMinor = 0),
        )!!

        assertThat(sms.body).endsWith(" Credit used up - no more alerts until you top up.")
    }

    @Test
    fun `minor units under ten are zero-padded, so 5 is not 0_5 CZK`() {
        val sms = SmsPolicy.compose(
            event(),
            hunter.copy(notice = Notice.LOW_BALANCE, balanceAfterMinor = 5),
        )!!

        assertThat(sms.body).contains("0.05 CZK left")
    }

    @Test
    fun `a whole-koruna balance still shows both decimals`() {
        val sms = SmsPolicy.compose(
            event(),
            hunter.copy(notice = Notice.LOW_BALANCE, balanceAfterMinor = 400),
        )!!

        assertThat(sms.body).contains("4.00 CZK left")
    }

    @Test
    fun `messages fit a single plain SMS segment, warning and all`() {
        // The longest combination the policy can produce: the hedged wording (which is longer than
        // naming a species) plus a credit warning. A second segment would double the price of the
        // very message that says the hunter is nearly out of credit.
        val longest = SmsPolicy.compose(
            event(species = "micromammal", lowConfidence = true, confidence = 0.1),
            hunter.copy(notice = Notice.OUT_OF_CREDIT, balanceAfterMinor = 0),
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
        recipient = "7f3k9qabcdef@wildalert.local",
        storageKey = "inbound/2026/09/15/a.jpg",
        species = species,
        confidence = confidence,
        lowConfidence = lowConfidence,
        occurredAt = Instant.now(),
    )
}
