package com.wildalert.useraccount.hunter

import com.wildalert.useraccount.credit.CreditService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.never
import org.mockito.BDDMockito.then
import org.mockito.Mockito.mock
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional
import java.util.UUID

/** Pure unit tests: no Spring context, no database — just the service logic. */
class HunterServiceTest {

    private val repository = mock(HunterRepository::class.java)
    private val credit = mock(CreditService::class.java)

    /**
     * A trivial stand-in for the real encoder. These tests are about the service's branching --
     * which input leads to which exception -- not about BCrypt, and a real delegating encoder
     * would add ~100ms of key stretching to every single case for no assurance whatsoever.
     */
    private val passwordEncoder = object : PasswordEncoder {
        override fun encode(rawPassword: CharSequence): String = "encoded:$rawPassword"
        override fun matches(rawPassword: CharSequence, encodedPassword: String?): Boolean =
            encodedPassword == "encoded:$rawPassword"
    }

    private val service =
        HunterService(repository, InboundAddresses("in.wildalert.test"), credit, passwordEncoder)

    @Test
    fun `register saves a new hunter when the email is free`() {
        val id = UUID.randomUUID()
        given(repository.existsByEmail("hunter@example.com")).willReturn(false)
        given(repository.save(any(Hunter::class.java))).willAnswer { withId(it.getArgument(0), id) }

        val result = service.register(
            RegisterHunterRequest("hunter@example.com", "+420123456789", "correct-horse"),
        )

        assertThat(result.email).isEqualTo("hunter@example.com")
        assertThat(result.phone).isEqualTo("+420123456789")
        assertThat(result.inboundToken).hasSize(12)
        then(repository).should().save(any(Hunter::class.java))
    }

    @Test
    fun `register opens a credit account for the new hunter`() {
        val id = UUID.randomUUID()
        given(repository.existsByEmail("hunter@example.com")).willReturn(false)
        given(repository.save(any(Hunter::class.java))).willAnswer { withId(it.getArgument(0), id) }

        service.register(RegisterHunterRequest("hunter@example.com", "+420123456789", "correct-horse"))

        // Same transaction as the insert, so a hunter can never exist without a balance.
        then(credit).should().openAccount(id)
    }

    @Test
    fun `a rejected duplicate registration opens no credit account`() {
        given(repository.existsByEmail("hunter@example.com")).willReturn(true)

        assertThrows<DuplicateEmailException> {
            service.register(RegisterHunterRequest("hunter@example.com", "+420123456789", "correct-horse"))
        }

        // Otherwise a 409 would leave an orphan credit row behind, granting credit to a hunter
        // that was never created. Asserting no interaction at all rather than `never()` with a
        // matcher: Mockito's any() returns null, which blows up on a non-null Kotlin parameter
        // before the verification even runs.
        then(credit).shouldHaveNoInteractions()
    }

    @Test
    fun `getByInboundAddress finds the hunter by the token in the address`() {
        val existing = Hunter("hunter@example.com", "+420123456789")
        given(repository.findByInboundToken("7f3k9qabcdef")).willReturn(existing)

        val result = service.getByInboundAddress(" 7F3K9QABCDEF@in.wildalert.test")

        assertThat(result).isSameAs(existing)
    }

    @Test
    fun `getByInboundAddress throws when no hunter owns the token`() {
        given(repository.findByInboundToken("7f3k9qabcdef")).willReturn(null)

        assertThrows<HunterNotFoundByInboundAddressException> {
            service.getByInboundAddress("7f3k9qabcdef@in.wildalert.test")
        }
    }

    @Test
    fun `getByInboundAddress never queries for an address on another domain`() {
        assertThrows<HunterNotFoundByInboundAddressException> {
            service.getByInboundAddress("hunter@gmail.com")
        }
        then(repository).shouldHaveNoInteractions()
    }

    @Test
    fun `register rejects a duplicate email`() {
        given(repository.existsByEmail("dup@example.com")).willReturn(true)

        assertThrows<DuplicateEmailException> {
            service.register(RegisterHunterRequest("dup@example.com", "+420123456789", "correct-horse"))
        }
        then(repository).should(never()).save(any(Hunter::class.java))
    }

    @Test
    fun `getById throws when the hunter does not exist`() {
        val id = UUID.randomUUID()
        given(repository.findById(id)).willReturn(Optional.empty())

        assertThrows<HunterNotFoundException> { service.getById(id) }
    }

    @Test
    fun `register normalizes the email to lowercase and trims whitespace`() {
        // Stubbed for the normalized form; register must call it with that, not the raw input.
        given(repository.existsByEmail("hunter@example.com")).willReturn(false)
        given(repository.save(any(Hunter::class.java)))
            .willAnswer { withId(it.getArgument(0), UUID.randomUUID()) }

        val result = service.register(
            RegisterHunterRequest("  Hunter@Example.COM ", "+420123456789", "correct-horse"),
        )

        assertThat(result.email).isEqualTo("hunter@example.com")
    }

    @Test
    fun `getByEmail returns the hunter when the email exists`() {
        val existing = Hunter("hunter@example.com", "+420123456789", active = true)
        given(repository.findByEmail("hunter@example.com")).willReturn(existing)

        val result = service.getByEmail("hunter@example.com")

        assertThat(result).isSameAs(existing)
    }

    @Test
    fun `getByEmail throws when no hunter has that email`() {
        given(repository.findByEmail("nobody@example.com")).willReturn(null)

        assertThrows<HunterNotFoundByEmailException> { service.getByEmail("nobody@example.com") }
    }

    @Test
    fun `getByEmail normalizes the lookup so case and whitespace do not matter`() {
        val existing = Hunter("hunter@example.com", "+420123456789", active = true)
        // Stubbed for the normalized form; a raw-input lookup would miss and throw.
        given(repository.findByEmail("hunter@example.com")).willReturn(existing)

        val result = service.getByEmail("  Hunter@Example.COM ")

        assertThat(result).isSameAs(existing)
    }

    @Test
    fun `update changes only the provided fields`() {
        val id = UUID.randomUUID()
        val existing = Hunter("a@b.com", "+420111111111", active = true)
        given(repository.findById(id)).willReturn(Optional.of(existing))

        val updated = service.update(
            id,
            UpdateHunterRequest(phone = "+420999999999", active = false),
        )

        assertThat(updated.phone).isEqualTo("+420999999999")
        assertThat(updated.active).isFalse()
        assertThat(updated.email).isEqualTo("a@b.com") // unchanged
    }

    @Test
    fun `register stores an encoded password, never the password itself`() {
        given(repository.existsByEmail("hunter@example.com")).willReturn(false)
        given(repository.save(any(Hunter::class.java)))
            .willAnswer { withId(it.getArgument(0), UUID.randomUUID()) }

        val result = service.register(
            RegisterHunterRequest("hunter@example.com", "+420123456789", "correct-horse"),
        )

        // The real encoder's output is opaque; the fake's is readable, which is why this asserts
        // the encoder was *used* rather than asserting a hash shape.
        assertThat(result.passwordHash).isEqualTo("encoded:correct-horse")
        assertThat(result.passwordHash).isNotEqualTo("correct-horse")
    }

    @Test
    fun `authenticate returns the hunter when the password matches`() {
        val hunter = hunterWithPassword("correct-horse", active = true)
        given(repository.findByEmail("hunter@example.com")).willReturn(hunter)

        val result = service.authenticate(LoginRequest("hunter@example.com", "correct-horse"))

        assertThat(result).isSameAs(hunter)
    }

    @Test
    fun `authenticate normalizes the email, so the case it was typed in does not matter`() {
        val hunter = hunterWithPassword("correct-horse", active = true)
        // Stubbed for the normalized form only -- a raw-input lookup would miss entirely.
        given(repository.findByEmail("hunter@example.com")).willReturn(hunter)

        val result = service.authenticate(LoginRequest("  Hunter@Example.COM ", "correct-horse"))

        assertThat(result).isSameAs(hunter)
    }

    /**
     * The four ways a login can fail must be indistinguishable to the caller. Asserting they all
     * throw the *same* exception type with the *same* message is how that stays true: anyone who
     * later "improves" one of these into a helpful "no such account" breaks this test, which is
     * the point. A helpful message here is an account-existence oracle.
     */
    @Test
    fun `every kind of failed login throws the same indistinguishable error`() {
        val cases = mapOf(
            "unknown email" to {
                given(repository.findByEmail("nobody@example.com")).willReturn(null)
                LoginRequest("nobody@example.com", "correct-horse")
            },
            "wrong password" to {
                given(repository.findByEmail("hunter@example.com"))
                    .willReturn(hunterWithPassword("correct-horse", active = true))
                LoginRequest("hunter@example.com", "wrong-password")
            },
            "a hunter registered before passwords existed" to {
                given(repository.findByEmail("legacy@example.com"))
                    .willReturn(Hunter("legacy@example.com", "+420123456789", active = true))
                LoginRequest("legacy@example.com", "correct-horse")
            },
            "a deactivated hunter with the right password" to {
                given(repository.findByEmail("gone@example.com"))
                    .willReturn(hunterWithPassword("correct-horse", active = false, email = "gone@example.com"))
                LoginRequest("gone@example.com", "correct-horse")
            },
        )

        val messages = cases.map { (description, arrange) ->
            val request = arrange()
            val thrown = assertThrows<InvalidCredentialsException>(
                "expected $description to be refused",
            ) { service.authenticate(request) }
            thrown.message
        }

        assertThat(messages).hasSize(4)
        assertThat(messages.distinct())
            .describedAs("all four refusals must read identically")
            .hasSize(1)
    }

    private fun hunterWithPassword(
        raw: String,
        active: Boolean,
        email: String = "hunter@example.com",
    ): Hunter =
        Hunter(email, "+420123456789", active = active)
            .also { it.setPassword(passwordEncoder.encode(raw)) }

    /**
     * Stands in for what JPA does on insert. `Hunter.id` has a protected setter so nothing can
     * assign it by accident, which is right — but `register` needs the generated id to open the
     * credit account, so a mocked `save` has to produce one. Same trick `HunterControllerTest`
     * already uses.
     */
    private fun withId(hunter: Hunter, id: UUID): Hunter =
        hunter.also { ReflectionTestUtils.setField(it, "id", id) }
}
