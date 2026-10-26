package com.wildalert.detectionhistory.history

import java.time.Instant
import java.util.UUID

/**
 * One detection as the API returns it.
 *
 * `hunterId` and `recipient` are deliberately absent: the caller already knows whose history it
 * asked for, and echoing a hunter's personal inbound address back in every row only spreads it
 * further. `storageKey` is the private object key, not a URL — the photo is served later through
 * a presigned URL (Phase 8).
 */
data class DetectionResponse(
    val id: UUID,
    val species: String,
    val confidence: Double,
    val lowConfidence: Boolean,
    val occurredAt: Instant,
    val storageKey: String,
) {
    companion object {
        fun from(detection: Detection) = DetectionResponse(
            // The source event id doubles as the detection's identity: it is the primary key, and
            // it is stable across a redelivery, so a client can use it to deduplicate too.
            id = detection.sourceEventId,
            species = detection.species,
            confidence = detection.confidence,
            lowConfidence = detection.lowConfidence,
            occurredAt = detection.occurredAt,
            storageKey = detection.storageKey,
        )
    }
}

/** One species in the overview: how often, how recently, how sure. */
data class SpeciesSummaryResponse(
    val species: String,
    val sightings: Int,
    val lastSeenAt: Instant,
    val averageConfidence: Double,
) {
    companion object {
        fun from(summary: SpeciesSummary) = SpeciesSummaryResponse(
            species = summary.species,
            sightings = summary.sightings,
            lastSeenAt = summary.lastSeenAt,
            averageConfidence = summary.averageConfidence,
        )
    }
}
