package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

private const val PHONE_WORLDWIND_MIB = 1024 * 1024
internal const val PHONE_WORLDWIND_TILE_RETRY_MS = 30_000L

/** WorldWind never retries a failed factory image itself, so each failed source is released after a delay. */
internal class PhoneWorldWindTileRetry(
    private val scope: CoroutineScope,
    private val delayMillis: Long = PHONE_WORLDWIND_TILE_RETRY_MS,
    private val onScheduled: () -> Unit = {},
    private val onRetry: (Int) -> Unit,
) {
    private val pending = mutableSetOf<Int>()

    fun schedule(resourceId: Int) {
        if (!synchronized(pending) { pending.add(resourceId) }) return
        onScheduled()
        scope.launch {
            delay(delayMillis)
            synchronized(pending) { pending.remove(resourceId) }
            onRetry(resourceId)
        }
    }
}

internal const val PHONE_WORLDWIND_DISK_CACHE_BYTES = 128L * PHONE_WORLDWIND_MIB

/**
 * Rendered Mapsforge tiles kept on disk, so reopening 3D or returning to an area skips rendering. WorldWind
 * already keeps decoded textures while a view is open, which left an in-memory copy unused. The file name
 * hashes the app version, map revision, theme and scale, so a changed input never reuses an old tile, and
 * the least recently used tiles are removed beyond [maximumBytes].
 */
internal class PhoneWorldWindDiskTileCache(
    private val directory: File,
    private val version: String,
    val maximumBytes: Long = PHONE_WORLDWIND_DISK_CACHE_BYTES,
    private val onEviction: () -> Unit = {},
) {
    private val lock = Any()
    private var used = tileFiles().sumOf(File::length)

    fun usedBytes(): Long = synchronized(lock) { used }

    fun get(key: PhoneWorldWindTileKey): ByteArray? {
        val file = file(key)
        return runCatching { file.readBytes().also { file.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }

    fun remove(key: PhoneWorldWindTileKey) {
        val file = file(key)
        val length = file.length()
        if (file.delete()) synchronized(lock) { used -= length }
    }

    fun put(
        key: PhoneWorldWindTileKey,
        bytes: ByteArray,
    ) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        val target = file(key)
        val previous = target.length()
        // A unique temporary file and rename keep a concurrent reader from seeing a partial tile.
        val temporary = File.createTempFile("tile-", ".tmp", directory)
        val stored = runCatching { temporary.writeBytes(bytes) }.isSuccess && temporary.renameTo(target)
        temporary.delete()
        if (stored) synchronized(lock) { used += bytes.size - previous }
        if (usedBytes() > maximumBytes) trim()
    }

    private fun trim() =
        synchronized(lock) {
            val files = tileFiles().sortedBy(File::lastModified)
            used = files.sumOf(File::length)
            // Trim below the limit so the next writes do not rescan the directory each time.
            val target = maximumBytes * TRIM_PERCENT / 100
            for (file in files) {
                if (used <= target) break
                val length = file.length()
                if (file.delete()) {
                    used -= length
                    onEviction()
                }
            }
        }

    private fun tileFiles(): List<File> =
        directory
            .listFiles { file -> file.name.endsWith(TILE_SUFFIX) }
            .orEmpty()
            .toList()

    private fun file(key: PhoneWorldWindTileKey): File {
        val identity = "$version|${key.mapIdentity}|${key.themeIdentity}|${key.scale}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        val prefix = digest.take(IDENTITY_BYTES).joinToString("") { "%02x".format(it) }
        val tile = key.tile
        return File(directory, "${prefix}_${tile.z}_${tile.x}_${tile.y}_${key.resolution}$TILE_SUFFIX")
    }

    private companion object {
        const val TILE_SUFFIX = ".png"
        const val TRIM_PERCENT = 90
        const val IDENTITY_BYTES = 8
    }
}

/** WorldWind's tile level is a stable proxy for visible texture detail; the same tile never changes bands. */
internal fun phoneWorldWindTileResolution(
    zoom: Int,
    density: Float,
    maxHeapBytes: Long,
): Int = if (zoom >= 16 && density >= 2f && maxHeapBytes >= 256L * PHONE_WORLDWIND_MIB) 512 else 256

/** Independent shared jobs let a cancelled waiter leave without cancelling other consumers. */
internal class PhoneWorldWindTileCoalescer<K, V>(
    private val scope: CoroutineScope,
    private val onCoalesced: () -> Unit = {},
    private val onCancelled: () -> Unit = {},
) {
    private class Entry<V>(
        val deferred: Deferred<V?>,
        var waiters: Int,
    )

    private val lock = Any()
    private val inFlight = mutableMapOf<K, Entry<V>>()

    suspend fun get(
        key: K,
        render: suspend () -> V?,
    ): V? {
        val entry =
            synchronized(lock) {
                val existing = inFlight[key]
                if (existing != null) {
                    existing.waiters++
                    onCoalesced()
                    existing
                } else {
                    val created = Entry(scope.async(start = CoroutineStart.LAZY) { render() }, 1)
                    inFlight[key] = created
                    created.deferred.invokeOnCompletion {
                        synchronized(lock) { if (inFlight[key] === created) inFlight.remove(key) }
                    }
                    created
                }
            }
        entry.deferred.start()
        try {
            return entry.deferred.await()
        } finally {
            synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.deferred.isCompleted) {
                    if (inFlight[key] === entry) inFlight.remove(key)
                    entry.deferred.cancel()
                    onCancelled()
                }
            }
        }
    }

    fun pendingCount(): Int = synchronized(lock) { inFlight.size }
}
