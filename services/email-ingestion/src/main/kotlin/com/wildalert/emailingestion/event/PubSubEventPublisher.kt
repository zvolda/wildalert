package com.wildalert.emailingestion.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.cloud.spring.pubsub.core.PubSubOperations
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Real EventPublisher that sends events to Cloud Pub/Sub as JSON. Active only when
 * events.provider=pubsub, so the fake logging publisher is used until a broker is configured.
 *
 * Depends on [PubSubOperations] rather than the concrete `PubSubTemplate` so unit tests can mock it
 * without a GCP client, credentials or an emulator.
 *
 * **Blocking on purpose.** The old Kafka publisher was fire-and-forget, so the webhook could answer
 * 200 for an event that was never published — a silently lost photo. Here we wait for Pub/Sub to
 * confirm and let failures propagate, which fails the webhook instead. The trade-off is that the
 * email provider then retries and we get a second ImageReceived with a *new* eventId, which
 * notification's idempotency (keyed on sourceEventId) will not dedupe. See ROADMAP.
 *
 * No message key / ordering key: Kafka's key only bought partition affinity, and Pub/Sub ordering
 * keys would reintroduce head-of-line blocking on failures. See ROADMAP.
 */
@Component
@ConditionalOnProperty(name = ["events.provider"], havingValue = "pubsub")
class PubSubEventPublisher(
    private val pubSub: PubSubOperations,
    private val objectMapper: ObjectMapper,
    @Value("\${events.topic.image-received}") private val topic: String,
    @Value("\${events.publish-timeout-seconds:5}") private val publishTimeoutSeconds: Long,
) : EventPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: ImageReceived) {
        val payload = objectMapper.writeValueAsString(event)
        // get() throws if the publish failed or timed out; the caller turns that into a 5xx.
        val messageId = pubSub.publish(topic, payload).get(publishTimeoutSeconds, TimeUnit.SECONDS)
        log.info(
            "Published ImageReceived id={} to topic {} (messageId={})",
            event.eventId, topic, messageId,
        )
    }
}
