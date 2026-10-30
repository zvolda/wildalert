package com.wildalert.notification.sms

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Settings for guarding our own gateway credit, bound from `sms.provider-credit.*`.
 *
 * Deliberately provider-agnostic: the numbers describe *what a message costs us* and *when to start
 * worrying*, which is the same question whichever gateway sends it. Only the reading of the balance
 * is provider-specific (`GoSmsSender`).
 */
@ConfigurationProperties(prefix = "sms.provider-credit")
data class SmsProviderCreditProperties(
    /**
     * What one message costs **us** at the gateway, in haléře. Not to be confused with
     * `billing.sms-cost-minor`, which is what we charge the hunter (150, so an alert covers its own
     * cost with a little margin). 120 is the real price on the `GoSMS-test` channel.
     */
    val messagePriceMinor: Long = 120,

    /**
     * Log a warning while the balance is at or below this, in haléře. 2000 = 20 CZK ≈ 16 messages,
     * which is enough notice to top up before anyone misses an alert.
     */
    val warnMinor: Long = 2000,

    /**
     * How long a healthy balance is trusted before it is read again. A balance moves by one message
     * price per alert, so at this project's volume a few minutes of staleness cannot be wrong by
     * much — and the gateway's own refusal is the backstop if it is. Keeping it this long is what
     * makes the guard nearly free: one extra call every [ttl], not one per alert.
     *
     * A *blocking* balance is never cached, whatever this says — see the guard.
     */
    val ttl: Duration = Duration.ofMinutes(5),
)
