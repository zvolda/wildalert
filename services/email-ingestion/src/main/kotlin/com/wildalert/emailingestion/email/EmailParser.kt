package com.wildalert.emailingestion.email

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

        return ExtractedEmail(from = from, subject = message.subject, images = images)
    }

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
