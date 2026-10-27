package com.wildalert.useraccount.credit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

/**
 * One movement of credit. Append-only: nothing in `credit_ledger` is ever updated or deleted, so
 * the table is the source of truth that [HunterCredit.balanceMinor] is checked against.
 *
 * Maps to `credit_ledger` (V4__create_credit_tables.sql).
 *
 * There are no setters and no mutable fields on purpose — a ledger whose rows can change is not a
 * ledger. Corrections are made by appending an [EntryType.ADJUSTMENT] or [EntryType.REVERSAL].
 */
@Entity
@Table(name = "credit_ledger")
class CreditLedgerEntry(
    @Column(name = "hunter_id", nullable = false, updatable = false)
    val hunterId: UUID,

    /** Positive adds credit (a grant, a top-up); negative spends it. Never zero in practice. */
    @Column(name = "amount_minor", nullable = false, updatable = false)
    val amountMinor: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 32)
    val entryType: EntryType,

    /**
     * What caused this entry, and the whole idempotency mechanism: an event's `sourceEventId` for
     * a debit, the Stripe payment-intent id for a top-up. A partial unique index on
     * `(entry_type, external_ref)` makes applying the same cause twice physically impossible.
     *
     * Null for a grant or a manual adjustment, which have no external cause — hence *partial*,
     * so many nulls can coexist.
     */
    @Column(name = "external_ref", updatable = false, length = 255)
    val externalRef: String? = null,

    /** The balance after this entry, so a statement reads without a window function. */
    @Column(name = "balance_after_minor", nullable = false, updatable = false)
    val balanceAfterMinor: Long,
) {
    @Id
    @Column(nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID()

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @Column(nullable = false, updatable = false, length = 3)
    val currency: String = "CZK"
}
