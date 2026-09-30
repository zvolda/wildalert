package com.wildalert.notification.event

import org.apache.kafka.common.TopicPartition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff
import org.springframework.web.client.HttpClientErrorException

/** `animal.recognized` → `animal.recognized.dlt`; the same naming the Python worker uses. */
object DeadLetterTopics {
    const val SUFFIX = ".dlt"

    fun forTopic(topic: String): String = topic + SUFFIX
}

/**
 * What happens when handling an event throws (user-account down, SMS provider failing).
 *
 * Spring Kafka's default is to retry ten times as fast as it can and then log and move on — the
 * hunter's alert is silently lost. Instead: retry a few times with a pause, then park the message
 * in `<topic>.dlt` with the failure recorded in its headers, and commit so the partition keeps
 * moving. Nothing is lost and one broken message can't block everyone else's alerts.
 *
 * Active only when events.provider=kafka, like the listener itself.
 */
@Configuration
@ConditionalOnProperty(name = ["events.provider"], havingValue = "kafka")
class KafkaErrorHandlingConfig {

    @Bean
    fun errorHandler(template: KafkaTemplate<Any, Any>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(template) { record, _ ->
            // Partition -1 lets the producer choose, so the dead-letter topic doesn't need the
            // same partition count as the original.
            TopicPartition(DeadLetterTopics.forTopic(record.topic()), -1)
        }
        // 3 attempts in total, 2s apart — matches the Python worker's policy.
        val handler = DefaultErrorHandler(recoverer, FixedBackOff(2_000L, 2L))
        // A 4xx from user-account (other than the 404 the client already handles) won't fix itself.
        handler.addNotRetryableExceptions(HttpClientErrorException::class.java)
        return handler
    }
}
