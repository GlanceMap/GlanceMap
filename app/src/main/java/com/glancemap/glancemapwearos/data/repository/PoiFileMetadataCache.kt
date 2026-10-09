package com.glancemap.glancemapwearos.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal class PoiFileMetadataCache<T>(
    private val maxEntries: Int = 64,
) {
    private data class Key(
        val path: String,
        val categoryIds: Set<Int>,
    )

    private data class Signature(
        val size: Long,
        val modified: FileTime,
        val fileKey: Any?,
    )

    private data class Entry<T>(
        val signature: Signature,
        val value: T,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, Entry<T>>(16, 0.75f, true)
    private var invalidationGeneration = 0L

    init {
        require(maxEntries > 0)
    }

    suspend fun getOrLoad(
        file: File,
        categoryIds: Set<Int> = emptySet(),
        load: suspend () -> T,
    ): T {
        val key = Key(file.absolutePath, categoryIds.toSet())
        val signature = signatureOf(file)
        val (generation, cached) =
            mutex.withLock {
                val cachedEntry = entries[key]?.takeIf { signature != null && it.signature == signature }
                if (cachedEntry == null) entries.remove(key)
                invalidationGeneration to cachedEntry
            }
        if (cached != null) return cached.value

        val value = load()
        val unchanged = signature != null && signature == signatureOf(file)
        // Keep database reads outside the lock so imports can invalidate without waiting for a scan.
        mutex.withLock {
            if (unchanged && generation == invalidationGeneration) {
                entries[key] = Entry(signature, value)
                if (entries.size > maxEntries) entries.remove(entries.keys.first())
            }
        }
        return value
    }

    suspend fun invalidate(path: String) {
        mutex.withLock {
            invalidationGeneration += 1
            entries.keys.removeAll { it.path == File(path).absolutePath }
        }
    }

    private fun signatureOf(file: File): Signature? =
        runCatching {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            Signature(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        }.getOrNull()
}
