package com.wildalert.useraccount.session

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The session cookie, in one place.
 *
 * Attributes worth the words:
 * - **HttpOnly** — nothing in the React app ever needs to read this, so script cannot either.
 * - **SameSite=Lax** — blocks the cross-site POST that is the actual CSRF vector, while still
 *   sending the cookie when a hunter follows a link to the site. Strict would make an inbound
 *   link render as logged-out on first load, which looks like a bug and is not worth the margin.
 * - **Secure, from config** — a `Secure` cookie is silently *not set* over plain http, so a
 *   hardcoded `true` would make local dev on :5173 look like a broken login. False by default,
 *   `SESSION_COOKIE_SECURE=true` on the Cloud Run revision.
 * - **Path=/ and no Domain** — host-only, so it never leaks to a sibling hostname.
 */
@Component
class SessionCookies(
    @Value("\${session.cookie.name}") private val name: String,
    @Value("\${session.cookie.secure}") private val secure: Boolean,
) {
    val cookieName: String get() = name

    fun issue(rawToken: String, ttl: Duration): ResponseCookie =
        base(rawToken).maxAge(ttl).build()

    /**
     * The same cookie with a zero lifetime, which is how a cookie is deleted: every attribute has
     * to match the one that was set, or the browser keeps the original alongside it.
     */
    fun clear(): ResponseCookie = base("").maxAge(Duration.ZERO).build()

    private fun base(value: String): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .path("/")
            .sameSite("Lax")
}
