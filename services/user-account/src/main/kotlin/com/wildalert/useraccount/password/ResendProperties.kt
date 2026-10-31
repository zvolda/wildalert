package com.wildalert.useraccount.password

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Settings shared by every [EmailSender], bound from `email.*`.
 *
 * `fromAddress` lives here rather than on the provider because it is a property of the **domain**,
 * not of whoever is carrying the message: it is the address the DNS records verify, and it should
 * not change if the provider does.
 *
 * It began life on `PasswordResetProperties` as `password-reset.from-address` and was **never
 * read** — dead config, found when the first real sender needed it. Worth the note: a field that
 * nothing consumes looks exactly like a working setting to anyone reading `application.yml`.
 */
@ConfigurationProperties(prefix = "email")
data class EmailProperties(
    val fromAddress: String = "WildAlert <no-reply@wildalert.app>",
)

/**
 * Resend settings, bound from `email.resend.*`.
 *
 * Chosen over Postmark and SES on 2026-10-31: one HTTPS POST, a single API key, and DKIM records
 * it generates for you — the least new surface for a project whose first outbound email this is.
 * Cloudflare Email Sending would have added no vendor but would have had to go through a Worker,
 * since it is a Worker binding rather than an API.
 */
@ConfigurationProperties(prefix = "email.resend")
data class ResendProperties(
    /**
     * The API key. Empty by default so nothing has to be set for the logging fake, and an empty
     * value is rejected loudly at startup when `email.provider=resend` — see [ResendEmailSender].
     */
    val apiKey: String = "",
    /** Overridable so tests can point at a mock server rather than the real API. */
    val baseUrl: String = "https://api.resend.com",
)
