package com.glancemap.glancemapwearos.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal data class PoiMetadataSignature(
    val size: Long,
    val modified: FileTime,
    val fileKey: Any?,
)

internal enum class PoiMetadataCacheSource {
    MEMORY,
    DISK,
    DATABASE,
}

internal class PoiFileMetadataCache<T>(
    private val maxEntries: Int = 64,
    private val diskStore: PoiMetadataDiskStore<T>? = null,
) {
    private data class Key(
        val path: String,
        val categoryIds: Set<Int>,
    )

    private data class Entry<T>(
        val signature: PoiMetadataSignature,
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
        onSource: (PoiMetadataCacheSource) -> Unit = {},
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
        val available = cached ?: restore(file, key, signature, generation)
        if (available != null) {
            onSource(if (cached != null) PoiMetadataCacheSource.MEMORY else PoiMetadataCacheSource.DISK)
            return available.value
        }

        onSource(PoiMetadataCacheSource.DATABASE)
        val value = load()
        val unchanged = signature != null && signature == signatureOf(file)
        // Keep database reads outside the lock so imports can invalidate without waiting for a scan.
        mutex.withLock {
            if (unchanged && generation == invalidationGeneration) {
                remember(key, Entry(signature, value))
                diskStore?.write(file, key.categoryIds, signature, value)
            }
        }
        return value
    }

    private suspend fun restore(
        file: File,
        key: Key,
        signature: PoiMetadataSignature?,
        generation: Long,
    ): Entry<T>? {
        val restored = signature?.let { diskStore?.read(file, key.categoryIds, it) }
        if (restored == null || signature != signatureOf(file)) return null
        return mutex.withLock {
            if (generation != invalidationGeneration) {
                null
            } else {
                Entry(signature, restored.value).also { remember(key, it) }
            }
        }
    }

    suspend fun invalidate(path: String) {
        mutex.withLock {
            invalidationGeneration += 1
            entries.keys.removeAll { it.path == File(path).absolutePath }
            diskStore?.invalidate(File(path))
        }
    }

    private fun remember(
        key: Key,
        entry: Entry<T>,
    ) {
        entries[key] = entry
        if (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    private fun signatureOf(file: File): PoiMetadataSignature? =
        runCatching {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            PoiMetadataSignature(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        }.getOrNull()
}
