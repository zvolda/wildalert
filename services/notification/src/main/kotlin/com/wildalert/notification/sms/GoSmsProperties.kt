package com.wildalert.notification.sms

import org.springframework.boot.context.properties.ConfigurationProperties

/** GoSMS settings, bound from `sms.gosms.*` (fed by env vars / .env). */
@ConfigurationProperties(prefix = "sms.gosms")
data class GoSmsProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    // Numeric id of the sending channel, from the GoSMS admin panel. Which channel you pick is
    // what decides the sender shown on the phone (and the per-message price).
    val channel: Int = 0,
    // Overridable because GoSMS answers on both app.gosms.eu and app.gosms.cz.
    val baseUrl: String = "https://app.gosms.eu",
)
