package com.wildalert.useraccount.password

import com.wildalert.useraccount.hunter.Hunter
import com.wildalert.useraccount.hunter.HunterRepository
import com.wildalert.useraccount.session.SessionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.never
import org.mockito.BDDMockito.then
import org.mockito.Mockito.times
import org.mockito.Mockito.mock
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.client.RestClientException
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure unit tests: no Spring context, no database.
 *
 * Most of these assert a **negative** -- that nothing was saved and no mail was sent -- because
 * that is what the privacy rule actually consists of. `request` returns Unit for every input, so
 * the only way to tell the cases apart from inside is by what it did, and the only way to tell
 * them apart from *outside* must be nothing at all.
 *
 * **Matchers are avoided almost everywhere here, and that is a scar.** Mockito's `any(T::class)`
 * returns null, Kotlin inserts a null check wherever a platform-type value reaches a non-null
 * parameter, and the resulting throw happens *before* the stubbing is recorded -- leaving a
 * dangling matcher on Mockito's thread-global stack that fails the **next** test class to run.
 * One misuse in this file failed twenty unrelated tests across the module, including every
 * `@SpringBootTest` context. HunterServiceTest already carries a note about the same trap. So:
 * concrete values wherever one is known, [anyValue] where a matcher is unavoidable.
 */
class PasswordResetServiceTest {

    private val hunters = mock(HunterRepository::class.java)
    private val tokens = mock(PasswordResetTokenRepository::class.java)
    private val sessions = mock(SessionService::class.java)
    private val sweeper = mock(ExpiredResetTokenSweeper::class.java)

    /** As in HunterServiceTest: real BCrypt would add ~100ms per case and prove nothing here. */
    private val passwordEncoder = object : PasswordEncoder {
        override fun encode(rawPassword: CharSequence): String = "encoded:$rawPassword"
        override fun matches(rawPassword: CharSequence, encodedPassword: String?): Boolean =
            encodedPassword == "encoded:$rawPassword"
    }

    private val sent = mutableListOf<EmailMessage>()

    /** Flipped on by the one test that needs the provider to be having a bad minute. */
    private val failing = AtomicBoolean(false)

    /**
     * A recording stand-in. An object expression rather than a lambda because `EmailSender` is a
     * Kotlin interface, and SAM conversion applies only to Java ones.
     */
    private val email = object : EmailSender {
        override fun send(message: EmailMessage) {
            if (failing.get()) throw RestClientException("Resend is down")
            sent += message
        }
    }

    /** Everything `save` was handed, so the negative cases can assert on an empty list. */
    private val saved = mutableListOf<PasswordResetToken>()

    private val properties = PasswordResetProperties(
        ttl = Duration.ofHours(1),
        throttle = Duration.ofMinutes(5),
        publicBaseUrl = "https://wildalert.test",
    )

    /**
     * A **real** issuer over the same mocked repositories, rather than a mock of it.
     *
     * The issuer is a separate bean only because Spring's `@Transactional` cannot work through a
     * self-invocation (see its class comment) — it is not a seam worth faking, and mocking it
     * here would turn every "request does nothing for X" case below into a test of a stub.
     */
    private val issuer = PasswordResetTokenIssuer(hunters, tokens, properties)

    private val service = PasswordResetService(
        hunters, tokens, sessions, issuer, sweeper, passwordEncoder, email, properties,
    )

    @BeforeEach
    fun recordSaves() {
        given(tokens.save(anyValue<PasswordResetToken>())).willAnswer {
            val token = it.getArgument<PasswordResetToken>(0)
            saved += token
            token
        }
    }

    // ---------- request ----------

    @Test
    fun `request sends a link and stores only its hash`() {
        val hunter = activeHunter()
        given(hunters.findByEmail("hunter@example.com")).willReturn(hunter)

        service.request("hunter@example.com")

        val message = sent.single()
        assertThat(message.to).isEqualTo("hunter@example.com")
        val raw = tokenFromLink(message.body)

        // The credential is in the mail; the database holds a digest of it and nothing else.
        assertThat(saved.single().tokenHash).isEqualTo(SessionService.hash(raw))
        assertThat(saved.single().tokenHash).isNotEqualTo(raw)
        assertThat(saved.single().hunterId).isEqualTo(hunter.id)
    }

    @Test
    fun `request normalizes the email the way registration does`() {
        given(hunters.findByEmail("hunter@example.com")).willReturn(activeHunter())

        service.request("  Hunter@Example.COM ")

        assertThat(sent).hasSize(1)
    }

    @Test
    fun `request replaces any outstanding link rather than adding a second`() {
        val hunter = activeHunter()
        given(hunters.findByEmail("hunter@example.com")).willReturn(hunter)

        service.request("hunter@example.com")

        then(tokens).should().deleteUnusedByHunterId(hunter.id!!)
    }

    @Test
    fun `the link points at the configured base url, never a request header`() {
        given(hunters.findByEmail("hunter@example.com")).willReturn(activeHunter())

        service.request("hunter@example.com")

        // Reset poisoning is the attack this forecloses: a Host-derived link would send a real
        // hunter's mail to whatever host the attacker's request named.
        assertThat(sent.single().body).contains("https://wildalert.test/?reset=")
    }

    @Test
    fun `request does nothing for an address with no account`() {
        given(hunters.findByEmail("nobody@example.com")).willReturn(null)

        service.request("nobody@example.com")

        assertNothingHappened()
    }

    @Test
    fun `request does nothing for a deactivated hunter`() {
        given(hunters.findByEmail("hunter@example.com")).willReturn(activeHunter(active = false))

        service.request("hunter@example.com")

        assertNothingHappened()
    }

    @Test
    fun `request does nothing for a hunter who has no password at all`() {
        // Hunters who predate logins. They cannot sign in, so a reset link would be a way to
        // claim an account by email alone -- see V5's note on the nullable column.
        val hunter = Hunter("hunter@example.com", "+420123456789").also { withId(it) }
        given(hunters.findByEmail("hunter@example.com")).willReturn(hunter)

        service.request("hunter@example.com")

        assertNothingHappened()
    }

    @Test
    fun `request is throttled when a fresh link was already sent`() {
        val hunter = activeHunter()
        given(hunters.findByEmail("hunter@example.com")).willReturn(hunter)
        given(
            tokens.existsByHunterIdAndUsedAtIsNullAndCreatedAtAfter(
                anyValue<UUID>(),
                anyValue<Instant>(),
            ),
        ).willReturn(true)

        service.request("hunter@example.com")

        assertNothingHappened()
        // Specifically must not have wiped the still-valid token it refused to replace: the
        // hunter is holding a working link from a minute ago.
        then(tokens).should(never()).deleteUnusedByHunterId(hunter.id!!)
    }

    @Test
    fun `request sweeps spent and expired tokens`() {
        given(hunters.findByEmail("nobody@example.com")).willReturn(null)

        service.request("nobody@example.com")

        // Even on the path that does nothing else: the sweep rides on the rare event by design.
        then(sweeper).should().sweep()
    }

    @Test
    fun `a failed send throws the token away instead of leaving a dead one behind`() {
        val hunter = activeHunter()
        given(hunters.findByEmail("hunter@example.com")).willReturn(hunter)
        failing.set(true)

        // No exception escapes, and that is the point: an address with no account answers 202
        // without ever reaching a send, so a provider outage that produced a 500 here would tell
        // an attacker which addresses are registered.
        service.request("hunter@example.com")

        // Called twice: once before issuing (one link at a time), once to discard the unsent one.
        // The second is what matters -- the throttle is keyed on a fresh unused token, so leaving
        // it would lock the hunter out of retrying for a mail that never arrived.
        then(tokens).should(times(2)).deleteUnusedByHunterId(hunter.id!!)
    }

    @Test
    fun `a successful send keeps the token`() {
        val hunter = activeHunter()
        given(hunters.findByEmail("hunter@example.com")).willReturn(hunter)

        service.request("hunter@example.com")

        // Only the pre-issue cleanup. The counterpart to the test above: a delivered link must
        // survive, or the mail the hunter is holding is already dead.
        then(tokens).should(times(1)).deleteUnusedByHunterId(hunter.id!!)
    }

    // ---------- confirm ----------

    @Test
    fun `confirm sets the new password, spends the token and ends every session`() {
        val hunter = activeHunter()
        val raw = SessionService.newToken()
        val token = usableToken(raw, hunter.id!!)
        given(tokens.findById(SessionService.hash(raw))).willReturn(Optional.of(token))
        given(hunters.findById(hunter.id!!)).willReturn(Optional.of(hunter))

        service.confirm(raw, "a-brand-new-password")

        assertThat(hunter.passwordHash).isEqualTo("encoded:a-brand-new-password")
        assertThat(token.usedAt).isNotNull()
        then(tokens).should().deleteUnusedByHunterId(hunter.id!!)
        // The reason the whole flow exists: whoever else was signed in is now not.
        then(sessions).should().closeAll(hunter.id!!)
    }

    @Test
    fun `confirm refuses an unknown token`() {
        given(tokens.findById(anyString())).willReturn(Optional.empty())

        assertThrows<InvalidResetTokenException> { service.confirm("made-up", "a-brand-new-password") }
    }

    @Test
    fun `confirm refuses an expired token`() {
        val hunter = activeHunter()
        val raw = SessionService.newToken()
        val expired = PasswordResetToken(
            tokenHash = SessionService.hash(raw),
            hunterId = hunter.id!!,
            expiresAt = Instant.now().minusSeconds(1),
        )
        given(tokens.findById(SessionService.hash(raw))).willReturn(Optional.of(expired))

        assertThrows<InvalidResetTokenException> { service.confirm(raw, "a-brand-new-password") }
        then(sessions).should(never()).closeAll(hunter.id!!)
    }

    @Test
    fun `confirm refuses a token that has already been used`() {
        val hunter = activeHunter()
        val raw = SessionService.newToken()
        val token = usableToken(raw, hunter.id!!).also { it.markUsed(Instant.now()) }
        given(tokens.findById(SessionService.hash(raw))).willReturn(Optional.of(token))

        // The replay case. A link that worked a moment ago must not work twice, or forwarding the
        // mail -- or a mailbox read later -- hands over the account again.
        assertThrows<InvalidResetTokenException> { service.confirm(raw, "a-brand-new-password") }
    }

    @Test
    fun `confirm refuses a token whose hunter has been deactivated`() {
        val hunter = activeHunter(active = false)
        val raw = SessionService.newToken()
        given(tokens.findById(SessionService.hash(raw)))
            .willReturn(Optional.of(usableToken(raw, hunter.id!!)))
        given(hunters.findById(hunter.id!!)).willReturn(Optional.of(hunter))

        assertThrows<InvalidResetTokenException> { service.confirm(raw, "a-brand-new-password") }
        assertThat(hunter.passwordHash).isEqualTo("encoded:correct-horse")
    }

    // No test that "every refusal reads identically" here, unlike HunterServiceTest's four login
    // refusals: there is one exception class with one fixed message, so the property is held by
    // the type rather than by agreement between four code paths. The thing worth guarding is that
    // it stays that way -- a future `TokenExpiredException` would be the regression, and it would
    // be visible in review rather than in an assertion.

    // ---------- helpers ----------

    private fun assertNothingHappened() {
        assertThat(sent).isEmpty()
        assertThat(saved).isEmpty()
    }

    private fun activeHunter(active: Boolean = true): Hunter =
        Hunter("hunter@example.com", "+420123456789", active = active)
            .also { it.setPassword(passwordEncoder.encode("correct-horse")) }
            .also { withId(it) }

    private fun usableToken(raw: String, hunterId: UUID) = PasswordResetToken(
        tokenHash = SessionService.hash(raw),
        hunterId = hunterId,
        expiresAt = Instant.now().plus(Duration.ofHours(1)),
    )

    /** Reads the token back out of the mail, which is the only place it ever exists in the clear. */
    private fun tokenFromLink(body: String): String =
        Regex("""/\?reset=(\S+)""").find(body)!!.groupValues[1]

    private fun withId(hunter: Hunter, id: UUID = UUID.randomUUID()): Hunter =
        hunter.also { ReflectionTestUtils.setField(it, "id", id) }

    /**
     * Registers a Mockito matcher and hands back an unchecked null.
     *
     * The cast is what makes it safe to pass where Kotlin demands a non-null value: the compiler
     * inserts no runtime check for a value already typed `T`, whereas `ArgumentMatchers.any()`
     * returns a platform type and gets one. See this class's header for what goes wrong without it.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        ArgumentMatchers.any<T>()
        return null as T
    }
}
