package com.glancemap.glancemapcompanionapp.map

import org.mapsforge.core.graphics.GraphicFactory
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.map.layer.queue.Job
import java.io.File
import java.io.FileInputStream

/**
 * Mapsforge indexes a reopened persistent bucket on a background thread, and its render workers do
 * not consult the cache. Until the index catches up, tiles already on disk would be queued and
 * rendered again, so the drawable cache reads them directly for this window after opening.
 */
internal const val PHONE_MAP_DISK_INDEX_FALLBACK_MS = 10_000L

/** Direct access to tiles that a persistent bucket already holds on disk. */
internal interface PhoneMapsforgeDiskTileReader {
    fun hasTile(job: Job): Boolean

    fun read(job: Job): TileBitmap?
}

/** Same layout as Mapsforge's FileSystemTileCache: `<bucket>/<zoom>/<x>/<y>.tile`. */
internal fun phoneMapsforgeDiskTileFile(
    bucketDir: File,
    job: Job,
): File = File(bucketDir, "${job.key}$PHONE_MAP_DISK_TILE_SUFFIX")

internal class PhoneMapsforgeFileDiskTileReader(
    private val bucketDir: File,
    private val graphicFactory: GraphicFactory,
) : PhoneMapsforgeDiskTileReader {
    override fun hasTile(job: Job): Boolean = phoneMapsforgeDiskTileFile(bucketDir, job).isFile

    override fun read(job: Job): TileBitmap? {
        val file = phoneMapsforgeDiskTileFile(bucketDir, job)
        if (!file.isFile) return null
        return FileInputStream(file).use { input ->
            graphicFactory.createTileBitmap(input, job.tile.tileSize, job.hasAlpha).apply {
                // Mapsforge's stale-tile check compares this with the map file's data timestamp.
                timestamp = file.lastModified()
            }
        }
    }
}

/** A reopened persistent bucket: direct disk reads during indexing and release on destroy. */
internal data class PhoneMapsforgePersistentBucket(
    val reader: PhoneMapsforgeDiskTileReader,
    val onReleased: () -> Unit,
    val fallbackWindowMs: Long = PHONE_MAP_DISK_INDEX_FALLBACK_MS,
    val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
)

/** Counters for one opened tile cache, exported only through debug capture. */
internal data class PhoneMapsforgeTileCacheStats(
    val persistent: Boolean,
    val renderedTilesStored: Int,
    val diskFallbackReads: Int,
    val diskFallbackFailures: Int,
    val diskFallbackActive: Boolean,
)

private const val PHONE_MAP_DISK_TILE_SUFFIX = ".tile"
private const val NANOS_PER_MILLI = 1_000_000L
