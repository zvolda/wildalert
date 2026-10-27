package com.wildalert.useraccount.credit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface HunterCreditRepository : JpaRepository<HunterCredit, UUID>

interface CreditLedgerRepository : JpaRepository<CreditLedgerEntry, UUID> {

    /** A hunter's statement, newest first. Backed by `credit_ledger_hunter_created_at_idx`. */
    fun findByHunterIdOrderByCreatedAtDesc(hunterId: UUID): List<CreditLedgerEntry>

    /**
     * The sum of a hunter's ledger, which `hunter_credit.balance_minor` must always equal.
     * Only used by tests and (later) a reconciliation job — the live read is the cached balance,
     * because re-summing history on every charge is what the cache exists to avoid.
     *
     * Returns 0 rather than null for a hunter with no entries, so callers need no special case.
     */
    @Query("select coalesce(sum(e.amountMinor), 0) from CreditLedgerEntry e where e.hunterId = :hunterId")
    fun sumAmountMinor(hunterId: UUID): Long
}
