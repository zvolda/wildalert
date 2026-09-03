package com.wildalert.emailingestion.email

import jakarta.activation.DataHandler
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Properties

/** Builds realistic raw emails for tests. */
object TestEmails {

    // A valid 1x1 PNG.
    private val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

    fun withImageAttachment(
        from: String = "hunter@example.com",
        subject: String = "Trail cam photo",
        filename: String = "boar.png",
    ): ByteArray {
        val message = MimeMessage(Session.getDefaultInstance(Properties()))
        message.setFrom(InternetAddress(from))
        message.subject = subject

        val text = MimeBodyPart().apply { setText("A new photo from the field.") }
        val image = MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(ONE_PIXEL_PNG, "image/png"))
            fileName = filename
            setDisposition(Part.ATTACHMENT)
        }

        message.setContent(MimeMultipart().apply { addBodyPart(text); addBodyPart(image) })
        message.saveChanges()

        return ByteArrayOutputStream().use { message.writeTo(it); it.toByteArray() }
    }
}
