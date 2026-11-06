package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.layer.cache.TwoLevelTileCache
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal const val PHONE_MAP_TILE_CACHE_DIR_PREFIX = "phone-offline-"
internal const val PHONE_MAP_TILE_CACHE_MARKER_NAME = ".last_used"
internal const val PHONE_MAP_TILE_CACHE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L
internal const val PHONE_MAP_TILE_CACHE_KEEP_RECENT_COUNT = 5
internal const val PHONE_MAP_TILE_CACHE_SOFT_LIMIT_BYTES = 150L * 1024L * 1024L
internal const val PHONE_MAP_TILE_CACHE_TARGET_LIMIT_BYTES = 100L * 1024L * 1024L
private const val PHONE_MAP_TILE_CACHE_TOMBSTONE_PREFIX = ".deleting-"

/** Totals across all cache roots, for the one debug-capture line each cleanup writes. */
internal data class PhoneMapsforgeTileCacheCleanupResult(
    val bucketsBefore: Int = 0,
    val bytesBefore: Long = 0L,
    val deletedBuckets: Int = 0,
    val bytesAfter: Long = 0L,
)

internal data class PhoneMapsforgeTileCacheBucket(
    val name: String,
    val lastUsedMs: Long,
    val sizeBytes: Long,
)

/** Separates rendered tiles whose pixels differ for the same map and theme selection. */
internal fun phoneMapsforgeTileRenderSignature(
    tileSizePx: Int,
    appVersionCode: Int,
): String = "tile=$tileSizePx:app=$appVersionCode"

/**
 * Mirrors the watch bucket retention: stale buckets first, then the oldest buckets outside the
 * recent set, then any non-live bucket, until the total falls under the target. Live buckets are
 * never selected.
 */
internal fun phoneMapsforgeTileCacheBucketsToDelete(
    buckets: List<PhoneMapsforgeTileCacheBucket>,
    nowMs: Long,
    liveIds: Set<String>,
): List<String> {
    val deleted = linkedSetOf<String>()
    buckets
        .filter { it.name !in liveIds && nowMs - it.lastUsedMs > PHONE_MAP_TILE_CACHE_MAX_AGE_MS }
        .forEach { deleted += it.name }
    val remaining = buckets.filter { it.name !in deleted }
    var totalBytes = remaining.sumOf { it.sizeBytes }
    if (totalBytes <= PHONE_MAP_TILE_CACHE_SOFT_LIMIT_BYTES) return deleted.toList()

    val recent =
        remaining
            .sortedByDescending { it.lastUsedMs }
            .take(PHONE_MAP_TILE_CACHE_KEEP_RECENT_COUNT)
            .map { it.name }
            .toSet()

    fun deleteOldest(candidates: List<PhoneMapsforgeTileCacheBucket>) {
        for (bucket in candidates.sortedBy { it.lastUsedMs }) {
            if (totalBytes <= PHONE_MAP_TILE_CACHE_TARGET_LIMIT_BYTES) return
            if (bucket.name in deleted || bucket.name in liveIds) continue
            deleted += bucket.name
            totalBytes -= bucket.sizeBytes
        }
    }
    deleteOldest(remaining.filter { it.name !in recent })
    deleteOldest(remaining)
    return deleted.toList()
}

/** Tracks open persistent tile-cache buckets and bounds their total disk use. */
internal object PhoneMapsforgeTileCacheRetention {
    private val lock = Any()
    private val liveCounts = mutableMapOf<String, Int>()
    private val cleanupScheduled = AtomicBoolean(false)

    /** Register before opening the bucket so concurrent cleanup cannot remove it. */
    fun acquire(cacheId: String) {
        synchronized(lock) { liveCounts[cacheId] = (liveCounts[cacheId] ?: 0) + 1 }
    }

    /** Touches the last-used marker of each existing bucket directory for this cache id. */
    fun markUsed(
        cacheId: String,
        roots: List<File>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        roots.forEach { root ->
            val dir = File(root, cacheId)
            if (dir.isDirectory) touchMarker(dir, nowMs)
        }
    }

    fun release(cacheId: String) {
        synchronized(lock) {
            val remaining = (liveCounts[cacheId] ?: return) - 1
            if (remaining > 0) liveCounts[cacheId] = remaining else liveCounts.remove(cacheId)
        }
    }

    /** Runs one bounded cleanup per process, off the main thread, after the first cache opens. */
    fun scheduleCleanupOnce(
        roots: List<File>,
        onCompleted: (PhoneMapsforgeTileCacheCleanupResult) -> Unit,
    ) {
        if (!cleanupScheduled.compareAndSet(false, true)) return
        Thread(
            {
                onCompleted(
                    runCatching { cleanup(roots, System.currentTimeMillis()) }
                        .getOrDefault(PhoneMapsforgeTileCacheCleanupResult()),
                )
            },
            "phone-tile-cache-retention",
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    internal fun cleanup(
        roots: List<File>,
        nowMs: Long,
    ): PhoneMapsforgeTileCacheCleanupResult {
        var result = PhoneMapsforgeTileCacheCleanupResult()
        roots.distinctBy { it.absolutePath }.forEach { root ->
            root
                .listFiles { file -> file.isDirectory && file.name.startsWith(PHONE_MAP_TILE_CACHE_TOMBSTONE_PREFIX) }
                ?.forEach { runCatching { it.deleteRecursively() } }
            val buckets = listBuckets(root)
            val live = synchronized(lock) { liveCounts.keys.toSet() }
            var deletedCount = 0
            var deletedBytes = 0L
            phoneMapsforgeTileCacheBucketsToDelete(buckets, nowMs, live).forEach { name ->
                // Renaming under the lock prevents a renderer from opening a bucket being deleted.
                val tombstone =
                    synchronized(lock) {
                        if (name in liveCounts) return@synchronized null
                        File(root, "$PHONE_MAP_TILE_CACHE_TOMBSTONE_PREFIX$name-$nowMs")
                            .takeIf { File(root, name).renameTo(it) }
                    }
                if (tombstone != null && runCatching { tombstone.deleteRecursively() }.getOrDefault(false)) {
                    deletedCount += 1
                    deletedBytes += buckets.firstOrNull { it.name == name }?.sizeBytes ?: 0L
                }
            }
            val bytesBefore = buckets.sumOf { it.sizeBytes }
            result =
                PhoneMapsforgeTileCacheCleanupResult(
                    bucketsBefore = result.bucketsBefore + buckets.size,
                    bytesBefore = result.bytesBefore + bytesBefore,
                    deletedBuckets = result.deletedBuckets + deletedCount,
                    bytesAfter = result.bytesAfter + bytesBefore - deletedBytes,
                )
        }
        return result
    }

    internal fun liveCount(cacheId: String): Int = synchronized(lock) { liveCounts[cacheId] ?: 0 }

    private fun touchMarker(
        dir: File,
        nowMs: Long,
    ) {
        runCatching {
            val marker = File(dir, PHONE_MAP_TILE_CACHE_MARKER_NAME)
            if (!marker.exists()) marker.createNewFile()
            marker.setLastModified(nowMs)
        }
    }

    private fun listBuckets(root: File): List<PhoneMapsforgeTileCacheBucket> =
        root
            .listFiles { file -> file.isDirectory && file.name.startsWith(PHONE_MAP_TILE_CACHE_DIR_PREFIX) }
            .orEmpty()
            .map { dir ->
                val marker = File(dir, PHONE_MAP_TILE_CACHE_MARKER_NAME)
                PhoneMapsforgeTileCacheBucket(
                    name = dir.name,
                    lastUsedMs = maxOf(if (marker.exists()) marker.lastModified() else 0L, dir.lastModified()),
                    sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }
}

private fun createPersistentTwoLevelTileCache(
    appContext: Context,
    cacheId: String,
    config: PhoneMapsforgeTileCacheConfig,
): Pair<TwoLevelTileCache, File> {
    var bucketDir = File(appContext.externalCacheDir ?: appContext.cacheDir, cacheId)
    var cache =
        AndroidUtil.createExternalStorageTileCache(
            appContext,
            cacheId,
            config.firstLevelTiles,
            config.tileSizePx,
            true,
        )
    if (cache !is TwoLevelTileCache) {
        cache.destroy()
        bucketDir = File(appContext.cacheDir, cacheId)
        cache =
            AndroidUtil.createExternalStorageTileCache(
                appContext.cacheDir,
                cacheId,
                config.firstLevelTiles,
                config.tileSizePx,
                true,
            )
    }
    if (cache !is TwoLevelTileCache) {
        cache.destroy()
        error("Offline tile cache storage is unavailable.")
    }
    return cache to bucketDir
}

internal fun openRetainedTwoLevelTileCache(
    appContext: Context,
    cacheId: String,
    config: PhoneMapsforgeTileCacheConfig,
    cacheRoots: List<File>,
): Pair<TwoLevelTileCache, File> {
    PhoneMapsforgeTileCacheRetention.acquire(cacheId)
    var opened = false
    val cache =
        try {
            createPersistentTwoLevelTileCache(appContext, cacheId, config).also { opened = true }
        } finally {
            if (!opened) PhoneMapsforgeTileCacheRetention.release(cacheId)
        }
    PhoneMapsforgeTileCacheRetention.markUsed(cacheId, cacheRoots)
    return cache
}
