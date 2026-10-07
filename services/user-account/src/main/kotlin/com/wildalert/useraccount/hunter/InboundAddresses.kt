package com.wildalert.useraccount.hunter

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * Personal inbound email addresses: every hunter gets `<token>@<inbound domain>` and forwards (or
 * has the camera send) trail-cam emails there. The recipient then identifies the hunter, whoever
 * the sender is — automatic forwarding keeps the camera's address in `From`, and `From` is easy
 * to spoof, so it can't be trusted for matching.
 *
 * Only the token is stored; the domain comes from config because it differs per environment.
 */
@Component
class InboundAddresses(
    @Value("\${hunters.inbound-domain}") domain: String,
) {

    val domain: String = domain.trim().lowercase()

    fun addressFor(hunter: Hunter): String = "${hunter.inboundToken}@$domain"

    /** The token from an address on our inbound domain, or null for any other address. */
    fun tokenOf(address: String): String? {
        val normalized = address.trim().lowercase()
        val at = normalized.lastIndexOf('@')
        if (at <= 0) return null
        val token = normalized.substring(0, at)
        return token.takeIf { normalized.substring(at + 1) == domain && TOKEN.matches(it) }
    }

    companion object {
        // No look-alike characters (0/o, 1/l), so an address read aloud or retyped still works.
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        private const val TOKEN_LENGTH = 12 // 32^12 = 2^60 combinations: not guessable
        private val TOKEN = Regex("^[a-z0-9]{1,32}$")
        private val random = SecureRandom()

        fun newToken(): String =
            (1..TOKEN_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}
