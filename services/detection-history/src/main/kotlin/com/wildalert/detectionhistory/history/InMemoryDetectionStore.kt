package com.wildalert.detectionhistory.history

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fake DetectionStore kept in memory. Active by default (history.store=memory or unset), so the
 * service runs and its tests pass with no database.
 *
 * Useless in production — the history is lost on every restart and not shared between replicas,
 * which for a service whose entire job is remembering things means production must use Postgres.
 * It exists so the event path can be exercised without infrastructure.
 */
@Component
@ConditionalOnProperty(name = ["history.store"], havingValue = "memory", matchIfMissing = true)
class InMemoryDetectionStore : DetectionStore {

    // Keyed by sourceEventId, which gives duplicate handling for free: putIfAbsent is the
    // equivalent of the real store's `on conflict do nothing`.
    private val detections = ConcurrentHashMap<UUID, Detection>()

    override fun record(detection: Detection): Boolean =
        detections.putIfAbsent(detection.sourceEventId, detection) == null

    override fun findByRecipient(
        recipient: String,
        species: String?,
        limit: Int,
        offset: Int,
    ): List<Detection> =
        detections.values
            .filter { it.recipient == recipient }
            .filter { species == null || it.species == species }
            .sortedByDescending { it.occurredAt }
            .drop(offset)
            .take(limit)

    override fun speciesSummary(recipient: String, since: Instant): List<SpeciesSummary> =
        detections.values
            .filter { it.recipient == recipient && !it.occurredAt.isBefore(since) }
            .groupBy { it.species }
            .map { (species, found) ->
                SpeciesSummary(
                    species = species,
                    sightings = found.size,
                    lastSeenAt = found.maxOf { it.occurredAt },
                    averageConfidence = found.map { it.confidence }.average(),
                )
            }
            // Same tie-break as the SQL: count descending, then species, so the order is stable
            // and the two stores can be held to one set of expectations.
            .sortedWith(compareByDescending<SpeciesSummary> { it.sightings }.thenBy { it.species })
}
