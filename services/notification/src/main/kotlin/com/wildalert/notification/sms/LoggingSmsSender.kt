package com.wildalert.notification.sms

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Fake SmsSender that just logs instead of sending. Lets us build and test the whole
 * flow with no Twilio account or cost. Replaced by the real Twilio sender in slice 3.
 */
@Component
class LoggingSmsSender : SmsSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: SmsMessage): SmsResult {
        val id = UUID.randomUUID().toString()
        log.info("[FAKE SMS] to={} body=\"{}\" -> messageId={}", message.to, message.body, id)
        return SmsResult(messageId = id)
    }
}
