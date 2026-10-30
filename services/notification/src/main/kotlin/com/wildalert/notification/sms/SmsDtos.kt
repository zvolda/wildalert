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

/**
 * Body of `GET /api/sms/credit` — our own balance at the gateway.
 *
 * `messagesLeft` is the number an operator actually wants; the haléře are there so the figure can
 * be checked against the GoSMS panel. Everything but `state` is absent when it does not apply.
 */
data class ProviderCreditResponse(
    /** KNOWN, UNMETERED, UNKNOWN or NOT_REPORTED. */
    val state: String,
    val remainingMinor: Long? = null,
    val currency: String? = null,
    val messagesLeft: Long? = null,
    val detail: String? = null,
)
