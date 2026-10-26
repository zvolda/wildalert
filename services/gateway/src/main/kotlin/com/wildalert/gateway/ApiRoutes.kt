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
 * exposes `GET /api/hunters/{id}`, `GET /api/hunters/by-email` and
 * `GET /api/hunters/by-inbound-address`, and every one of them returns a hunter's **phone
 * number**. A convenient wildcard route under `/api/hunters` would publish all three to anyone
 * who guessed the URL. So the only route is the exact method and path the registration form
 * needs: `POST /api/hunters`.
 *
 * Anything not matched here falls through to the static React app, and an unmatched API path
 * ends up a 404 from this service — it never reaches user-account at all.
 *
 * Opening more routes is a Phase 9 job and needs authentication first: a hunter should be able to
 * read *their own* record, which means knowing who is asking, which this service cannot yet do.
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
