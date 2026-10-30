package com.wildalert.useraccount.password

/** A single transactional email to send. Plain text: nothing here needs HTML. */
data class EmailMessage(
    val to: String,
    val subject: String,
    val body: String,
)

/**
 * Abstraction over the email provider, following the house rule (AGENTS.md, "Core conventions"):
 * interface, fake default, real implementation chosen by config. Mirrors `SmsSender` in
 * notification and `ImageStore` in email-ingestion.
 *
 * **This is the project's first outbound email of any kind.** Cloudflare Email Routing, which
 * handles the inbound trail-cam mail, is inbound-only. Sending is not blocked by that -- the apex
 * MX governs inbound mail, and a sending provider needs an SPF include plus DKIM records
 * alongside it -- but no provider is wired up yet, which is why the only implementation in the
 * repository today is the logging one. See docs/password-reset.md for the provider options and
 * the DNS that goes with them.
 *
 * Money's "no fake, no off switch" exception (AGENTS.md) deliberately does **not** apply here:
 * nothing in this path moves credit, and a reset mail that is only logged costs a hunter nothing
 * but a support question.
 */
interface EmailSender {
    fun send(message: EmailMessage)
}
