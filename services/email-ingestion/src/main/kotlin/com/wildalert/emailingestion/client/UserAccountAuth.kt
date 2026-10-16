package com.wildalert.emailingestion.client

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import java.io.IOException

/**
 * Authorises our outbound calls to user-account. Same swappable-interface shape as `ImageStore`
 * and `EventPublisher`: no bean at all is the local default (user-account is open on localhost),
 * and the real implementation is switched on by config.
 */
fun interface UserAccountAuth {

    /** Adds whatever credential user-account requires to an outgoing request. */
    fun authorize(headers: HttpHeaders)
}

/**
 * Cloud Run authentication for a private service. user-account is deployed with **Require
 * authentication** because it serves hunters' phone numbers, which means Cloud Run rejects us
 * with 403 unless we present a Google-signed **ID token whose audience is the target service's
 * URL** (an access token is not enough — it is the audience that proves the token was minted
 * for this service and not replayed from another).
 *
 * On Cloud Run the token comes from the instance metadata server via Application Default
 * Credentials, so there is no key to manage: the only setup is `roles/run.invoker` for this
 * service's service account on user-account.
 *
 * Active only when `services.user-account.auth=gcp-id-token`, so tests and local dev never
 * touch ADC. Construction fails fast: credentials that cannot mint ID tokens are a deployment
 * mistake, and a revision that refuses to start is easier to spot than one that silently
 * matches no hunters.
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
        // getRequestMetadata() caches the token and refreshes it shortly before it expires.
        val metadata = try {
            credentials.requestMetadata
        } catch (ex: IOException) {
            // Wrapped so HunterLookupClient's retry/degrade path handles it: an unmintable token
            // must not fail the webhook, or the photo is lost rather than just its SMS.
            throw RestClientException("Could not mint an ID token for $audience", ex)
        }
        metadata.forEach { (name, values) -> values.forEach { value -> headers.add(name, value) } }
    }

    private fun idTokenProvider(): IdTokenProvider {
        val credentials = GoogleCredentials.getApplicationDefault()
        return credentials as? IdTokenProvider
            ?: throw IllegalStateException(
                "Application Default Credentials (${credentials.javaClass.simpleName}) cannot mint ID " +
                    "tokens. On Cloud Run they can; locally, unset services.user-account.auth or use " +
                    "`gcloud auth application-default login` with an impersonated service account.",
            )
    }
}
