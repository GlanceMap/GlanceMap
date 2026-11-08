package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val WORLDWIND_TILE_DISK_DIR = "worldwind-tiles"

/** Encoded 3D map images kept across sessions; roughly 1,000 to 2,500 256-pixel tiles. */
internal const val WORLDWIND_TILE_DISK_BYTES = 50L * 1024 * 1024

/** Images unused for this long are deleted, matching the 2D tile cache age limit. */
internal const val WORLDWIND_TILE_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

private const val WRITES_BETWEEN_TRIMS = 32
private const val IDENTITY_HASH_CHARS = 16

/**
 * Persistent store of drawn 3D map images. Images are grouped by everything that affects drawing
 * (map file, theme, text scale, tile size), so a changed map or theme starts a new group and old
 * groups age out. Files are written through a temporary file and rename; a failed read is a miss.
 * Above [maxBytes] the least recently used images are deleted, and images unused for [maxAgeMs].
 */
internal class WorldWindTileDiskCache(
    private val dir: File,
    private val maxBytes: Long = WORLDWIND_TILE_DISK_BYTES,
    private val maxAgeMs: Long = WORLDWIND_TILE_MAX_AGE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        @Volatile private var shared: WorldWindTileDiskCache? = null

        fun shared(context: Context): WorldWindTileDiskCache =
            shared ?: synchronized(this) {
                shared ?: WorldWindTileDiskCache(File(context.applicationContext.cacheDir, WORLDWIND_TILE_DISK_DIR))
                    .also { shared = it }
            }
    }

    private var writesSinceTrim = 0

    fun read(key: WorldWindTileKey): ByteArray? {
        val file = fileFor(key)
        return try {
            file.readBytes().also { file.setLastModified(now()) }
        } catch (_: IOException) {
            null
        }
    }

    /** Returns the bytes written, or zero when the image could not be stored. */
    @Synchronized
    fun write(
        key: WorldWindTileKey,
        bytes: ByteArray,
    ): Long {
        val target = fileFor(key)
        val folder = checkNotNull(target.parentFile)
        val written =
            try {
                if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
                val temp = File.createTempFile(target.name, ".tmp", folder)
                try {
                    temp.writeBytes(bytes)
                    if (!temp.renameTo(target)) throw IOException("Cannot store $target")
                    target.setLastModified(now())
                } finally {
                    temp.delete()
                }
                bytes.size.toLong()
            } catch (_: IOException) {
                0L
            }
        if (++writesSinceTrim >= WRITES_BETWEEN_TRIMS) trim()
        return written
    }

    fun delete(key: WorldWindTileKey) {
        fileFor(key).delete()
    }

    @Synchronized
    fun trim() {
        writesSinceTrim = 0
        val cutoff = now() - maxAgeMs
        val images =
            dir
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".png") }
                .filterNot { it.lastModified() < cutoff && it.delete() }
                .sortedBy(File::lastModified)
                .toList()
        var total = images.sumOf(File::length)
        for (image in images) {
            if (total <= maxBytes) break
            val length = image.length()
            if (image.delete()) total -= length
        }
        dir.listFiles()?.filter { it.isDirectory && it.list().isNullOrEmpty() }?.forEach(File::delete)
    }

    fun sizeBytes(): Long =
        dir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".png") }
            .sumOf(File::length)

    private fun fileFor(key: WorldWindTileKey): File {
        val group = File(dir, worldWindTileIdentity(key))
        return File(group, "${key.z}_${key.x}_${key.y}.png")
    }
}

/** Short stable name for everything other than the tile position that changes a drawn image. */
internal fun worldWindTileIdentity(key: WorldWindTileKey): String {
    val identity = "${key.map}\n${key.theme}\n${key.scale}\n${key.resolution}"
    return MessageDigest
        .getInstance("SHA-256")
        .digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(IDENTITY_HASH_CHARS)
}
