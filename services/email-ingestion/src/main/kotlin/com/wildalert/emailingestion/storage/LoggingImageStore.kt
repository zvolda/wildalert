package com.wildalert.emailingestion.storage

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Fake ImageStore that just logs instead of uploading. Active by default
 * (storage.provider=logging or unset), so the app runs with no R2 bucket or credentials.
 */
@Component
@ConditionalOnProperty(name = ["storage.provider"], havingValue = "logging", matchIfMissing = true)
class LoggingImageStore : ImageStore {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun store(bytes: ByteArray, contentType: String?, filename: String?): StoredImage {
        val key = ObjectKeys.newKey(contentType, filename)
        log.info("[FAKE STORE] filename={} contentType={} size={} -> key={}", filename, contentType, bytes.size, key)
        return StoredImage(key = key, sizeBytes = bytes.size)
    }
}
