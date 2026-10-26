package com.wildalert.detectionhistory.history

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The fake store is what every other test in this module runs against, so its ordering, filtering
 * and duplicate handling have to match the real one's. These are the expectations
 * [JooqDetectionStore] is written to satisfy too — its SQL is checked separately in
 * [JooqDetectionStoreSqlTest], because executing it needs a database.
 */
class InMemoryDetectionStoreTest {

    private val store = InMemoryDetectionStore()
    private val recipient = "7f3k9qabcdef@wildalert.app"

    private fun detection(
        species: String = "wild boar",
        occurredAt: Instant = Instant.parse("2026-10-20T22:00:00Z"),
        confidence: Double = 0.95,
        recipient: String? = this.recipient,
        sourceEventId: UUID = UUID.randomUUID(),
    ) = Detection(
        sourceEventId = sourceEventId,
        recipient = recipient,
        hunterId = null,
        storageKey = "inbound/${sourceEventId}.jpg",
        species = species,
        confidence = confidence,
        lowConfidence = confidence < 0.7,
        occurredAt = occurredAt,
    )

    @Test
    fun `records a detection once and ignores a redelivery of the same event`() {
        val sourceEventId = UUID.randomUUID()

        assertThat(store.record(detection(sourceEventId = sourceEventId))).isTrue()
        assertThat(store.record(detection(sourceEventId = sourceEventId))).isFalse()

        assertThat(store.findByRecipient(recipient, null, 10, 0)).hasSize(1)
    }

    @Test
    fun `returns one hunter's detections newest sighting first`() {
        store.record(detection(species = "roe deer", occurredAt = Instant.parse("2026-10-18T03:00:00Z")))
        store.record(detection(species = "wild boar", occurredAt = Instant.parse("2026-10-20T22:00:00Z")))
        store.record(detection(species = "red fox", occurredAt = Instant.parse("2026-10-19T01:00:00Z")))

        val history = store.findByRecipient(recipient, null, 10, 0)

        assertThat(history.map { it.species }).containsExactly("wild boar", "red fox", "roe deer")
    }

    @Test
    fun `another hunter's detections are never returned`() {
        store.record(detection(recipient = "someone-else@wildalert.app"))
        store.record(detection())

        val history = store.findByRecipient(recipient, null, 10, 0)

        assertThat(history).hasSize(1)
        assertThat(history.single().recipient).isEqualTo(recipient)
    }

    @Test
    fun `filters to one species`() {
        store.record(detection(species = "wild boar"))
        store.record(detection(species = "roe deer"))

        assertThat(store.findByRecipient(recipient, "roe deer", 10, 0).map { it.species })
            .containsExactly("roe deer")
    }

    @Test
    fun `pages through the history`() {
        (1..5).forEach { day ->
            store.record(detection(species = "day $day", occurredAt = Instant.parse("2026-10-0${day}T00:00:00Z")))
        }

        val firstPage = store.findByRecipient(recipient, null, 2, 0)
        val secondPage = store.findByRecipient(recipient, null, 2, 2)

        assertThat(firstPage.map { it.species }).containsExactly("day 5", "day 4")
        assertThat(secondPage.map { it.species }).containsExactly("day 3", "day 2")
    }

    @Test
    fun `summarises sightings per species, most seen first`() {
        // Confidences chosen to be exactly representable as doubles, so the averages can be
        // asserted with == instead of a tolerance.
        store.record(detection(species = "wild boar", occurredAt = Instant.parse("2026-10-18T22:00:00Z"), confidence = 0.5))
        store.record(detection(species = "wild boar", occurredAt = Instant.parse("2026-10-20T22:00:00Z"), confidence = 1.0))
        store.record(detection(species = "roe deer", occurredAt = Instant.parse("2026-10-19T22:00:00Z"), confidence = 0.25))

        val summary = store.speciesSummary(recipient, Instant.parse("2026-10-01T00:00:00Z"))

        assertThat(summary).containsExactly(
            SpeciesSummary(
                species = "wild boar",
                sightings = 2,
                lastSeenAt = Instant.parse("2026-10-20T22:00:00Z"),
                averageConfidence = 0.75,
            ),
            SpeciesSummary(
                species = "roe deer",
                sightings = 1,
                lastSeenAt = Instant.parse("2026-10-19T22:00:00Z"),
                averageConfidence = 0.25,
            ),
        )
    }

    @Test
    fun `the summary window excludes older sightings`() {
        store.record(detection(species = "wild boar", occurredAt = Instant.parse("2026-09-01T22:00:00Z")))
        store.record(detection(species = "roe deer", occurredAt = Instant.parse("2026-10-20T22:00:00Z")))

        val summary = store.speciesSummary(recipient, Instant.parse("2026-10-01T00:00:00Z"))

        assertThat(summary.map { it.species }).containsExactly("roe deer")
    }

    /** Two replicas handed the same redelivered event must not both store it. */
    @Test
    fun `only one of many concurrent records of the same event succeeds`() {
        val sourceEventId = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(
                (1..8).map { Callable { store.record(detection(sourceEventId = sourceEventId)) } }
            )

            assertThat(results.count { it.get() }).isEqualTo(1)
        } finally {
            pool.shutdown()
        }
    }
}
