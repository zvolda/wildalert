package com.wildalert.notification.sms

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import java.time.Duration

/**
 * The guard's rules, which are all about *when not to block*: an alert refused here is an alert the
 * hunter does not get, so every lenient branch is pinned as deliberately as the blocking one.
 */
class SmsProviderCreditGuardTest {

    /** Counts reads, so the caching rules can be asserted rather than assumed. */
    private class FakeProviderCredit(private var answer: () -> ProviderCredit) : SmsProviderCredit {
        var reads = 0
        override fun remaining(): ProviderCredit {
            reads++
            return answer()
        }

        fun nowAnswers(answer: () -> ProviderCredit) {
            this.answer = answer
        }
    }

    private val props = SmsProviderCreditProperties(
        messagePriceMinor = 120,
        warnMinor = 2000,
        ttl = Duration.ofMinutes(5),
    )

    private fun known(minor: Long) = ProviderCredit.Known(minor, "CZK")

    @Test
    fun `a balance that covers a message lets the alert through`() {
        val guard = SmsProviderCreditGuard(FakeProviderCredit { known(31521) }, props)

        assertThatCode { guard.requireCreditForOneMessage() }.doesNotThrowAnyException()
    }

    @Test
    fun `a balance below one message blocks, so nobody is charged for silence`() {
        val guard = SmsProviderCreditGuard(FakeProviderCredit { known(119) }, props)

        assertThatThrownBy { guard.requireCreditForOneMessage() }
            .isInstanceOf(SmsProviderOutOfCreditException::class.java)
            .hasMessageContaining("119")
    }

    @Test
    fun `exactly one message's worth is enough`() {
        // The boundary matters: off by one here either wastes the last message or sends it twice
        // over, and the gateway's own refusal is the only other check.
        val guard = SmsProviderCreditGuard(FakeProviderCredit { known(120) }, props)

        assertThatCode { guard.requireCreditForOneMessage() }.doesNotThrowAnyException()
    }

    @Test
    fun `a low balance warns but still sends`() {
        // Below warn-minor, above one message: there is time to top up and no reason to lose alerts
        // in the meantime.
        val guard = SmsProviderCreditGuard(FakeProviderCredit { known(360) }, props)

        assertThatCode { guard.requireCreditForOneMessage() }.doesNotThrowAnyException()
    }

    @Test
    fun `an unmetered account is never blocked`() {
        val guard = SmsProviderCreditGuard(FakeProviderCredit { ProviderCredit.Unmetered("Postpaid") }, props)

        assertThatCode { guard.requireCreditForOneMessage() }.doesNotThrowAnyException()
    }

    @Test
    fun `an unreadable balance never blocks an alert`() {
        // Rule 1. A broken credit endpoint is our problem, not the hunter's, and the send itself is
        // the real test. Blocking here would turn a monitoring failure into a delivery failure.
        val provider = FakeProviderCredit { throw ResourceAccessException("gosms unreachable") }
        val guard = SmsProviderCreditGuard(provider, props)

        assertThatCode { guard.requireCreditForOneMessage() }.doesNotThrowAnyException()
        assertThat(provider.reads).isEqualTo(1)
    }

    @Test
    fun `a response we cannot parse never blocks either`() {
        val guard = SmsProviderCreditGuard(FakeProviderCredit { ProviderCredit.Unknown("shape changed") }, props)

        assertThatCode { guard.requireCreditForOneMessage() }.doesNotThrowAnyException()
    }

    @Test
    fun `guards nothing when the provider has no balance to report`() {
        // sms.provider=logging or twilio: no SmsProviderCredit bean, so no read and no blocking.
        val guard = SmsProviderCreditGuard(provider = null, props = props)

        assertThatCode { guard.requireCreditForOneMessage() }.doesNotThrowAnyException()
        assertThat(guard.inspect()).isNull()
    }

    @Test
    fun `a healthy balance is cached, so the guard costs one read per ttl and not one per alert`() {
        val provider = FakeProviderCredit { known(31521) }
        val guard = SmsProviderCreditGuard(provider, props)

        repeat(5) { guard.requireCreditForOneMessage() }

        assertThat(provider.reads).isEqualTo(1)
    }

    @Test
    fun `a blocking balance is never cached, so a top-up takes effect at once`() {
        // Rule 2, and the reason it exists: cached bad news would keep alerts failing for up to the
        // whole ttl after the account was topped up.
        val provider = FakeProviderCredit { known(0) }
        val guard = SmsProviderCreditGuard(provider, props)

        assertThatThrownBy { guard.requireCreditForOneMessage() }
            .isInstanceOf(SmsProviderOutOfCreditException::class.java)

        provider.nowAnswers { known(20000) }

        assertThatCode { guard.requireCreditForOneMessage() }.doesNotThrowAnyException()
        assertThat(provider.reads).isEqualTo(2)
    }

    @Test
    fun `an unreadable balance is cached too, so a gateway outage is not asked once per alert`() {
        val provider = FakeProviderCredit { ProviderCredit.Unknown("down") }
        val guard = SmsProviderCreditGuard(provider, props)

        repeat(3) { guard.requireCreditForOneMessage() }

        assertThat(provider.reads).isEqualTo(1)
    }

    @Test
    fun `inspect reads fresh, because an operator asking is asking about now`() {
        val provider = FakeProviderCredit { known(31521) }
        val guard = SmsProviderCreditGuard(provider, props)

        guard.requireCreditForOneMessage()
        val inspected = guard.inspect()

        assertThat(inspected).isEqualTo(known(31521))
        assertThat(provider.reads).isEqualTo(2)
    }

    @Test
    fun `a stale cache is re-read`() {
        val provider = FakeProviderCredit { known(31521) }
        val guard = SmsProviderCreditGuard(provider, props.copy(ttl = Duration.ZERO))

        guard.requireCreditForOneMessage()
        guard.requireCreditForOneMessage()

        assertThat(provider.reads).isEqualTo(2)
    }

    @Test
    fun `messages left is what an operator actually wants to know`() {
        val guard = SmsProviderCreditGuard(FakeProviderCredit { known(31521) }, props)

        // 315.21 CZK at 1.20 a message. Rounded down: the part-message is not sendable.
        assertThat(guard.messagesLeft(known(31521))).isEqualTo(262)
    }
}
