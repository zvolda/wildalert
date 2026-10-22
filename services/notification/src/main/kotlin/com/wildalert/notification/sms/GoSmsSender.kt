package com.wildalert.notification.sms

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Instant

/**
 * Real SmsSender backed by [GoSMS](https://gosms.eu) — a Czech gateway, chosen over Twilio
 * because Twilio charges $12/month to lease a number while GoSMS has no monthly fee at all
 * (and the Czech gateways that do are cheaper per message anyway).
 *
 * Two calls rather than one: GoSMS is OAuth2 client-credentials, so we exchange the client
 * id/secret for a bearer token and then post the message. The token is valid for an hour and
 * cached here, so a steady stream of alerts costs one token request per hour, not per SMS.
 *
 * Active only when `sms.provider=gosms`, so the fake logging sender remains the default and
 * tests never need credentials.
 */
@Component
@ConditionalOnProperty(name = ["sms.provider"], havingValue = "gosms")
class GoSmsSender(
    builder: RestClient.Builder,
    private val props: GoSmsProperties,
) : SmsSender {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client = builder.baseUrl(props.baseUrl.trimEnd('/')).build()

    /** Guards [cached] — Cloud Run serves concurrent pushes on one instance. */
    private val lock = Any()

    @Volatile
    private var cached: CachedToken? = null

    init {
        log.info(
            "GoSMS sender initialised (channel={}, baseUrl={})",
            props.channel,
            props.baseUrl,
        )
    }

    override fun send(message: SmsMessage): SmsResult =
        try {
            post(message, token())
        } catch (ex: HttpClientErrorException.Unauthorized) {
            // The cached token was rejected before we thought it expired — revoked, or clock
            // skew. Mint a fresh one and try once more; a second 401 is a real config problem
            // and is allowed to propagate so the push 5xxs and Pub/Sub redelivers.
            log.warn("GoSMS rejected the cached token; refreshing and retrying once")
            post(message, token(forceRefresh = true))
        }

    private fun post(message: SmsMessage, accessToken: String): SmsResult {
        val response = client.post()
            .uri("/api/v1/messages/")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer $accessToken")
            .body(
                mapOf(
                    "message" to message.body,
                    // The API takes a list even for one recipient.
                    "recipients" to listOf(message.to),
                    "channel" to props.channel,
                ),
            )
            .retrieve()
            .body(JsonNode::class.java)

        val id = messageId(response)
        log.info("GoSMS accepted the message for {} (id={})", message.to, id)
        return SmsResult(messageId = id)
    }

    /**
     * The id is informational — nothing downstream keys off it (idempotency uses the event id),
     * so an unexpected response shape must not fail a send that the gateway already accepted.
     * GoSMS returns the message object either at the root or under `data` depending on the
     * endpoint; both are tried, and anything else is logged rather than thrown.
     */
    private fun messageId(response: JsonNode?): String {
        val id = response?.path("id")?.takeIf { !it.isMissingNode && !it.isNull }
            ?: response?.path("data")?.path("id")?.takeIf { !it.isMissingNode && !it.isNull }
        if (id == null) {
            log.warn("GoSMS response carried no message id; body={}", response)
            return "unknown"
        }
        return id.asText()
    }

    private fun token(forceRefresh: Boolean = false): String {
        val current = cached
        if (!forceRefresh && current != null && current.isFresh()) return current.value
        synchronized(lock) {
            // Another thread may have refreshed it while we waited for the lock.
            val latest = cached
            if (!forceRefresh && latest != null && latest.isFresh()) return latest.value
            val fetched = fetchToken()
            cached = fetched
            return fetched.value
        }
    }

    private fun fetchToken(): CachedToken {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", props.clientId)
            add("client_secret", props.clientSecret)
            add("grant_type", "client_credentials")
        }

        val response = client.post()
            .uri("/oauth/v2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JsonNode::class.java)

        val accessToken = response?.path("access_token")?.asText()?.takeIf { it.isNotBlank() }
            ?: throw RestClientException("GoSMS returned no access_token")

        // Documented as an hour; trust what we are told and keep a minute of margin so a token
        // cannot expire in flight. Falls back to a short life if the field is missing. No lower
        // bound on purpose: if the margin eats the whole lifetime, the token is treated as
        // single-use rather than cached past the point the server says it is dead.
        val lifetimeSeconds = response.path("expires_in").asLong(600)
        val expiresAt = Instant.now().plusSeconds((lifetimeSeconds - 60).coerceAtLeast(0))
        log.debug("Fetched a GoSMS access token, valid for {}s", lifetimeSeconds)
        return CachedToken(accessToken, expiresAt)
    }

    private data class CachedToken(val value: String, val expiresAt: Instant) {
        fun isFresh() = Instant.now().isBefore(expiresAt)
    }
}
