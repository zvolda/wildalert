package com.wildalert.emailingestion.storage

import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Where an image ended up: the object key it was written under, and its size. */
data class StoredImage(
    val key: String,
    val sizeBytes: Int,
)

/**
 * Abstraction over image object storage. Everything talks to this interface, so we can swap
 * the fake logging store for Cloudflare R2 (or AWS S3 / MinIO) without touching callers.
 */
interface ImageStore {
    /** Stores the image bytes and returns the object key it was written under. */
    fun store(bytes: ByteArray, contentType: String?, filename: String?): StoredImage
}

/**
 * Builds object keys shared by every store implementation, so a photo lands under the same
 * layout no matter the backend: `inbound/yyyy/MM/dd/<uuid>.<ext>` — date-partitioned (easy to
 * browse/expire) and UUID-named (no collisions between hunters or duplicate filenames).
 */
object ObjectKeys {

    private val datePath = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC)

    fun newKey(contentType: String?, filename: String?, clock: Clock = Clock.systemUTC()): String {
        val date = datePath.format(clock.instant())
        return "inbound/$date/${UUID.randomUUID()}${extensionFor(contentType, filename)}"
    }

    /** ".png" / ".jpg" / "" — prefers the content type, falls back to the filename's extension. */
    private fun extensionFor(contentType: String?, filename: String?): String {
        val raw = contentType?.substringAfter('/', "")?.lowercase()?.takeIf { it.isNotBlank() }
            ?: filename?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
        val ext = if (raw == "jpeg") "jpg" else raw
        return if (ext.isNullOrBlank()) "" else ".$ext"
    }
}
