package com.wildalert.detectionhistory.history

import java.time.Instant

/**
 * Where detections are kept. Two implementations, chosen by `history.store`, following the
 * repo-wide pattern: [InMemoryDetectionStore] is the fake default so the service runs and tests
 * with no infrastructure, [JooqDetectionStore] is the real one.
 *
 * The fake is not just a stub here — it has to reproduce the real store's ordering, filtering and
 * duplicate handling, because the service tests are written against this interface. That is the
 * cost of the pattern, and the reason the two are tested with the same expectations.
 */
interface DetectionStore {

    /**
     * Stores a detection. Returns false if its `sourceEventId` was already stored, which is how a
     * Pub/Sub redelivery is absorbed — recording is idempotent, so a duplicate is a no-op rather
     * than a second row or an error.
     */
    fun record(detection: Detection): Boolean

    /**
     * One hunter's detections, newest sighting first. [species] narrows to a single species when
     * given. Paged, because a busy camera produces a few hundred rows a month and the dashboard
     * should not fetch them all.
     */
    fun findByRecipient(recipient: String, species: String?, limit: Int, offset: Int): List<Detection>

    /**
     * Per-species counts for one hunter since [since], most sightings first. The reporting query
     * the dashboard's overview is built on.
     */
    fun speciesSummary(recipient: String, since: Instant): List<SpeciesSummary>
}
