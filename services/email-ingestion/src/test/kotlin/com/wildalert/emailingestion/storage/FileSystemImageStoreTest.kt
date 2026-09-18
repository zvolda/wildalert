package com.wildalert.emailingestion.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileSystemImageStoreTest {

    @TempDir
    lateinit var root: Path

    private val bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Test
    fun `writes the bytes to root slash key`() {
        val store = FileSystemImageStore(FileSystemProperties(root = root.toString()))

        val stored = store.store(bytes, contentType = "image/png", filename = "boar.png")

        // The recognition service relies on exactly this layout: <root>/<key>, raw bytes.
        val file = root.resolve(stored.key)
        assertThat(file).exists()
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes)
        assertThat(stored.sizeBytes).isEqualTo(bytes.size)
    }

    @Test
    fun `uses the shared date-partitioned key layout`() {
        val store = FileSystemImageStore(FileSystemProperties(root = root.toString()))

        val stored = store.store(bytes, contentType = "image/jpeg", filename = null)

        assertThat(stored.key).matches("inbound/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.jpg")
    }

    @Test
    fun `creates the root folder if it does not exist yet`() {
        val missingRoot = root.resolve("not/created/yet")
        val store = FileSystemImageStore(FileSystemProperties(root = missingRoot.toString()))

        val stored = store.store(bytes, contentType = "image/png", filename = null)

        assertThat(missingRoot.resolve(stored.key)).exists()
    }
}
