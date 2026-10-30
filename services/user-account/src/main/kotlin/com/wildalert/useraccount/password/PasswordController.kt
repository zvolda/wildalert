package com.wildalert.useraccount.password

import com.wildalert.useraccount.hunter.HunterResponse
import com.wildalert.useraccount.hunter.HunterResponseFactory
import com.wildalert.useraccount.hunter.HunterService
import com.wildalert.useraccount.session.SessionCookies
import com.wildalert.useraccount.session.SessionService
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Changing a password: the signed-in way, and the forgotten way.
 *
 * Both live here rather than on SessionController because they share one rule -- a new password
 * ends every other session -- and separating them would invite one of the two to forget it.
 *
 * **The two flows have opposite exposure, which is the thing to keep straight when editing this
 * file.** `POST /api/me/password` is authenticated and so can afford to be specific about
 * failures. The two `/api/password-resets` endpoints are necessarily public -- being locked out is
 * the whole premise -- so they are deliberately uninformative: one always answers 202, the other
 * has a single 400 for every way a link can fail.
 */
@RestController
class PasswordController(
    private val hunters: HunterService,
    private val responses: HunterResponseFactory,
    private val sessions: SessionService,
    private val cookies: SessionCookies,
    private val resets: PasswordResetService,
) {

    /**
     * Change your own password, then stay signed in here and nowhere else.
     *
     * The order matters: close every session first, then open a fresh one for this caller. Doing
     * it the other way round would delete the session that was just issued. The hunter who made
     * the change keeps working; every other browser is logged out, which is the point of changing
     * a password you think somebody else may know.
     *
     * Returns the hunter, like login does, so the dashboard the caller is already looking at can
     * refresh from one response.
     */
    @PostMapping("/api/me/password")
    fun changePassword(
        @AuthenticationPrincipal hunterId: UUID,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<HunterResponse> {
        val hunter = hunters.changePassword(hunterId, request)
        sessions.closeAll(hunterId)
        val token = sessions.open(hunter)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookies.issue(token, sessions.ttl).toString())
            .body(responses.of(hunter))
    }

    /**
     * Ask for a reset link. **Always 202**, and the body is empty.
     *
     * 202 rather than 200 is the honest status: all this endpoint promises is that the request was
     * accepted, and whether a mail goes out depends on things the caller is not entitled to know.
     * Answering 404 for an unknown address -- the obvious REST reflex -- would turn a public form
     * into a way to test which email addresses have WildAlert accounts.
     */
    @PostMapping("/api/password-resets")
    fun requestReset(@Valid @RequestBody request: RequestPasswordResetRequest): ResponseEntity<Void> {
        resets.request(request.email)
        return ResponseEntity.accepted().build()
    }

    /**
     * Spend a reset link and set the new password. 204, and no session.
     *
     * Deliberately does **not** log the hunter in, unlike registration. The caller has proved
     * they can read the hunter's email, which is not the same as knowing the password -- and
     * sending them to the sign-in form means the new password gets used once immediately, which
     * is how a hunter finds out now rather than later that it is what they thought it was.
     */
    @PostMapping("/api/password-resets/confirm")
    fun confirmReset(@Valid @RequestBody request: ConfirmPasswordResetRequest): ResponseEntity<Void> {
        resets.confirm(request.token, request.newPassword)
        return ResponseEntity.noContent().build()
    }
}
