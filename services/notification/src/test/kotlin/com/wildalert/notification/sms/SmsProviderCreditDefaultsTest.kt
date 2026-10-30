package com.wildalert.notification.sms

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration

/**
 * Pins what `application.yml` ships for the gateway-credit guard — no `properties` override, the
 * sibling of [com.wildalert.notification.alert.SmsCostDefaultTest].
 *
 * These numbers decide when alerts stop rather than how much they cost, so a stray edit is worth a
 * red build: too high a price blocks sends the account could afford, too low and the guard never
 * fires at all.
 */
@SpringBootTest
class SmsProviderCreditDefaultsTest {

    @Value("\${sms.provider-credit.message-price-minor}")
    private var messagePriceMinor: Long = 0

    @Value("\${sms.provider-credit.warn-minor}")
    private var warnMinor: Long = 0

    @Value("\${sms.provider-credit.ttl}")
    private lateinit var ttl: Duration

    @Value("\${billing.sms-cost-minor}")
    private var smsCostMinor: Long = 0

    @Test
    fun `a message costs us 1_20 CZK by default, the real GoSMS-test channel price`() {
        assertThat(messagePriceMinor).isEqualTo(120)
    }

    @Test
    fun `we charge the hunter more than the gateway charges us`() {
        // The two prices read from the same config, so this invariant is checked against what is
        // actually shipped rather than against a number copied into a comment.
        assertThat(smsCostMinor).isGreaterThan(messagePriceMinor)
    }

    @Test
    fun `the warning leaves room to act`() {
        // At least a few messages of notice, or the warning arrives with the outage.
        assertThat(warnMinor).isGreaterThanOrEqualTo(messagePriceMinor * 5)
    }

    @Test
    fun `the balance is re-read often enough to be useful and rarely enough to be free`() {
        assertThat(ttl).isEqualTo(Duration.ofMinutes(5))
    }
}
