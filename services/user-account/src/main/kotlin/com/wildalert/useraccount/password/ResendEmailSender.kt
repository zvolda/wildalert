package com.wildalert.useraccount.password

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Real [EmailSender] backed by [Resend](https://resend.com).
 *
 * One POST to `/emails` with a bearer key — no OAuth exchange, unlike GoSMS, so there is no token
 * to cache and nothing to refresh. Active only when `email.provider=resend`, so the logging fake
 * stays the default and no test needs a key.
 *
 * **It deliberately does not retry.** A failed send is caught by [PasswordResetService], which
 * discards the unsent token so the hunter can ask again immediately — a retry loop here would
 * hold the request open while the caller waits for a 202 that says nothing about delivery anyway.
 * A hunter clicking the button again is a better retry than one we invent.
 */
@Component
@ConditionalOnProperty(name = ["email.provider"], havingValue = "resend")
class ResendEmailSender(
    builder: RestClient.Builder,
    private val props: ResendProperties,
    private val email: EmailProperties,
) : EmailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client = builder.baseUrl(props.baseUrl.trimEnd('/')).build()

    init {
        // Fail at startup, not at the first hunter who forgets their password. The readiness
        // probe includes the context, so a missing key means the revision never serves -- which
        // is a deploy that visibly did not happen, rather than a reset flow that silently cannot
        // send. Same instinct as INBOUND_EMAIL_DOMAIN, which was unset once and nobody was texted.
        require(props.apiKey.isNotBlank()) {
            "email.provider=resend but email.resend.api-key is empty -- set the RESEND_API_KEY secret"
        }
        log.info("Resend email sender initialised (from={}, baseUrl={})", email.fromAddress, props.baseUrl)
    }

    override fun send(message: EmailMessage) {
        val response = client.post()
            .uri("/emails")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${props.apiKey}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                ResendRequest(
                    from = email.fromAddress,
                    to = listOf(message.to),
                    subject = message.subject,
                    text = message.body,
                ),
            )
            .retrieve()
            .body(ResendResponse::class.java)

        // Logged without the recipient's message: the id is what Resend's dashboard is searched
        // by when someone asks "did my reset email go out?", and it is not a credential. The body
        // never appears in any log -- see LoggingEmailSender for why that rule exists.
        log.info("Resend accepted the email (id={})", response?.id ?: "unknown")
    }

    /**
     * `text` rather than `html`: the reset mail is plain text, which no provider has to rewrite
     * and no client can mangle into an unclickable link. `to` is a list because that is Resend's
     * shape, even for one recipient.
     */
    private data class ResendRequest(
        val from: String,
        val to: List<String>,
        val subject: String,
        val text: String,
    )

    /** Resend answers `{"id":"..."}` on success. Everything else arrives as an exception. */
    private data class ResendResponse(val id: String? = null)
}
