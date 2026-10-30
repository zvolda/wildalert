package com.wildalert.useraccount.password

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Everything configurable about the reset flow, bound in one place.
 *
 * [publicBaseUrl] is the one with a security reason rather than a convenience reason behind it.
 * The link in the mail must be built from configuration and **never from the inbound `Host`
 * header**: a header-derived link is the classic password-reset-poisoning vector, where an
 * attacker triggers a reset for someone else's address with a `Host` of their own and the real
 * hunter's mail arrives pointing at the attacker's site.
 */
@ConfigurationProperties(prefix = "password-reset")
data class PasswordResetProperties(
    /** How long a link works. Short on purpose -- a mail sits in an inbox indefinitely. */
    val ttl: Duration = Duration.ofHours(1),

    /**
     * How long a hunter must wait before a second mail is sent. Protects a real hunter's inbox
     * from being used as a relay by someone entering their address repeatedly. It is a courtesy
     * throttle, not the abuse control -- that is Turnstile, which is still an open item and
     * belongs in front of this form too.
     */
    val throttle: Duration = Duration.ofMinutes(5),

    /** Where the reset link points. Configuration, never the request's `Host` -- see above. */
    val publicBaseUrl: String = "http://localhost:5173",

    /** The `From` address on the mail. Must be on a domain the sending provider is set up for. */
    val fromAddress: String = "no-reply@wildalert.app",
)
