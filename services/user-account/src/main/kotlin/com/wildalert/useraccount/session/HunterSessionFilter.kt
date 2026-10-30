package com.wildalert.useraccount.session

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Turns the session cookie into an authenticated request.
 *
 * Follows the shape of email-ingestion's WebhookAuthFilter: a OncePerRequestFilter that stands
 * down when there is nothing for it to do, and that never rejects anything itself. Rejection is
 * the filter chain's job -- this one only reports who is asking, so an endpoint that does not
 * care about identity is unaffected by a missing or stale cookie.
 *
 * Deliberately **not** a `@Component`: Spring Boot auto-registers any `Filter` bean into the
 * servlet container's own chain, which would run this outside Spring Security's ordering as well
 * as inside it. SecurityConfig constructs it instead, so there is exactly one registration and
 * its position in the chain is stated explicitly.
 */
class HunterSessionFilter(
    private val sessions: SessionService,
    private val cookies: SessionCookies,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val raw = request.cookies?.firstOrNull { it.name == cookies.cookieName }?.value
        if (!raw.isNullOrBlank() && SecurityContextHolder.getContext().authentication == null) {
            val hunterId = sessions.resolve(raw)
            if (hunterId != null) {
                val authentication = UsernamePasswordAuthenticationToken(
                    hunterId,
                    null,
                    listOf(SimpleGrantedAuthority(ROLE_HUNTER)),
                )
                SecurityContextHolder.getContext().authentication = authentication
            } else {
                // Expired or unknown. Not worth a warning: a hunter returning after a month has
                // a stale cookie through no fault of anyone's.
                logger.debug("Ignoring a session cookie that resolves to no live session")
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val ROLE_HUNTER = "ROLE_HUNTER"
    }
}
