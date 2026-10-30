package com.wildalert.gateway

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Everything the internet is allowed to reach on the private services, in one file.
 *
 * **The allowlist is the security model here, so it is deliberately narrow.** `user-account`
 * exposes several endpoints that each return a hunter's **phone number**, and a convenient
 * wildcard route under `/api/hunters` would publish every one of them to anyone who guessed the
 * URL. So each route is an exact method and path that the React app actually needs:
 *
 * - `POST /api/hunters` — register (and, since it now also issues a session, log in)
 * - `POST /api/sessions` — log in
 * - `DELETE /api/sessions` — log out
 * - `GET /api/me` — the signed-in hunter's own record
 * - `POST /api/me/password` — change your own password (user-account requires the session)
 * - `POST /api/password-resets` and `POST /api/password-resets/confirm` — the forgotten-password
 *   flow. **These two are the only endpoints here that an anonymous caller is *meant* to reach
 *   and that change state**, which is what makes them the ones wanting Turnstile in front; the
 *   reset request is also the first thing the edge proxies that can cause an email to be sent.
 *
 * Deliberately **still not** proxied, and each for the original reason:
 * - `GET /api/hunters/{id}`
 * - `GET /api/hunters/by-email`
 * - `GET /api/hunters/by-inbound-address`
 * - `POST /api/hunters/by-inbound-address/charges` and `POST /api/hunters/{id}/charges` — these
 *   also *spend the hunter's credit*, so reaching them from a browser would let anyone drain an
 *   account as well as read a phone number.
 *
 * `GET /api/me` is what makes keeping those closed sustainable rather than merely strict. The
 * earlier note here said a hunter reading their own record "needs authentication first, which
 * this service cannot yet do" — and it still cannot, which is the point: user-account
 * authenticates the hunter from a session cookie, and this service only forwards it. The identity
 * is asserted by the service that owns the hunter table, never by the edge.
 *
 * The cookie rides along in both directions because [withCredential] *adds* a header rather than
 * replacing the header set; `ApiRoutesTest` pins that, because a proxy silently dropping
 * `Set-Cookie` would look exactly like a broken login.
 *
 * Anything not matched here falls through to the static React app, and an unmatched API path
 * ends up a 404 from this service — it never reaches user-account at all.
 */
// proxyBeanMethods = false: no CGLIB subclass. Needed as well as preferred here -- the Kotlin
// default value on `auth` below leaves no no-arg constructor for CGLIB to call, which fails at
// startup with "No default constructor found". It is also simply correct: this class declares one
// @Bean and calls no other @Bean method, so the proxy would buy nothing.
@Configuration(proxyBeanMethods = false)
class ApiRoutes(
    @Value("\${services.user-account.base-url}") private val userAccountBaseUrl: String,
    /**
     * Absent locally (user-account is open on localhost), present in the cloud. A missing
     * credential in the cloud is not a subtle failure: Cloud Run answers 403 to every
     * registration.
     */
    private val auth: UserAccountAuth? = null,
) {

    @Bean
    fun userAccountRoutes(): RouterFunction<ServerResponse> =
        route("user-account")
            // http() with no argument takes its target from the `uri` before-filter below.
            // Passing the URI to http() directly is deprecated.
            .POST("/api/hunters", http())
            .POST("/api/sessions", http())
            .DELETE("/api/sessions", http())
            .GET("/api/me", http())
            .POST("/api/me/password", http())
            .POST("/api/password-resets", http())
            .POST("/api/password-resets/confirm", http())
            .before(uri(userAccountBaseUrl))
            .before(::withCredential)
            .build()

    /**
     * Copies the credential headers onto the proxied request. Returns the request untouched when
     * there is no [UserAccountAuth] bean, which is how local development and the tests run
     * against a plain, open user-account with no Google involvement at all.
     */
    private fun withCredential(request: ServerRequest): ServerRequest {
        val credential = auth ?: return request
        val headers = HttpHeaders()
        credential.authorize(headers)
        val builder = ServerRequest.from(request)
        headers.forEach { (name, values) -> values.forEach { value -> builder.header(name, value) } }
        return builder.build()
    }
}
