package com.wildalert.notification.sms

/** A single SMS to send. */
data class SmsMessage(
    val to: String,
    val body: String,
)

/** Outcome of a send — carries the provider's message id so we can track it later. */
data class SmsResult(
    val messageId: String,
)

/**
 * Abstraction over the SMS provider. Everything talks to this interface, so we can swap
 * the fake logging sender for Twilio (or Plivo/Vonage) without touching callers.
 */
interface SmsSender {
    fun send(message: SmsMessage): SmsResult
}
