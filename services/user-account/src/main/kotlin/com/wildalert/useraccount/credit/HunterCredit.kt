package com.wildalert.useraccount.credit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/**
 * A hunter's current balance, in CZK minor units (haléře). One row per hunter, and the only
 * mutable row in this feature — [CreditLedgerEntry] is append-only.
 *
 * Maps to `hunter_credit` (V4__create_credit_tables.sql).
 *
 * **The balance is cached, not authoritative.** It must always equal the sum of the hunter's
 * ledger entries; the column exists so a charge is one indexed read and one guarded update rather
 * than a re-sum of history. A reconciliation query comparing the two is a later slice.
 *
 * Deliberately *not* mutated through this entity by the debit path. When debits arrive they use a
 * single guarded `update ... where balance_minor >= :cost`, because a load-mutate-flush through
 * JPA is exactly the read-then-write race this design exists to avoid.
 */
@Entity
@Table(name = "hunter_credit")
class HunterCredit(
    @Id
    @Column(name = "hunter_id", nullable = false, updatable = false)
    val hunterId: UUID,

    @Column(name = "balance_minor", nullable = false)
    var balanceMinor: Long = 0,
) {
    @Column(nullable = false, length = 3)
    var currency: String = "CZK"
        protected set

    /**
     * 0 = nothing said, 1 = low-balance warning appended to an alert, 2 = out-of-credit SMS sent.
     * Unused until the warning slice; here because the column is.
     */
    @Column(name = "warning_level", nullable = false)
    var warningLevel: Short = 0

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set
}
