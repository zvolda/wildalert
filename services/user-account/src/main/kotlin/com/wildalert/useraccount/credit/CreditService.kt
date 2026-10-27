package com.wildalert.useraccount.credit

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Credit, as far as this slice goes: open an account and put the signup grant in it, and read a
 * balance back. **Nothing here spends credit** — the guarded debit, the charge endpoint and the
 * Stripe top-up arrive in later slices.
 *
 * Every mutation writes the ledger *and* the cached balance together, in one transaction, so the
 * two can never disagree. That invariant is what makes the cached `balance_minor` safe to read.
 */
@Service
class CreditService(
    private val credits: HunterCreditRepository,
    private val ledger: CreditLedgerRepository,
    /**
     * What a new hunter starts with, in haléře. 2500 is ten alerts with their recognition
     * (100 + 150 each) — though in practice it buys fewer, because every photo pays the 100
     * whether or not it alerts.
     *
     * **This is also the entire abuse budget of an unauthenticated registration form**: at 2500 a
     * scripted signup costs 25 CZK. Set `SIGNUP_GRANT_MINOR=0` the day someone finds the form —
     * it is the kill switch, not the plan. Rate limiting is the real fix.
     */
    @Value("\${billing.signup-grant-minor}") private val signupGrantMinor: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Opens a hunter's credit account with the signup grant.
     *
     * Called from `HunterService.register`'s transaction on purpose, so a hunter can never exist
     * without a credit row: a partial registration leaves nothing behind, and no later code has
     * to cope with a missing balance.
     *
     * A grant of zero still creates the row and still writes a ledger entry. The row because the
     * invariant "every hunter has a balance" is worth more than saving an insert, and the entry
     * because "granted nothing" is a fact worth being able to see.
     */
    @Transactional
    fun openAccount(hunterId: UUID): HunterCredit {
        val credit = credits.save(HunterCredit(hunterId = hunterId, balanceMinor = signupGrantMinor))
        ledger.save(
            CreditLedgerEntry(
                hunterId = hunterId,
                amountMinor = signupGrantMinor,
                entryType = EntryType.SIGNUP_GRANT,
                // A grant has no external cause, so no idempotency key. The unique index is
                // partial precisely so these nulls can coexist.
                externalRef = null,
                balanceAfterMinor = signupGrantMinor,
            ),
        )
        log.info("Opened credit account for hunter {} with a grant of {} haléře", hunterId, signupGrantMinor)
        return credit
    }

    /**
     * The hunter's balance in haléře, or 0 if they somehow have no credit row.
     *
     * The zero is a deliberate floor rather than an exception: a missing row should be impossible
     * (see [openAccount]), and if it ever happens, reporting no credit is the safe failure — it
     * refuses to spend money we cannot account for.
     */
    @Transactional(readOnly = true)
    fun balanceOf(hunterId: UUID): Long =
        credits.findById(hunterId).map { it.balanceMinor }.orElseGet {
            log.warn("Hunter {} has no credit row; reporting a zero balance", hunterId)
            0
        }
}
