package com.wildalert.detectionhistory.history

import com.wildalert.detectionhistory.event.AnimalRecognized
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DetectionHistoryServiceTest {

    private val store = InMemoryDetectionStore()
    private val service = DetectionHistoryService(store)

    private fun event(
        recipient: String? = "7f3k9qabcdef@wildalert.app",
        hunterId: UUID? = null,
        species: String = "wild boar",
        sourceEventId: UUID = UUID.randomUUID(),
    ) = AnimalRecognized(
        eventId = UUID.randomUUID(),
        sourceEventId = sourceEventId,
        recipient = recipient,
        hunterId = hunterId,
        storageKey = "inbound/2026/10/20/$sourceEventId.jpg",
        species = species,
        confidence = 0.97,
        lowConfidence = false,
        occurredAt = Instant.parse("2026-10-20T22:14:00Z"),
    )

    @Test
    fun `records a recognised animal as history`() {
        service.record(event(species = "wild boar"))

        val history = service.history("7f3k9qabcdef@wildalert.app", null, 10, 0)

        assertThat(history).hasSize(1)
        assertThat(history.single().species).isEqualTo("wild boar")
        assertThat(history.single().occurredAt).isEqualTo(Instant.parse("2026-10-20T22:14:00Z"))
    }

    /** At-least-once delivery: the same event arriving twice must not become two sightings. */
    @Test
    fun `a redelivered event does not create a second row`() {
        val sourceEventId = UUID.randomUUID()

        service.record(event(sourceEventId = sourceEventId))
        service.record(event(sourceEventId = sourceEventId))

        assertThat(service.history("7f3k9qabcdef@wildalert.app", null, 10, 0)).hasSize(1)
    }

    /**
     * The address is normalised on write and on read, so a hunter whose address reaches us with
     * different casing or stray whitespace still sees one history rather than two empty ones.
     */
    @Test
    fun `the recipient is matched regardless of case or surrounding space`() {
        service.record(event(recipient = "  7F3K9QABCDEF@WildAlert.App  "))

        assertThat(service.history("7f3k9qabcdef@wildalert.app", null, 10, 0)).hasSize(1)
        assertThat(service.history("  7F3K9QAbcdef@WILDALERT.app ", null, 10, 0)).hasSize(1)
    }

    /**
     * A legacy event carrying only a hunter id is still stored rather than dead-lettered. It will
     * not show up in a recipient-keyed read, which is the documented trade-off: keeping the row
     * costs nothing, and resolving the id back to an address would need the user-account call this
     * service is built without.
     */
    @Test
    fun `an event with only a hunter id is stored`() {
        val hunterId = UUID.randomUUID()

        service.record(event(recipient = null, hunterId = hunterId))

        assertThat(service.history("7f3k9qabcdef@wildalert.app", null, 10, 0)).isEmpty()
    }

    /** No owner at all is permanent, not transient: the push adapter turns this into a 400. */
    @Test
    fun `an event with neither recipient nor hunter id is rejected`() {
        assertThatThrownBy { service.record(event(recipient = null, hunterId = null)) }
            .isInstanceOf(UnattributableDetectionException::class.java)
    }

    @Test
    fun `a blank recipient counts as no recipient`() {
        assertThatThrownBy { service.record(event(recipient = "   ", hunterId = null)) }
            .isInstanceOf(UnattributableDetectionException::class.java)
    }

    @Test
    fun `summarises a hunter's species`() {
        service.record(event(species = "wild boar"))
        service.record(event(species = "wild boar"))
        service.record(event(species = "roe deer"))

        val summary = service.summary("7f3k9qabcdef@wildalert.app", Instant.parse("2026-10-01T00:00:00Z"))

        assertThat(summary.map { it.species to it.sightings })
            .containsExactly("wild boar" to 2, "roe deer" to 1)
    }
}
