package com.wildalert.gateway

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Exercises the gateway against a stub standing in for user-account, so the proxying is tested
 * for real (a request leaves the JVM over HTTP and comes back) without needing Postgres or the
 * actual service. The stub is a plain JDK `HttpServer` -- no extra test dependency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiRoutesTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @BeforeEach
    fun reset() {
        received.clear()
    }

    @Test
    fun `forwards a registration to user-account and returns its response unchanged`() {
        val body =
            """{"email":"hunter@example.com","phone":"+420123456789","password":"correct-horse"}"""

        val response = rest.exchange("/api/hunters", HttpMethod.POST, jsonEntity(body), String::class.java)

        // The upstream's 201 and body reach the browser as-is: the gateway is a pipe, not a
        // translator. The React app parses user-account's response, so anything else would break it.
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body).contains(""""inboundAddress":"abc123@wildalert.local"""")

        assertThat(received).hasSize(1)
        val upstreamCall = received.single()
        assertThat(upstreamCall.method).isEqualTo("POST")
        // Path is forwarded verbatim -- no rewriting, so user-account's own mapping is unchanged.
        assertThat(upstreamCall.path).isEqualTo("/api/hunters")
        assertThat(upstreamCall.body).isEqualTo(body)
    }

    @Test
    fun `attaches no credential when there is no auth bean`() {
        rest.exchange("/api/hunters", HttpMethod.POST, jsonEntity("""{"email":"a@b"}"""), String::class.java)

        // Local dev and these tests run against an open user-account. If an Authorization header
        // appeared here it would mean the gateway had quietly acquired Google credentials.
        assertThat(received.single().authorization).isNull()
    }

    @Test
    fun `does not proxy the lookup endpoints, which expose phone numbers`() {
        // The whole point of the narrow allowlist in ApiRoutes. These are real endpoints on
        // user-account; if the route were /api/hunters/** they would be public.
        val blocked = listOf(
            "/api/hunters/by-email?email=hunter@example.com",
            "/api/hunters/by-inbound-address?address=abc123@wildalert.local",
            "/api/hunters/11111111-1111-1111-1111-111111111111",
            // The charge endpoints return a phone number *and* spend credit, so reaching them
            // from a browser would let anyone drain an account.
            "/api/hunters/by-inbound-address/charges?address=abc123@wildalert.local",
            "/api/hunters/11111111-1111-1111-1111-111111111111/charges",
        )

        for (path in blocked) {
            val response = rest.getForEntity(path, String::class.java)
            assertThat(response.statusCode)
                .describedAs("GET %s must not be proxied", path)
                .isEqualTo(HttpStatus.NOT_FOUND)
        }

        // Not merely rejected after the fact -- the request never left this service.
        assertThat(received).isEmpty()
    }

    @Test
    fun `does not proxy other methods on the register path`() {
        // PUT /api/hunters/{id} updates a hunter. Only POST is allowed through.
        val response = rest.exchange(
            "/api/hunters/11111111-1111-1111-1111-111111111111",
            HttpMethod.PUT,
            jsonEntity("""{"phone":"+420999999999"}"""),
            String::class.java,
        )

        assertThat(response.statusCode.is4xxClientError).isTrue()
        assertThat(received).isEmpty()
    }

    @Test
    fun `a Set-Cookie from user-account reaches the browser intact`() {
        // The quiet failure this guards against: if the proxy dropped or rewrote Set-Cookie, the
        // login would answer 200 with the right body and simply never log anybody in -- and
        // nothing in user-account's logs would show a problem.
        val response = rest.exchange(
            "/api/sessions",
            HttpMethod.POST,
            jsonEntity("""{"email":"hunter@example.com","password":"correct-horse"}"""),
            String::class.java,
        )

        val setCookie = response.headers[HttpHeaders.SET_COOKIE]
        assertThat(setCookie).isNotNull().hasSize(1)
        assertThat(setCookie!!.single())
            .contains("wa_session=a-session-token")
            // The attributes matter as much as the value: lose HttpOnly or SameSite and the
            // cookie still works while its protections are gone.
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .contains("Path=/")
    }

    @Test
    fun `a session cookie from the browser reaches user-account`() {
        // The other direction. Without this, GET /api/me would arrive unauthenticated and answer
        // 401 for a hunter who is in fact logged in.
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "wa_session=a-session-token") }

        rest.exchange("/api/me", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertThat(received.single().cookie).isEqualTo("wa_session=a-session-token")
    }

    @Test
    fun `proxies the session routes the React app needs`() {
        data class Call(val method: HttpMethod, val path: String)
        val allowed = listOf(
            Call(HttpMethod.POST, "/api/sessions"),
            Call(HttpMethod.DELETE, "/api/sessions"),
            Call(HttpMethod.GET, "/api/me"),
        )

        for (call in allowed) {
            received.clear()
            rest.exchange(call.path, call.method, jsonEntity("{}"), String::class.java)
            assertThat(received)
                .describedAs("%s %s must reach user-account", call.method, call.path)
                .hasSize(1)
            assertThat(received.single().path).isEqualTo(call.path)
        }
    }

    @Test
    fun `keeps the health endpoint open for Cloud Run probes`() {
        assertThat(rest.getForEntity("/actuator/health", String::class.java).statusCode)
            .isEqualTo(HttpStatus.OK)
    }

    private fun jsonEntity(body: String): HttpEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return HttpEntity(body, headers)
    }

    data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String,
        val authorization: String?,
        val cookie: String?,
    )

    companion object {
        private val received = mutableListOf<RecordedRequest>()

        /**
         * Initialised on first access, which is [userAccountBaseUrl] below -- so the stub is
         * listening before Spring builds the context that points at it.
         */
        private val upstream: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/", ::handle)
                executor = null
                start()
            }

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            received += RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                body = body,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                cookie = exchange.requestHeaders.getFirst("Cookie"),
            )

            // Kept in step with user-account's real HunterResponse. It previously still carried
            // "plan":"FREE" -- a field dropped in migration V3 -- and no creditBalanceMinor,
            // which the test did not notice because it only asserted on inboundAddress. A stub
            // that has drifted from the contract tests nothing.
            val response = """
                {"id":"22222222-2222-2222-2222-222222222222","email":"hunter@example.com",
                "phone":"+420123456789","active":true,
                "inboundAddress":"abc123@wildalert.local",
                "creditBalanceMinor":2500,"alertPriceMinor":250,
                "createdAt":"2026-10-26T12:00:00Z","updatedAt":"2026-10-26T12:00:00Z"}
            """.trimIndent().replace("\n", "")
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            // Every stub response sets a cookie, so any proxied route can be checked for the one
            // thing that would silently break logging in.
            exchange.responseHeaders.add("Set-Cookie", SESSION_COOKIE)
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private const val SESSION_COOKIE =
            "wa_session=a-session-token; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax"

        @JvmStatic
        @DynamicPropertySource
        fun userAccountBaseUrl(registry: DynamicPropertyRegistry) {
            registry.add("services.user-account.base-url") {
                "http://127.0.0.1:${upstream.address.port}"
            }
        }
    }
}
