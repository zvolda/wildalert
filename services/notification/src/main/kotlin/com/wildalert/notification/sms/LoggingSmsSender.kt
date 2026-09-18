package com.wildalert.notification.sms

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Fake SmsSender that just logs instead of sending. Active by default (sms.provider=logging
 * or unset), so the app runs with no Twilio account or cost.
 */
@Component
@ConditionalOnProperty(name = ["sms.provider"], havingValue = "logging", matchIfMissing = true)
class LoggingSmsSender : SmsSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: SmsMessage): SmsResult {
        val id = UUID.randomUUID().toString()
        log.info("[FAKE SMS] to={} body=\"{}\" -> messageId={}", message.to, message.body, id)
        return SmsResult(messageId = id)
    }
}
