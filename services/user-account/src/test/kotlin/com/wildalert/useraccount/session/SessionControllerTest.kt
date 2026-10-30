package com.wildalert.useraccount.session

import com.wildalert.useraccount.credit.CreditService
import com.wildalert.useraccount.hunter.Hunter
import com.wildalert.useraccount.hunter.HunterResponseFactory
import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.InboundAddresses
import com.wildalert.useraccount.hunter.InvalidCredentialsException
import com.wildalert.useraccount.hunter.LoginRequest
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The HTTP contract of logging in, logging out and reading your own record.
 *
 * `addFilters = false`, so identity is set directly on the SecurityContextHolder where a test
 * needs one. SecurityConfigTest covers which of these endpoints the real chain lets through;
 * this file covers what they do once they are reached.
 */
@WebMvcTest(SessionController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(HunterResponseFactory::class, InboundAddresses::class, SessionCookies::class)
class SessionControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var hunters: HunterService

    @MockitoBean
    lateinit var credit: CreditService

    @MockitoBean
    lateinit var sessions: SessionService

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a successful login returns the hunter and sets the session cookie`() {
        val id = UUID.randomUUID()
        val hunter = sampleHunter(id)
        given(hunters.authenticate(LoginRequest("hunter@example.com", "correct-horse")))
            .willReturn(hunter)
        given(sessions.open(hunter)).willReturn("a-session-token")
        given(sessions.ttl).willReturn(Duration.ofDays(30))
        given(credit.balanceOf(id)).willReturn(2500)

        mockMvc.perform(login("""{"email":"hunter@example.com","password":"correct-horse"}"""))
            .andExpect(status().isOk)
            // The hunter comes back on the login response itself, so the client can render the
            // dashboard without a second round-trip to /api/me.
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.inboundAddress").value("7f3k9qabcdef@wildalert.local"))
            .andExpect(jsonPath("$.creditBalanceMinor").value(2500))
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(
                header().string(
                    HttpHeaders.SET_COOKIE,
                    allOf(
                        containsString("wa_session=a-session-token"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Lax"),
                    ),
                ),
            )
    }

    @Test
    fun `a failed login returns 401 and no cookie`() {
        willThrow(InvalidCredentialsException())
            .given(hunters).authenticate(LoginRequest("hunter@example.com", "wrong-password"))

        mockMvc.perform(login("""{"email":"hunter@example.com","password":"wrong-password"}"""))
            .andExpect(status().isUnauthorized)
            // A refused login that still handed out a cookie would be the worst kind of bug.
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andExpect(jsonPath("$.detail").value("Email or password is incorrect"))
    }

    @Test
    fun `a login with no password is rejected before any lookup happens`() {
        mockMvc.perform(login("""{"email":"hunter@example.com","password":""}"""))
            .andExpect(status().isBadRequest)

        // No lookup means an empty password cannot even be used to probe which emails exist.
        then(hunters).shouldHaveNoInteractions()
    }

    @Test
    fun `me returns the signed-in hunter's own record`() {
        val id = UUID.randomUUID()
        given(hunters.getById(id)).willReturn(sampleHunter(id))
        given(credit.balanceOf(id)).willReturn(1000)
        signIn(id)

        mockMvc.perform(get("/api/me"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.creditBalanceMinor").value(1000))
            .andExpect(jsonPath("$.alertPriceMinor").value(250))
    }

    @Test
    fun `logging out deletes the session row, not just the cookie`() {
        signIn(UUID.randomUUID())

        mockMvc.perform(delete("/api/sessions").cookie(Cookie("wa_session", "a-session-token")))
            .andExpect(status().isNoContent)
            .andExpect(
                header().string(
                    HttpHeaders.SET_COOKIE,
                    // Max-Age=0 is how a cookie is deleted.
                    allOf(containsString("wa_session="), containsString("Max-Age=0")),
                ),
            )

        // Clearing the cookie alone would leave a token that still worked for anyone who had
        // copied it. Ending the session means deleting the row.
        then(sessions).should().close("a-session-token")
    }

    @Test
    fun `logging out with no cookie still succeeds and touches no session`() {
        signIn(UUID.randomUUID())

        mockMvc.perform(delete("/api/sessions"))
            .andExpect(status().isNoContent)

        then(sessions).shouldHaveNoInteractions()
    }

    // --- helpers ---

    private fun login(body: String) =
        post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(body)

    /** Stands in for what HunterSessionFilter does once a cookie has been resolved. */
    private fun signIn(hunterId: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(hunterId, null, emptyList())
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
