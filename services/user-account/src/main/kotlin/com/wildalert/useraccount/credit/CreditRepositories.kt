package com.wildalert.useraccount.credit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface HunterCreditRepository : JpaRepository<HunterCredit, UUID> {

    /**
     * Takes [cost] from the balance, **only if it is there**. The whole point is that the
     * arithmetic and the sufficiency check happen in one SQL statement: the row-level exclusive
     * lock an `update` takes serialises concurrent charges for the same hunter, so there is no
     * window between deciding and deducting.
     *
     * Never replace this with `findById` → `balance - cost` → `save()`. That is the
     * read-then-write race this exists to avoid, and with two photos a second apart it lets a
     * hunter spend credit they do not have.
     *
     * @return 1 if the balance was debited, 0 if there was not enough (nothing was changed).
     */
    // flushAutomatically: a native statement bypasses the persistence context, so any pending
    // insert -- a credit row from a registration in the same transaction, say -- has to reach the
    // database first or this update matches nothing and reports a false INSUFFICIENT.
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            update hunter_credit
               set balance_minor = balance_minor - :cost,
                   updated_at = now()
             where hunter_id = :hunterId
               and balance_minor >= :cost
        """,
        nativeQuery = true,
    )
    fun debitIfSufficient(@Param("hunterId") hunterId: UUID, @Param("cost") cost: Long): Int

    /**
     * Raises the warning level, and reports whether *this* call is the one that moved it.
     *
     * That is what makes "tell the hunter once per depletion" work without a second table: only
     * the transaction that actually moves the level is told to send the notice, so thirty photos
     * arriving on an empty balance produce one message, not thirty.
     *
     * @return 1 if this call raised the level, 0 if it was already at or above [level].
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            update hunter_credit
               set warning_level = :level,
                   updated_at = now()
             where hunter_id = :hunterId
               and warning_level < :level
        """,
        nativeQuery = true,
    )
    fun raiseWarningLevel(@Param("hunterId") hunterId: UUID, @Param("level") level: Int): Int

    /**
     * The balance, read in native SQL so it bypasses JPA's first-level cache — which
     * [debitIfSufficient] does not update, and which would otherwise return the pre-charge value.
     *
     * Null when the hunter has no credit row at all.
     */
    @Query(
        value = "select balance_minor from hunter_credit where hunter_id = :hunterId",
        nativeQuery = true,
    )
    fun balanceMinorOf(@Param("hunterId") hunterId: UUID): Long?

    /**
     * The warning level, read natively for the same reason as [balanceMinorOf]:
     * [raiseWarningLevel] is a native update, so a cached entity still reports the old value.
     */
    @Query(
        value = "select warning_level from hunter_credit where hunter_id = :hunterId",
        nativeQuery = true,
    )
    fun warningLevelOf(@Param("hunterId") hunterId: UUID): Short?
}

interface CreditLedgerRepository : JpaRepository<CreditLedgerEntry, UUID> {

    /** A hunter's statement, newest first. Backed by `credit_ledger_hunter_created_at_idx`. */
    fun findByHunterIdOrderByCreatedAtDesc(hunterId: UUID): List<CreditLedgerEntry>

    /**
     * Has this cause already been applied? Backed by `credit_ledger_external_ref_uk`, the same
     * partial unique index that makes applying it twice impossible.
     *
     * This is a **fast path, not the guarantee**: it answers the common case (a redelivered
     * event) without touching the balance at all. The index remains the authority — two replicas
     * racing on the same event can both pass this check, and the second one's insert then fails,
     * rolling its transaction back. See [CreditChargeService].
     */
    fun findByEntryTypeAndExternalRef(entryType: EntryType, externalRef: String): CreditLedgerEntry?

    /**
     * The sum of a hunter's ledger, which `hunter_credit.balance_minor` must always equal.
     * Only used by tests and (later) a reconciliation job — the live read is the cached balance,
     * because re-summing history on every charge is what the cache exists to avoid.
     *
     * Returns 0 rather than null for a hunter with no entries, so callers need no special case.
     */
    @Query("select coalesce(sum(e.amountMinor), 0) from CreditLedgerEntry e where e.hunterId = :hunterId")
    fun sumAmountMinor(@Param("hunterId") hunterId: UUID): Long
}
