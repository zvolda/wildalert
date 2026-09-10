package com.wildalert.useraccount.hunter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.never
import org.mockito.BDDMockito.then
import org.mockito.Mockito.mock
import java.util.Optional
import java.util.UUID

/** Pure unit tests: no Spring context, no database — just the service logic. */
class HunterServiceTest {

    private val repository = mock(HunterRepository::class.java)
    private val service = HunterService(repository)

    @Test
    fun `register saves a new hunter when the email is free`() {
        given(repository.existsByEmail("hunter@example.com")).willReturn(false)
        given(repository.save(any(Hunter::class.java))).willAnswer { it.getArgument<Hunter>(0) }

        val result = service.register(
            RegisterHunterRequest("hunter@example.com", "+420123456789", Plan.PRO),
        )

        assertThat(result.email).isEqualTo("hunter@example.com")
        assertThat(result.phone).isEqualTo("+420123456789")
        assertThat(result.plan).isEqualTo(Plan.PRO)
        then(repository).should().save(any(Hunter::class.java))
    }

    @Test
    fun `register rejects a duplicate email`() {
        given(repository.existsByEmail("dup@example.com")).willReturn(true)

        assertThrows<DuplicateEmailException> {
            service.register(RegisterHunterRequest("dup@example.com", "+420123456789"))
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
        given(repository.save(any(Hunter::class.java))).willAnswer { it.getArgument<Hunter>(0) }

        val result = service.register(
            RegisterHunterRequest("  Hunter@Example.COM ", "+420123456789", Plan.FREE),
        )

        assertThat(result.email).isEqualTo("hunter@example.com")
    }

    @Test
    fun `getByEmail returns the hunter when the email exists`() {
        val existing = Hunter("hunter@example.com", "+420123456789", Plan.FREE, active = true)
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
        val existing = Hunter("hunter@example.com", "+420123456789", Plan.FREE, active = true)
        // Stubbed for the normalized form; a raw-input lookup would miss and throw.
        given(repository.findByEmail("hunter@example.com")).willReturn(existing)

        val result = service.getByEmail("  Hunter@Example.COM ")

        assertThat(result).isSameAs(existing)
    }

    @Test
    fun `update changes only the provided fields`() {
        val id = UUID.randomUUID()
        val existing = Hunter("a@b.com", "+420111111111", Plan.FREE, active = true)
        given(repository.findById(id)).willReturn(Optional.of(existing))

        val updated = service.update(
            id,
            UpdateHunterRequest(phone = "+420999999999", plan = Plan.PRO, active = false),
        )

        assertThat(updated.phone).isEqualTo("+420999999999")
        assertThat(updated.plan).isEqualTo(Plan.PRO)
        assertThat(updated.active).isFalse()
        assertThat(updated.email).isEqualTo("a@b.com") // unchanged
    }
}
