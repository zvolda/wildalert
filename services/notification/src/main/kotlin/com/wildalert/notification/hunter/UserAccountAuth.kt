package com.wildalert.notification.hunter

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
 * Authorises our outbound calls to user-account. Same swappable-interface shape as `SmsSender`
 * and `ProcessedEvents`: no bean at all is the local default (user-account is open on localhost),
 * and the real implementation is switched on by config.
 *
 * Deliberately a copy of email-ingestion's class of the same name rather than a shared module.
 * It is ~30 lines of infrastructure with no domain in it, and the two services are otherwise
 * independent — the same call this project already makes for the event contracts. If a third
 * service needs it, extract it then.
 */
fun interface UserAccountAuth {

    /** Adds whatever credential user-account requires to an outgoing request. */
    fun authorize(headers: HttpHeaders)
}

/**
 * Cloud Run authentication for a private service. user-account is deployed with **Require
 * authentication** because it serves hunters' phone numbers, so Cloud Run rejects us with 403 —
 * in front of the application, before any of our code runs — unless we present a Google-signed
 * **ID token whose audience is the target service's URL**. An access token is not enough: it is
 * the audience that proves the token was minted for this service rather than replayed from
 * another.
 *
 * Without this, every lookup 403s. `HunterClient` catches only `NotFound`, so `Forbidden`
 * propagates, the push endpoint answers 5xx, Pub/Sub retries and the alert ends up in
 * `animal.recognized.dlt` — no SMS, and nothing obviously broken about the service.
 *
 * On Cloud Run the token comes from the instance metadata server via Application Default
 * Credentials, so there is no key to manage: the only setup is `roles/run.invoker` for this
 * service's service account on user-account.
 *
 * Active only when `services.user-account.auth=gcp-id-token`, so tests and local dev never touch
 * ADC. Construction fails fast: credentials that cannot mint ID tokens are a deployment mistake,
 * and a revision that refuses to start is easier to spot than one that quietly texts nobody.
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
            // A metadata-server hiccup is transient, so let it surface as a failed push: Pub/Sub
            // redelivers and the alert still goes out. Swallowing it here would lose the SMS.
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
