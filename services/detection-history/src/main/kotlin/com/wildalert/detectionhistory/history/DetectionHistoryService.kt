package com.wildalert.detectionhistory.history

import com.wildalert.detectionhistory.event.AnimalRecognized
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Turns recognition results into history, and answers the dashboard's questions about it.
 *
 * Transport-free on purpose, like notification's equivalent: it takes an event, not an HTTP
 * request, which is what made swapping Kafka for Pub/Sub a small change and what lets the event
 * path be tested without a web layer.
 */
@Service
class DetectionHistoryService(
    private val store: DetectionStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Records one recognition result. Safe to call twice with the same event: the second call is a
     * no-op, because [DetectionStore.record] keys on `sourceEventId`.
     *
     * Throws [UnattributableDetectionException] when the event names no owner at all, which no
     * amount of retrying will fix — the push adapter turns that into a dead-letter rather than an
     * endless redelivery.
     */
    fun record(event: AnimalRecognized) {
        val recipient = event.recipient?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (recipient == null && event.hunterId == null) {
            throw UnattributableDetectionException(
                "AnimalRecognized ${event.eventId} has neither a recipient nor a hunterId"
            )
        }

        val stored = store.record(
            Detection(
                sourceEventId = event.sourceEventId,
                recipient = recipient,
                hunterId = event.hunterId,
                storageKey = event.storageKey,
                species = event.species,
                confidence = event.confidence,
                lowConfidence = event.lowConfidence,
                occurredAt = event.occurredAt,
            )
        )

        if (stored) {
            log.info(
                "Recorded {} (source={} confidence={} lowConfidence={})",
                event.species, event.sourceEventId, event.confidence, event.lowConfidence,
            )
        } else {
            // Expected, not a problem: at-least-once delivery means this is how a redelivery
            // looks. Logged at info because seeing it often is worth noticing.
            log.info("Detection for source={} already recorded; ignoring", event.sourceEventId)
        }
    }

    /** One hunter's detections, newest sighting first. */
    fun history(recipient: String, species: String?, limit: Int, offset: Int): List<Detection> =
        store.findByRecipient(normalise(recipient), species, limit, offset)

    /** Per-species counts for one hunter since [since]. */
    fun summary(recipient: String, since: Instant): List<SpeciesSummary> =
        store.speciesSummary(normalise(recipient), since)

    // Writes are normalised on the way in, so reads have to be normalised the same way or a
    // hunter typing their address with a capital letter sees an empty history.
    private fun normalise(recipient: String) = recipient.trim().lowercase()
}

/** The event names no hunter, so the detection can never be attributed. Permanent, not transient. */
class UnattributableDetectionException(message: String) : RuntimeException(message)
