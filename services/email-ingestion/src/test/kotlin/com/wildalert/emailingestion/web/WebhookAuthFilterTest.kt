package com.wildalert.emailingestion.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class WebhookAuthFilterTest {

    private val header = "X-Webhook-Secret"
    private val response = MockHttpServletResponse()
    private val chain = MockFilterChain()

    @Test
    fun `lets the request through when no secret is configured`() {
        filter(secret = "").doFilter(webhookRequest(), response, chain)

        assertThat(chain.request).isNotNull()
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `lets the request through when the secret matches`() {
        val request = webhookRequest().apply { addHeader(header, "s3cret") }

        filter(secret = "s3cret").doFilter(request, response, chain)

        assertThat(chain.request).isNotNull()
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `rejects a request carrying the wrong secret`() {
        val request = webhookRequest().apply { addHeader(header, "guess") }

        filter(secret = "s3cret").doFilter(request, response, chain)

        assertThat(chain.request).isNull()
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `rejects a request with no secret header at all`() {
        filter(secret = "s3cret").doFilter(webhookRequest(), response, chain)

        assertThat(chain.request).isNull()
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `guards only the webhook, so health probes stay reachable`() {
        val probe = MockHttpServletRequest("GET", "/actuator/health/readiness")

        filter(secret = "s3cret").doFilter(probe, response, chain)

        assertThat(chain.request).isNotNull()
        assertThat(response.status).isEqualTo(200)
    }

    private fun filter(secret: String) = WebhookAuthFilter(secret, header)

    private fun webhookRequest() = MockHttpServletRequest("POST", "/api/emails")
}
