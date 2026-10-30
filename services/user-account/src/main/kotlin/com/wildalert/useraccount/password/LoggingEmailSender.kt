package com.wildalert.useraccount.password

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Fake EmailSender that logs instead of sending. Active by default (`email.provider=logging` or
 * unset), so the service runs, and the tests pass, with no provider account and no DNS.
 *
 * **It logs the subject and recipient but never the body**, and that asymmetry is the whole point
 * rather than an oversight: the body contains the reset link, and the link *is* the credential.
 * On Cloud Run every log line goes to Cloud Logging, where it is retained and readable by anyone
 * with project log access -- which would make the log a standing list of working password resets.
 * `LoggingSmsSender` logs its body freely because an SMS body is a species name.
 *
 * Local development still needs the link, so it goes out at **debug**, which is off in the cloud
 * and one line in `application.yml` locally. That is a deliberate trade: convenience where the
 * only reader is the developer who started the process, nothing where the logs are durable.
 */
@Component
@ConditionalOnProperty(name = ["email.provider"], havingValue = "logging", matchIfMissing = true)
class LoggingEmailSender : EmailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: EmailMessage) {
        log.info("[FAKE EMAIL] to={} subject=\"{}\" ({} chars, body withheld)",
            message.to, message.subject, message.body.length)
        log.debug("[FAKE EMAIL] body:\n{}", message.body)
    }
}
