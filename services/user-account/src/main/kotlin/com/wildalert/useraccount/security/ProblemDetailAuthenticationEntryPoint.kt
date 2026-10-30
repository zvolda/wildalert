package com.wildalert.useraccount.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

/**
 * Answers an unauthenticated request with RFC 9457, the same shape GlobalExceptionHandler uses.
 *
 * Spring Security rejects before any controller is reached, so without this the service would
 * answer the *same* failure in two different formats depending on which layer noticed -- a
 * ProblemDetail from a controller, Spring Security's own body from the filter chain. The React
 * client would survive either (api.ts tolerates a non-JSON body) but a client should not have to.
 *
 * Written by hand rather than via ProblemDetail + an ObjectMapper because the body is fixed: a
 * dependency on Jackson here would be ceremony around eleven bytes of JSON.
 */
class ProblemDetailAuthenticationEntryPoint : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"type":"about:blank","title":"Unauthorized","status":401,""" +
                """"detail":"You are not signed in."}""",
        )
    }
}
