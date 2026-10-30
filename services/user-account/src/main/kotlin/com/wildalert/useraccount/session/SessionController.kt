package com.wildalert.useraccount.session

import com.wildalert.useraccount.hunter.HunterResponse
import com.wildalert.useraccount.hunter.HunterResponseFactory
import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.hunter.LoginRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The browser-facing surface: log in, log out, read your own record.
 *
 * Kept apart from HunterController on purpose. Everything here requires -- or creates -- a hunter
 * identity, while the endpoints on HunterController are either public registration or
 * service-to-service lookups protected by the Cloud Run perimeter. Two controllers makes
 * "who is allowed to call this?" answerable by looking at which file it is in.
 */
@RestController
class SessionController(
    private val hunters: HunterService,
    private val responses: HunterResponseFactory,
    private val sessions: SessionService,
    private val cookies: SessionCookies,
) {

    /**
     * Log in. Returns the hunter so the client needs no second round-trip to render the
     * dashboard, and sets the session cookie.
     */
    @PostMapping("/api/sessions")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<HunterResponse> {
        val hunter = hunters.authenticate(request)
        val token = sessions.open(hunter)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookies.issue(token, sessions.ttl).toString())
            .body(responses.of(hunter))
    }

    /**
     * Log out: delete the session row *and* clear the cookie.
     *
     * Deleting the row is what actually ends the session -- clearing the cookie alone would leave
     * a token that still works if anyone had copied it. Reading the cookie off the raw request
     * rather than the SecurityContext because what we need to delete is the token, and the
     * context only carries the hunter id it resolved to.
     */
    @DeleteMapping("/api/sessions")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        request.cookies?.firstOrNull { it.name == cookies.cookieName }?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { sessions.close(it) }
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
            .build()
    }

    /**
     * The signed-in hunter's own record: credit balance and personal inbound address.
     *
     * This is what the gateway can safely expose to a browser, and it is why the other lookup
     * endpoints do not need to be: a hunter reads their own row by presenting a session, never by
     * naming an id or an email.
     */
    @GetMapping("/api/me")
    fun me(@AuthenticationPrincipal hunterId: UUID): HunterResponse =
        responses.of(hunters.getById(hunterId))
}
