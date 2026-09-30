package com.wildalert.emailingestion.event

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Fake EventPublisher that just logs instead of publishing to a broker. Active by default
 * (events.provider=logging or unset), so the app runs end-to-end with no Kafka.
 */
@Component
@ConditionalOnProperty(name = ["events.provider"], havingValue = "logging", matchIfMissing = true)
class LoggingEventPublisher : EventPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: ImageReceived) {
        log.info(
            "[FAKE EVENT] ImageReceived id={} key={} contentType={} from={} hunterId={}",
            event.eventId, event.storageKey, event.contentType, event.senderEmail, event.hunterId,
        )
    }
}
