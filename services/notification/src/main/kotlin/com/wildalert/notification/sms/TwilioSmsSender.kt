package com.wildalert.notification.sms

import com.twilio.Twilio
import com.twilio.rest.api.v2010.account.Message
import com.twilio.type.PhoneNumber
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Real SmsSender backed by Twilio. Active only when sms.provider=twilio, so the fake
 * sender is used until credentials are configured.
 */
@Component
@ConditionalOnProperty(name = ["sms.provider"], havingValue = "twilio")
class TwilioSmsSender(
    private val props: TwilioProperties,
) : SmsSender {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        Twilio.init(props.accountSid, props.authToken)
        log.info("Twilio SMS sender initialised (from={})", props.senderId)
    }

    override fun send(message: SmsMessage): SmsResult {
        val sent = Message.creator(
            PhoneNumber(message.to),        // to
            PhoneNumber(props.senderId),    // from (number or alphanumeric sender id)
            message.body,
        ).create()
        return SmsResult(messageId = sent.sid)
    }
}
