package com.wildalert.notification.sms

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

// Same E.164 rule as the user-account service (e.g. +420123456789).
private const val E164 = "^\\+[1-9]\\d{6,14}$"

/** Body for POST /api/sms/test. */
data class SendSmsRequest(
    @field:NotBlank
    @field:Pattern(regexp = E164, message = "must be E.164 format, e.g. +420123456789")
    val to: String,

    @field:NotBlank
    val body: String,
)
