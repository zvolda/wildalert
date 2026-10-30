package com.wildalert.useraccount.security

import com.wildalert.useraccount.credit.ChargeController
import com.wildalert.useraccount.credit.ChargeOutcome
import com.wildalert.useraccount.credit.ChargeRequest
import com.wildalert.useraccount.credit.ChargeResponse
import com.wildalert.useraccount.credit.CreditChargeService
import com.wildalert.useraccount.credit.CreditService
import com.wildalert.useraccount.credit.EntryType
import com.wildalert.useraccount.credit.Notice
import com.wildalert.useraccount.hunter.Hunter
import com.wildalert.useraccount.hunter.HunterResponseFactory
import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.InboundAddresses
import com.wildalert.useraccount.hunter.LoginRequest
import com.wildalert.useraccount.session.SessionController
import com.wildalert.useraccount.session.SessionCookies
import com.wildalert.useraccount.session.SessionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The real filter chain, with filters **on** — the opposite of every other @WebMvcTest here.
 *
 * This exists because of one specific way to break production. Putting
 * `spring-boot-starter-security` on the classpath secures every endpoint by default, and
 * user-account serves endpoints that other services call with no cookie and no Spring Security
 * credential at all. The worst of them is the charge endpoint: notification calls it for every
 * recognized animal, and if it starts answering 401 then the money never moves, no SMS is ever
 * sent, and nothing logs anything that names the cause. A 401 there is a silent outage.
 *
 * So these tests assert the *absence* of authentication, which is unusual enough to be worth
 * saying out loud: the protection for those endpoints is Cloud Run's "Require authentication"
 * plus the gateway's route allowlist, a perimeter outside this application.
 */
@WebMvcTest(controllers = [ChargeController::class, SessionController::class])
@Import(
    SecurityConfig::class,
    SessionCookies::class,
    HunterResponseFactory::class,
    InboundAddresses::class,
)
class SecurityConfigTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var hunters: HunterService

    @MockitoBean
    lateinit var charges: CreditChargeService

    @MockitoBean
    lateinit var credit: CreditService

    @MockitoBean
    lateinit var sessions: SessionService

    @Test
    fun `notification can charge a hunter with no cookie and no credential`() {
        val id = UUID.randomUUID()
        val hunter = mock(Hunter::class.java)
        given(hunters.getByInboundAddress("abc@wildalert.local")).willReturn(hunter)
        given(charges.charge(hunter, ChargeRequest("evt-1", 250, EntryType.SMS_DEBIT))).willReturn(
            ChargeResponse(
                hunterId = id,
                phone = "+420123456789",
                active = true,
                outcome = ChargeOutcome.CHARGED,
                balanceAfterMinor = 2250,
                notice = Notice.NONE,
            ),
        )

        mockMvc.perform(
            post("/api/hunters/by-inbound-address/charges")
                .param("address", "abc@wildalert.local")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sourceEventId":"evt-1","amountMinor":250,"entryType":"SMS_DEBIT"}"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `the hunter lookups stay open to unauthenticated callers`() {
        // Used from deploy/CONSOLE.md for manual operations. Whatever these answer -- a hunter or
        // a 404 -- it must not be 401, or the perimeter has moved without anyone deciding to.
        val paths = listOf(
            get("/api/hunters/by-email").param("email", "nobody@example.com"),
            get("/api/hunters/by-inbound-address").param("address", "nobody@wildalert.local"),
        )

        paths.forEach { request ->
            val status = mockMvc.perform(request).andReturn().response.status
            assertThat(status)
                .describedAs("$request must not be refused for lack of a session")
                .isNotEqualTo(401)
        }
    }

    @Test
    fun `logging in needs no session, which is the only way to get one`() {
        // 401 here would be a deadlock: no session, so no way to log in, so no session. Stubbed
        // all the way to a success so the assertion is 200 rather than "not 401" -- a refused
        // login is *also* a 401, so "not 401" would be unable to tell the chain blocking the
        // request apart from the controller refusing the credentials.
        val id = UUID.randomUUID()
        val hunter = sampleHunter(id)
        given(hunters.authenticate(LoginRequest("hunter@example.com", "correct-horse")))
            .willReturn(hunter)
        given(sessions.open(hunter)).willReturn("a-session-token")
        given(sessions.ttl).willReturn(Duration.ofDays(30))
        given(credit.balanceOf(id)).willReturn(2500)

        mockMvc.perform(
            post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"hunter@example.com","password":"correct-horse"}"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `reading your own record without a session is refused, as RFC 9457`() {
        mockMvc.perform(get("/api/me"))
            .andExpect(status().isUnauthorized)
            // ProblemDetailAuthenticationEntryPoint, so a filter-chain refusal looks like a
            // controller refusal instead of Spring Security's own shape.
            .andExpect { result ->
                assertThat(result.response.contentType)
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                assertThat(result.response.contentAsString).contains(""""status":401""")
            }
    }

    @Test
    fun `logging out without a session is refused rather than silently accepted`() {
        mockMvc.perform(delete("/api/sessions"))
            .andExpect(status().isUnauthorized)
    }

    private fun sampleHunter(id: UUID): Hunter {
        val hunter = Hunter("hunter@example.com", "+420123456789", active = true)
        ReflectionTestUtils.setField(hunter, "id", id)
        ReflectionTestUtils.setField(hunter, "inboundToken", "7f3k9qabcdef")
        ReflectionTestUtils.setField(hunter, "createdAt", Instant.now())
        ReflectionTestUtils.setField(hunter, "updatedAt", Instant.now())
        return hunter
    }
}
