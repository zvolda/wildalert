package com.wildalert.emailingestion.storage

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * ImageStore that writes images to a local folder as `<root>/<key>`, using the same key layout
 * as R2. Unlike the logging fake the bytes really exist, so the recognition service can read
 * them back — this lets the whole pipeline run locally with no cloud bucket. Active only when
 * storage.provider=filesystem.
 */
@Component
@ConditionalOnProperty(name = ["storage.provider"], havingValue = "filesystem")
class FileSystemImageStore(
    props: FileSystemProperties,
) : ImageStore {

    private val log = LoggerFactory.getLogger(javaClass)

    private val root: Path = Path.of(props.root).toAbsolutePath().normalize()

    init {
        log.info("Filesystem image store initialised (root={})", root)
    }

    override fun store(bytes: ByteArray, contentType: String?, filename: String?): StoredImage {
        val key = ObjectKeys.newKey(contentType, filename)
        val target = root.resolve(key)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        log.info("Stored image on disk: key={} size={} path={}", key, bytes.size, target)
        return StoredImage(key = key, sizeBytes = bytes.size)
    }
}
