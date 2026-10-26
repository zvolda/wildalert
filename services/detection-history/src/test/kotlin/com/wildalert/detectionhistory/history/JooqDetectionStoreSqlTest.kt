package com.wildalert.detectionhistory.history

import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Checks the SQL jOOQ actually renders for Postgres — with no database anywhere.
 *
 * A DSLContext built from a dialect alone can render a query but not execute it, which is exactly
 * what is wanted here: the things that quietly break a reporting endpoint are the ones visible in
 * the SQL text (lost schema qualification, an ORDER BY on the wrong column, a GROUP BY that
 * disappeared, an INSERT that no longer tolerates a duplicate). Those are checked here and run on
 * this machine without WSL2 or a container.
 *
 * It does NOT prove the SQL is valid Postgres or returns the right rows — only a real database
 * does that, which is why [InMemoryDetectionStoreTest] pins the behaviour the store has to produce
 * and this one pins the shape of the statements.
 */
class JooqDetectionStoreSqlTest {

    // Dialect only: no connection, no DataSource, nothing to start.
    private val store = JooqDetectionStore(DSL.using(SQLDialect.POSTGRES))

    private val recipient = "7f3k9qabcdef@wildalert.app"

    @Test
    fun `recording tolerates a duplicate instead of failing`() {
        val sql = store.recordQuery(
            Detection(
                sourceEventId = UUID.randomUUID(),
                recipient = recipient,
                hunterId = null,
                storageKey = "inbound/x.jpg",
                species = "wild boar",
                confidence = 0.97,
                lowConfidence = false,
                occurredAt = Instant.parse("2026-10-20T22:14:00Z"),
            )
        ).getSQL(ParamType.INLINED)

        assertThat(sql)
            .contains("insert into \"detection_history\".\"detection\"")
            // The whole idempotency mechanism. Without it a redelivery raises a duplicate-key
            // error, the push returns 500, and Pub/Sub retries it forever until it dead-letters.
            .contains("on conflict do nothing")
    }

    @Test
    fun `history is filtered by recipient and ordered by when the animal was seen`() {
        val sql = store.findByRecipientQuery(recipient, null, 50, 0).getSQL(ParamType.INLINED)

        assertThat(sql)
            .contains("from \"detection_history\".\"detection\"")
            .contains("\"recipient\" = '$recipient'")
            // occurred_at, never recorded_at -- a replayed event was stored long after the animal
            // walked past, and the hunter's history follows the animal's clock.
            .contains("order by \"detection_history\".\"detection\".\"occurred_at\" desc")
            // jOOQ renders paging as the SQL standard OFFSET/FETCH rather than Postgres's LIMIT.
            // Both work on Postgres; this assertion exists to pin which one we actually send.
            .contains("fetch next 50 rows only")
    }

    @Test
    fun `the species filter is applied when given and absent when not`() {
        val filtered = store.findByRecipientQuery(recipient, "roe deer", 50, 0).getSQL(ParamType.INLINED)
        val unfiltered = store.findByRecipientQuery(recipient, null, 50, 0).getSQL(ParamType.INLINED)

        assertThat(filtered).contains("\"species\" = 'roe deer'")
        // noCondition() leaves no trace at all -- not even a 1 = 1 for the planner to think about.
        assertThat(unfiltered).doesNotContain("\"species\" =")
    }

    @Test
    fun `paging offsets the window`() {
        val sql = store.findByRecipientQuery(recipient, null, 20, 40).getSQL(ParamType.INLINED)

        assertThat(sql).contains("offset 40 rows fetch next 20 rows only")
    }

    @Test
    fun `the summary aggregates in the database, not in Kotlin`() {
        val sql = store
            .speciesSummaryQuery(recipient, Instant.parse("2026-10-01T00:00:00Z"))
            .getSQL(ParamType.INLINED)

        assertThat(sql)
            .contains("count(*)")
            .contains("max(\"detection_history\".\"detection\".\"occurred_at\")")
            .contains("avg(\"detection_history\".\"detection\".\"confidence\")")
            .contains("group by \"detection_history\".\"detection\".\"species\"")
            // Species breaks the tie so equal counts keep a stable order between requests.
            .contains("order by")
            .contains("\"sightings\" desc")
    }

    @Test
    fun `the summary window reaches the query`() {
        val sql = store
            .speciesSummaryQuery(recipient, Instant.parse("2026-10-01T00:00:00Z"))
            .getSQL(ParamType.INLINED)

        assertThat(sql).contains("\"occurred_at\" >=")
        assertThat(sql).contains("2026-10-01")
    }
}
