package com.wildalert.notification.alert

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest

/**
 * Pins the **default** price of an alert at 150 haléře — no `properties` override here on purpose,
 * so this reads whatever `application.yml` ships.
 *
 * It is the price of the product, not a preference: this number travels to user-account on every
 * charge and decides what a hunter's balance buys. A stray edit that halved it would hand out
 * alerts below the 1.20 we pay GoSMS, and nothing else in the repo would notice.
 *
 * The sibling of user-account's `SignupGrantDefaultTest`, and the opposite default for the opposite
 * reason: forgetting the grant must give nothing away, while forgetting the price must still
 * charge. Needs no Postgres — this service's context starts without one.
 */
@SpringBootTest
class SmsCostDefaultTest {

    @Value("\${billing.sms-cost-minor}")
    private var smsCostMinor: Long = 0

    @Test
    fun `an alert costs 1_50 CZK by default`() {
        assertThat(smsCostMinor).isEqualTo(150)
    }

    @Test
    fun `the default covers what we pay GoSMS`() {
        // We are billed 120 haléře per message. A price at or below that would mean every alert
        // loses money, which is a pricing bug rather than a discount.
        assertThat(smsCostMinor).isGreaterThan(120)
    }
}
