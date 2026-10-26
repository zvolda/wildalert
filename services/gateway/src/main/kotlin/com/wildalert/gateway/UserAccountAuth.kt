package com.wildalert.gateway

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import java.io.IOException

/**
 * Authorises the gateway's outbound calls to user-account. Same swappable-interface shape used
 * everywhere else here: no bean at all is the local default (user-account is open on localhost),
 * and the real implementation is switched on by config.
 *
 * This is the third copy of this ~30 lines (email-ingestion's was deleted when it stopped calling
 * user-account; notification has the other). Still deliberately copied rather than extracted:
 * it is infrastructure with no domain in it, and a shared module between otherwise independent
 * services buys coupling for very little. The rule of three has now been reached, though — if a
 * fourth service needs it, extract it.
 */
fun interface UserAccountAuth {

    /** Adds whatever credential user-account requires to an outgoing request. */
    fun authorize(headers: HttpHeaders)
}

/**
 * Cloud Run authentication for a private service. Cloud Run rejects us with 403 — in front of the
 * container, before any application code runs — unless we present a Google-signed **ID token
 * whose audience is the target service's URL**. An access token is not enough: the audience is
 * what proves the token was minted for this service rather than replayed from another.
 *
 * On Cloud Run the token comes from the instance metadata server via Application Default
 * Credentials, so there is no key to manage. The only setup is `roles/run.invoker` on
 * user-account for this service's service account.
 *
 * Active only when `services.user-account.auth=gcp-id-token`, so tests and local dev never touch
 * ADC. Construction fails fast: credentials that cannot mint ID tokens are a deployment mistake,
 * and a revision that refuses to start is easier to notice than one that 403s every registration.
 */
@Component
@ConditionalOnProperty(name = ["services.user-account.auth"], havingValue = "gcp-id-token")
class GcpIdTokenUserAccountAuth(
    @Value("\${services.user-account.base-url}") baseUrl: String,
) : UserAccountAuth {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Cloud Run matches the audience against the service URL, so it must carry no path. */
    private val audience = baseUrl.trimEnd('/')

    private val credentials: IdTokenCredentials = IdTokenCredentials.newBuilder()
        .setIdTokenProvider(idTokenProvider())
        .setTargetAudience(audience)
        .build()

    init {
        log.info("Calls to user-account will carry a Google ID token for audience {}", audience)
    }

    override fun authorize(headers: HttpHeaders) {
        // getRequestMetadata() caches the token and refreshes it shortly before it expires, so
        // this is not a network call per request.
        val metadata = try {
            credentials.requestMetadata
        } catch (ex: IOException) {
            // A metadata-server hiccup is transient. Surfacing it as a 503 to the browser is
            // honest and retryable; pretending the registration failed for a bad-request reason
            // would not be.
            throw IdTokenUnavailableException("Could not mint an ID token for $audience", ex)
        }
        metadata.forEach { (name, values) -> values.forEach { value -> headers.add(name, value) } }
    }

    private fun idTokenProvider(): IdTokenProvider {
        val credentials = GoogleCredentials.getApplicationDefault()
        return credentials as? IdTokenProvider
            ?: throw IllegalStateException(
                "Application Default Credentials (${credentials.javaClass.simpleName}) cannot mint ID " +
                    "tokens. On Cloud Run they can; locally, leave services.user-account.auth unset or " +
                    "use `gcloud auth application-default login` with an impersonated service account.",
            )
    }
}

/** Thrown when the metadata server will not give us a token; mapped to 503 by [ApiRoutes]. */
class IdTokenUnavailableException(message: String, cause: Throwable) : RuntimeException(message, cause)
