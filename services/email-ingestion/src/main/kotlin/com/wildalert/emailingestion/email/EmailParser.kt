package com.wildalert.emailingestion.email

import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.util.Properties

/** An image pulled out of an email. */
data class ExtractedImage(
    val filename: String?,
    val contentType: String?,
    val bytes: ByteArray,
)

/** The bits of a forwarded email we care about. */
data class ExtractedEmail(
    val from: String?,
    // Every address the email was delivered or addressed to, most reliable first (see parse).
    val recipients: List<String>,
    val subject: String?,
    val images: List<ExtractedImage>,
)

/** Parses a raw RFC-822 email (as delivered by Cloudflare Email Routing) into its parts. */
@Component
class EmailParser {

    fun parse(raw: ByteArray): ExtractedEmail {
        val session = Session.getDefaultInstance(Properties())
        val message = MimeMessage(session, ByteArrayInputStream(raw))

        val from = (message.from?.firstOrNull() as? InternetAddress)?.address
        val images = mutableListOf<ExtractedImage>()
        collectImages(message, images)

        return ExtractedEmail(
            from = from,
            recipients = recipients(message),
            subject = message.subject,
            images = images,
        )
    }

    /**
     * Candidate recipients in priority order. Delivery headers added by receiving mail servers
     * (Delivered-To, X-Original-To) name the mailbox it actually reached — when a hunter's
     * forwarding rule sends it on, To/Cc still show the hunter's own address, not ours.
     */
    private fun recipients(message: MimeMessage): List<String> {
        val delivered = listOf("Delivered-To", "X-Original-To")
            .flatMap { name -> message.getHeader(name)?.toList().orEmpty() }
            .flatMap { value -> parseAddresses(value) }
        val addressed = listOf(Message.RecipientType.TO, Message.RecipientType.CC)
            .flatMap { type -> runCatching { message.getRecipients(type)?.toList() }.getOrNull().orEmpty() }
            .mapNotNull { (it as? InternetAddress)?.address }
        return (delivered + addressed).distinctBy { it.lowercase() }
    }

    // Lenient: a malformed header shouldn't lose the whole email.
    private fun parseAddresses(value: String): List<String> =
        runCatching { InternetAddress.parseHeader(value, false).mapNotNull { it.address } }
            .getOrDefault(emptyList())

    /** Walks the (possibly nested) MIME tree, collecting every image part. */
    private fun collectImages(part: Part, out: MutableList<ExtractedImage>) {
        when {
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                for (i in 0 until multipart.count) {
                    collectImages(multipart.getBodyPart(i), out)
                }
            }

            part.isMimeType("image/*") -> {
                out.add(
                    ExtractedImage(
                        filename = part.fileName,
                        contentType = part.contentType?.substringBefore(';')?.trim(),
                        bytes = part.inputStream.readBytes(),
                    ),
                )
            }
        }
    }
}
