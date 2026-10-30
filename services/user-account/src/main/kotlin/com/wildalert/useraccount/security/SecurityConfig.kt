package com.wildalert.useraccount.security

import com.wildalert.useraccount.session.HunterSessionFilter
import com.wildalert.useraccount.session.SessionCookies
import com.wildalert.useraccount.session.SessionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * **Spring Security here guards the browser surface only.**
 *
 * That sentence is the whole design, and getting it wrong breaks production silently. Adding
 * `spring-boot-starter-security` to the classpath secures *every* endpoint by default, and
 * user-account serves endpoints that other services call with no cookie and no Spring Security
 * credential of any kind:
 *
 * - `POST /api/hunters/by-inbound-address/charges` and `POST /api/hunters/{id}/charges` are
 *   called by **notification** on every recognized animal. If these start answering 401, the
 *   money never moves, no SMS is sent, and nothing anywhere logs an error that names the cause.
 *   This is the single most important thing in this file.
 * - the hunter lookups (`by-email`, `by-inbound-address`, by id) are used from `deploy/CONSOLE.md`
 *   for manual operations.
 *
 * What actually protects those in production is **Cloud Run's "Require authentication"** plus the
 * gateway's deliberately tiny route allowlist -- a perimeter outside this application. So the
 * default here is `permitAll`, and only the two endpoints a *browser* uses to read a hunter's own
 * record are authenticated. Defaulting the other way would mean every future service-to-service
 * endpoint is one forgotten line away from a silent outage.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(
        http: HttpSecurity,
        sessions: SessionService,
        cookies: SessionCookies,
    ): SecurityFilterChain {
        http
            // No server-side HTTP session: identity comes from our own cookie on every request,
            // resolved against the hunter_session table. Spring must not start a JSESSIONID.
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }

            // CSRF off, and this is the justification rather than an oversight. The session
            // cookie is SameSite=Lax, which is what actually blocks the cross-site POST that CSRF
            // tokens defend against, and the only authenticated state-changing endpoint in this
            // slice is the logout. Revisit the moment the dashboard can spend money -- a top-up
            // button makes a token worth having.
            .csrf { it.disable() }

            // Nothing here is a browser-form or basic-auth login, and leaving the defaults on
            // would publish a /login page and a WWW-Authenticate prompt we never want.
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }

            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.GET, "/api/me").authenticated()
                it.requestMatchers(HttpMethod.DELETE, "/api/sessions").authenticated()
                // Changing your own password needs a session; the *reset* endpoints must not,
                // since being locked out is their entire premise. They are listed explicitly
                // rather than left to the permitAll default so that this block reads as the
                // complete answer to "what can an anonymous caller reach?" -- the two flows look
                // alike and are exposed oppositely, which is worth one line each to make obvious.
                it.requestMatchers(HttpMethod.POST, "/api/me/password").authenticated()
                it.requestMatchers(HttpMethod.POST, "/api/password-resets").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/password-resets/confirm").permitAll()
                // Everything else, deliberately. See the class comment.
                it.anyRequest().permitAll()
            }

            // Without this, a rejection from the filter chain answers in Spring Security's own
            // shape while a rejection from a controller answers as RFC 9457 -- the same failure
            // in two formats depending on which layer caught it.
            .exceptionHandling { it.authenticationEntryPoint(ProblemDetailAuthenticationEntryPoint()) }

            .addFilterBefore(
                HunterSessionFilter(sessions, cookies),
                UsernamePasswordAuthenticationFilter::class.java,
            )

        return http.build()
    }

    /**
     * Delegating, so the stored hash carries its own algorithm prefix (`{bcrypt}...`). Moving to
     * a different algorithm later is then a per-row matter with no migration and no flag day:
     * old hashes keep verifying while new ones are written the new way.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
}
