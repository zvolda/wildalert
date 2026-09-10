package com.wildalert.notification.sms

import org.springframework.boot.context.properties.ConfigurationProperties

/** Twilio settings, bound from `sms.twilio.*` (fed by env vars / .env). */
@ConfigurationProperties(prefix = "sms.twilio")
data class TwilioProperties(
    val accountSid: String = "",
    val authToken: String = "",
    // The "from" — either a Twilio phone number (+1...) or a free EU alphanumeric sender ID (e.g. "WildAlert").
    val senderId: String = "",
)
