package com.wildalert.emailingestion.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Requires a shared secret on the email webhook.
 *
 * In production the webhook is a public URL — Cloudflare Email Routing posts raw emails to it from
 * outside our network, so it cannot be locked down by IP. Without a secret anyone who finds the URL
 * could push images through storage and the DeepFaune model at our expense. The same value is
 * configured on both sides: `WEBHOOK_SECRET` here, and the header on the Email Worker's request.
 *
 * Only the webhook path is guarded, so health probes stay reachable. Follows the same
 * config-gated-real-behaviour pattern as the rest of the codebase: with no secret set the filter
 * stands down (and says so loudly at startup), which keeps local dev and tests infra-free.
 */
@Component
class WebhookAuthFilter(
    @Value("\${webhook.secret:}") private val secret: String,
    @Value("\${webhook.header-name:X-Webhook-Secret}") private val headerName: String,
) : OncePerRequestFilter() {

    private val expected = secret.toByteArray(StandardCharsets.UTF_8)

    init {
        if (secret.isBlank()) {
            logger.warn(
                "webhook.secret is not set — $PROTECTED_PATH is UNAUTHENTICATED. That is fine for " +
                    "local development; set WEBHOOK_SECRET before exposing this service publicly.",
            )
        } else {
            logger.info("$PROTECTED_PATH requires the $headerName header")
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        secret.isBlank() || !request.requestURI.startsWith(PROTECTED_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val provided = (request.getHeader(headerName) ?: "").toByteArray(StandardCharsets.UTF_8)
        // Constant-time for equal-length inputs, so a caller can't discover the secret one
        // character at a time by timing our replies.
        if (!MessageDigest.isEqual(expected, provided)) {
            logger.warn(
                "Rejected ${request.method} ${request.requestURI} from ${request.remoteAddr}: " +
                    "missing or wrong $headerName",
            )
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid $headerName")
            return
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        /** The only endpoint that is reachable from the public internet. */
        const val PROTECTED_PATH = "/api/emails"
    }
}
