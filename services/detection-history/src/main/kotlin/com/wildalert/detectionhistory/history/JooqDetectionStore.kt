package com.wildalert.detectionhistory.history

// The generated table, imported as the static field rather than the class: the generated table
// class is also called Detection and would collide with this package's domain type.
import com.wildalert.detectionhistory.jooq.Tables.DETECTION
import com.wildalert.detectionhistory.jooq.tables.records.DetectionRecord
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.ResultQuery
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset

/**
 * The real DetectionStore: typed SQL over Postgres with jOOQ. Active only when
 * history.store=postgres.
 *
 * The tables referenced here (`DETECTION` and its columns) are generated from the Flyway
 * migrations at build time — see build.gradle.kts. That is what jOOQ is here for: rename a column
 * in the SQL and this file stops compiling, instead of failing on the first request after a
 * deploy.
 *
 * Each query is built by its own `...Query` function and executed by the public method. The split
 * is not ceremony: a jOOQ query object can render its SQL with no connection at all, so the shape
 * of these queries is unit-testable on this machine without starting Postgres (see
 * JooqDetectionStoreSqlTest). Executing still needs a real database.
 */
@Component
@ConditionalOnProperty(name = ["history.store"], havingValue = "postgres")
class JooqDetectionStore(
    private val dsl: DSLContext,
) : DetectionStore {

    override fun record(detection: Detection): Boolean = recordQuery(detection).execute() == 1

    override fun findByRecipient(
        recipient: String,
        species: String?,
        limit: Int,
        offset: Int,
    ): List<Detection> =
        findByRecipientQuery(recipient, species, limit, offset).fetch().map { it.toDetection() }

    override fun speciesSummary(recipient: String, since: Instant): List<SpeciesSummary> =
        speciesSummaryQuery(recipient, since).fetch().map { row ->
            SpeciesSummary(
                species = row[DETECTION.SPECIES],
                sightings = row[SIGHTINGS],
                // max() over a non-null column cannot be null for a group that exists, but jOOQ
                // types every aggregate as nullable, so the compiler asks.
                lastSeenAt = row[LAST_SEEN_AT]!!.toInstant(),
                averageConfidence = row[AVERAGE_CONFIDENCE]!!.toDouble(),
            )
        }

    /**
     * `on conflict do nothing` makes recording a single atomic statement: a redelivery, or two
     * replicas handed the same event, both end with exactly one row and no exception. There is no
     * read-then-write race to lose.
     */
    internal fun recordQuery(detection: Detection) =
        dsl.insertInto(DETECTION)
            .set(DETECTION.SOURCE_EVENT_ID, detection.sourceEventId)
            .set(DETECTION.RECIPIENT, detection.recipient)
            .set(DETECTION.HUNTER_ID, detection.hunterId)
            .set(DETECTION.STORAGE_KEY, detection.storageKey)
            .set(DETECTION.SPECIES, detection.species)
            .set(DETECTION.CONFIDENCE, detection.confidence)
            .set(DETECTION.LOW_CONFIDENCE, detection.lowConfidence)
            .set(DETECTION.OCCURRED_AT, detection.occurredAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()

    /**
     * Ordered by `occurred_at`, never `recorded_at`: a replayed event is stored long after the
     * animal walked past, and the hunter's history is about the animal's clock, not ours. Matches
     * the (recipient, occurred_at desc) index.
     *
     * `noCondition()` is how jOOQ expresses an absent filter without building the query twice or
     * smuggling in a `1 = 1`.
     */
    internal fun findByRecipientQuery(
        recipient: String,
        species: String?,
        limit: Int,
        offset: Int,
    ): ResultQuery<DetectionRecord> =
        dsl.selectFrom(DETECTION)
            .where(DETECTION.RECIPIENT.eq(recipient))
            .and(species?.let { DETECTION.SPECIES.eq(it) } ?: DSL.noCondition())
            .orderBy(DETECTION.OCCURRED_AT.desc())
            .limit(limit)
            .offset(offset)

    /**
     * The aggregate the dashboard overview is built on. Counting and averaging in the database
     * rather than in Kotlin is the whole point — the alternative ships every row over the wire to
     * compute four numbers from it.
     */
    internal fun speciesSummaryQuery(recipient: String, since: Instant): ResultQuery<out Record> =
        dsl.select(DETECTION.SPECIES, SIGHTINGS, LAST_SEEN_AT, AVERAGE_CONFIDENCE)
            .from(DETECTION)
            .where(DETECTION.RECIPIENT.eq(recipient))
            .and(DETECTION.OCCURRED_AT.ge(since.atOffset(ZoneOffset.UTC)))
            .groupBy(DETECTION.SPECIES)
            // Species name breaks ties so the page is stable: without it, two species with equal
            // counts can swap places between requests and the list appears to flicker.
            .orderBy(SIGHTINGS.desc(), DETECTION.SPECIES.asc())

    private fun DetectionRecord.toDetection() = Detection(
        sourceEventId = this[DETECTION.SOURCE_EVENT_ID],
        recipient = this[DETECTION.RECIPIENT],
        hunterId = this[DETECTION.HUNTER_ID],
        storageKey = this[DETECTION.STORAGE_KEY],
        species = this[DETECTION.SPECIES],
        confidence = this[DETECTION.CONFIDENCE],
        lowConfidence = this[DETECTION.LOW_CONFIDENCE],
        occurredAt = this[DETECTION.OCCURRED_AT].toInstant(),
    )

    companion object {
        // Declared once and used both in select() and when reading the result, so the query and
        // the mapping cannot disagree about a column — the string alias is written exactly once.
        private val SIGHTINGS = DSL.count().`as`("sightings")
        private val LAST_SEEN_AT = DSL.max(DETECTION.OCCURRED_AT).`as`("last_seen_at")
        private val AVERAGE_CONFIDENCE = DSL.avg(DETECTION.CONFIDENCE).`as`("average_confidence")
    }
}
