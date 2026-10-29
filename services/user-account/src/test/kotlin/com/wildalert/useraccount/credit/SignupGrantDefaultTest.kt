package com.wildalert.useraccount.credit

import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.RegisterHunterRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Pins the **default** signup grant at zero — no `properties` override here on purpose, so this
 * reads whatever `application.yml` ships.
 *
 * It is a security property, not a preference. Registration is unauthenticated and unthrottled,
 * so the grant is the entire abuse budget of a public form; a change that quietly made it
 * generous again should fail a test rather than be discovered on a GoSMS invoice. Turning it on
 * is a deliberate act (`SIGNUP_GRANT_MINOR`) once there is rate limiting in front of the form.
 *
 * **Needs Postgres**, like the other credit tests.
 */
@SpringBootTest
@Transactional
class SignupGrantDefaultTest {

    @Autowired private lateinit var hunters: HunterService
    @Autowired private lateinit var credits: HunterCreditRepository
    @Autowired private lateinit var ledger: CreditLedgerRepository

    @Test
    fun `a new hunter is granted nothing by default`() {
        val hunter = hunters.register(
            RegisterHunterRequest("grant-default-${UUID.randomUUID()}@example.com", "+420123456789"),
        )
        credits.flush()

        assertThat(credits.balanceMinorOf(hunter.id!!)).isEqualTo(0)
    }

    @Test
    fun `a zero grant still opens an account and still records the entry`() {
        val hunter = hunters.register(
            RegisterHunterRequest("grant-default-${UUID.randomUUID()}@example.com", "+420123456789"),
        )
        credits.flush()
        val id = hunter.id!!

        // The row exists even though it holds nothing, so "every hunter has a balance" stays true
        // and no downstream code needs a missing-row special case.
        assertThat(credits.findById(id)).isPresent()

        // And "granted nothing" is itself a fact worth being able to see in a statement.
        val entries = ledger.findByHunterIdOrderByCreatedAtDesc(id)
        assertThat(entries).hasSize(1)
        assertThat(entries.single().entryType).isEqualTo(EntryType.SIGNUP_GRANT)
        assertThat(entries.single().amountMinor).isEqualTo(0)
        assertThat(entries.single().balanceAfterMinor).isEqualTo(0)
    }
}
