package com.glancemap.glancemapwearos.presentation.features.maps

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal const val CACHE_ID_PREFIX = "mapcache"
internal const val CACHE_HASH_BYTES = 8
internal const val CACHE_CLEANUP_PREFS_NAME = "map_cache_maintenance"
internal const val KEY_CACHE_LAST_CLEANUP_MS = "last_cleanup_ms"
internal const val CACHE_CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
internal const val CACHE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L
internal const val CACHE_KEEP_RECENT_COUNT = 5
internal const val CACHE_SOFT_LIMIT_BYTES = 150L * 1024L * 1024L
internal const val CACHE_TARGET_LIMIT_BYTES = 100L * 1024L * 1024L
internal const val CACHE_LAST_USED_MARKER_NAME = ".last_used"
internal const val KEY_ACTIVE_CACHE_ID = "active_cache_id"
internal const val KEY_ACTIVE_CACHE_UPDATED_MS = "active_cache_updated_ms"
internal const val RELIEF_OVERLAY_CACHE_DIR_NAME = "relief_overlay"
internal const val RELIEF_OVERLAY_CACHE_HASH_BYTES = 8
internal const val BUNDLED_THEME_CACHE_DIR_PREFIX = "bundled-theme-"
internal const val HILLSHADE_TILE_CACHE_SCHEMA_VERSION = 2

private const val MAP_RENDERER_CACHE_TAG = "MapRenderer"

private data class MapRendererCacheBucketInfo(
    val name: String,
    val dir: File,
    val lastUsedMs: Long,
    val sizeBytes: Long,
)

@Suppress("LongMethod")
internal fun captureMapRendererCacheDiagnostics(context: Context): MapRenderer.CacheDiagnosticsSnapshot {
    val prefs = context.getSharedPreferences(CACHE_CLEANUP_PREFS_NAME, Context.MODE_PRIVATE)
    val tileCacheRoot = context.externalCacheDir ?: context.cacheDir
    val tileCacheBuckets =
        tileCacheRoot
            .listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith(CACHE_ID_PREFIX) }
            ?.toList()
            ?: emptyList()
    val activeTileCacheId = prefs.getString(KEY_ACTIVE_CACHE_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
    val activeTileCacheLastUsedMs =
        prefs
            .getLong(KEY_ACTIVE_CACHE_UPDATED_MS, 0L)
            .takeIf { it > 0L }
    val activeTileCacheSizeBytes =
        activeTileCacheId
            ?.let { id -> tileCacheBuckets.firstOrNull { it.name == id } }
            ?.let { dir -> mapRendererDirectorySizeBytes(dir) }
    val reliefOverlayRootDir = File(tileCacheRoot, RELIEF_OVERLAY_CACHE_DIR_NAME)
    val reliefOverlayEntries = reliefOverlayRootDir.listFiles()?.toList().orEmpty()
    val bundledThemeDirs =
        context.cacheDir
            .listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith(BUNDLED_THEME_CACHE_DIR_PREFIX) }
            ?.toList()
            ?: emptyList()

    return MapRenderer.CacheDiagnosticsSnapshot(
        activeTileCacheId = activeTileCacheId,
        activeTileCacheLastUsedMs = activeTileCacheLastUsedMs,
        tileCacheBucketCount = tileCacheBuckets.size,
        tileCacheTotalSizeBytes = tileCacheBuckets.sumOf { mapRendererDirectorySizeBytes(it) },
        activeTileCacheSizeBytes = activeTileCacheSizeBytes,
        tileCacheSoftLimitBytes = CACHE_SOFT_LIMIT_BYTES,
        tileCacheTargetLimitBytes = CACHE_TARGET_LIMIT_BYTES,
        tileCacheMaxAgeMs = CACHE_MAX_AGE_MS,
        tileCacheCleanupIntervalMs = CACHE_CLEANUP_INTERVAL_MS,
        lastCleanupMs = prefs.getLong(KEY_CACHE_LAST_CLEANUP_MS, 0L).takeIf { it > 0L },
        tileCacheBuckets =
            tileCacheBuckets
                .map { dir ->
                    val lastUsedMs = mapRendererBucketLastUsedMs(dir).takeIf { it > 0L }
                    MapRenderer.CacheBucketDiagnostics(
                        id = dir.name,
                        sizeBytes = mapRendererDirectorySizeBytes(dir),
                        lastUsedMs = lastUsedMs,
                        active = dir.name == activeTileCacheId,
                    )
                }.sortedWith(
                    compareByDescending<MapRenderer.CacheBucketDiagnostics> { it.active }
                        .thenByDescending { it.lastUsedMs ?: 0L },
                ),
        reliefOverlayNamespaceCount = reliefOverlayEntries.count { it.isDirectory || it.isFile },
        reliefOverlayCacheSizeBytes =
            if (reliefOverlayRootDir.exists()) {
                mapRendererDirectorySizeBytes(reliefOverlayRootDir)
            } else {
                0L
            },
        bundledThemeCacheDirCount = bundledThemeDirs.size,
        bundledThemeCacheTotalSizeBytes = bundledThemeDirs.sumOf { mapRendererDirectorySizeBytes(it) },
    )
}

internal fun computeMapRendererMapSignature(mapPath: String?): String? {
    if (mapPath.isNullOrBlank()) return null
    val file = File(mapPath)
    if (!file.exists()) return "MISSING:$mapPath"
    val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
    val length = runCatching { file.length() }.getOrDefault(0L)
    return "FILE:${file.absolutePath}|$lastModified|$length"
}

internal fun resolveMapRendererDesiredCacheId(
    mapSignature: String?,
    themeSignature: String,
    elevationLabelsMetric: Boolean,
    labelTextScale: Float,
): String {
    val mapPart = mapSignature ?: "MAP:NONE"
    val themePart = themeSignature.ifBlank { "THEME:UNSET" }
    val unitPart = if (elevationLabelsMetric) "UNITS:METRIC" else "UNITS:IMPERIAL"
    val labelScalePart =
        "LABEL_SCALE:${"%.2f".format(Locale.US, mapRendererLayerTextScale(labelTextScale))}"
    val signature = "$mapPart|$themePart|$unitPart|$labelScalePart"

    val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(Charsets.UTF_8))
    val shortHex =
        digest.take(CACHE_HASH_BYTES).joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    return "${CACHE_ID_PREFIX}_$shortHex"
}

internal fun resolveMapRendererReliefOverlayCacheNamespace(currentDemSignature: String?): String {
    val demPart = currentDemSignature ?: "DEM:NONE"
    val signature =
        buildString {
            append("RELIEF")
            append("|")
            append(demPart)
            append("|")
            append(ReliefOverlayAlgorithm.cacheSignature())
            append("|MIN_ZOOM:13")
        }
    val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(Charsets.UTF_8))
    val shortHex =
        digest.take(RELIEF_OVERLAY_CACHE_HASH_BYTES).joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    return "relief_$shortHex"
}

internal fun markMapRendererCacheBucketUsed(
    context: Context,
    cacheMaintenancePrefs: SharedPreferences,
    cacheId: String,
) {
    runCatching {
        val now = System.currentTimeMillis()
        cacheMaintenancePrefs
            .edit()
            .putString(KEY_ACTIVE_CACHE_ID, cacheId)
            .putLong(KEY_ACTIVE_CACHE_UPDATED_MS, now)
            .apply()

        val root = context.externalCacheDir ?: context.cacheDir
        val dir = File(root, cacheId)
        if (!dir.exists()) dir.mkdirs()
        val marker = File(dir, CACHE_LAST_USED_MARKER_NAME)
        if (!marker.exists()) marker.createNewFile()
        marker.setLastModified(now)
        dir.setLastModified(now)
    }.onFailure { error ->
        Log.d(MAP_RENDERER_CACHE_TAG, "Failed touching cache bucket marker for $cacheId", error)
    }
}

internal fun cleanupMapRendererPersistentCacheBuckets(
    cacheRoot: File,
    nowMs: Long,
    keepIds: Set<String>,
) {
    val initial = listMapRendererCacheBuckets(cacheRoot)
    if (initial.isEmpty()) return

    var buckets = initial

    val stale =
        buckets.filter { bucket ->
            bucket.name !in keepIds && (nowMs - bucket.lastUsedMs) > CACHE_MAX_AGE_MS
        }
    stale.forEach { bucket ->
        if (deleteMapRendererCacheBucket(bucket.dir)) {
            Log.d(MAP_RENDERER_CACHE_TAG, "Deleted stale cache bucket: ${bucket.name}")
        }
    }
    if (stale.isNotEmpty()) {
        buckets = listMapRendererCacheBuckets(cacheRoot)
        if (buckets.isEmpty()) return
    }

    var totalBytes = buckets.sumOf { it.sizeBytes }
    if (totalBytes <= CACHE_SOFT_LIMIT_BYTES) return

    val keptRecentNames =
        buckets
            .sortedByDescending { it.lastUsedMs }
            .take(CACHE_KEEP_RECENT_COUNT)
            .map { it.name }
            .toSet()

    val protected = keepIds + keptRecentNames
    val deletedNames = mutableSetOf<String>()

    fun tryDeleteOrdered(candidates: List<MapRendererCacheBucketInfo>) {
        for (bucket in candidates) {
            if (totalBytes <= CACHE_TARGET_LIMIT_BYTES) break
            if (bucket.name in deletedNames || bucket.name in keepIds) continue
            if (deleteMapRendererCacheBucket(bucket.dir)) {
                deletedNames += bucket.name
                totalBytes -= bucket.sizeBytes
                Log.d(MAP_RENDERER_CACHE_TAG, "Deleted cache bucket for size cap: ${bucket.name}")
            }
        }
    }

    tryDeleteOrdered(
        buckets
            .filter { it.name !in protected }
            .sortedBy { it.lastUsedMs },
    )

    if (totalBytes > CACHE_TARGET_LIMIT_BYTES) {
        tryDeleteOrdered(
            buckets
                .filter { it.name !in keepIds }
                .sortedBy { it.lastUsedMs },
        )
    }
}

private fun listMapRendererCacheBuckets(cacheRoot: File): List<MapRendererCacheBucketInfo> {
    val dirs =
        cacheRoot
            .listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith(CACHE_ID_PREFIX) }
            ?.toList()
            ?: emptyList()

    return dirs.map { dir ->
        MapRendererCacheBucketInfo(
            name = dir.name,
            dir = dir,
            lastUsedMs = mapRendererBucketLastUsedMs(dir),
            sizeBytes = mapRendererDirectorySizeBytes(dir),
        )
    }
}

private fun mapRendererBucketLastUsedMs(dir: File): Long {
    val marker = File(dir, CACHE_LAST_USED_MARKER_NAME)
    val markerMs = if (marker.exists()) marker.lastModified() else 0L
    val dirMs = dir.lastModified()
    return maxOf(markerMs, dirMs)
}

private fun mapRendererDirectorySizeBytes(root: File): Long {
    var total = 0L
    root.walkTopDown().forEach { file ->
        if (file.isFile) {
            total += runCatching { file.length() }.getOrDefault(0L)
        }
    }
    return total
}

private fun deleteMapRendererCacheBucket(dir: File): Boolean =
    runCatching {
        if (!dir.exists()) true else dir.deleteRecursively()
    }.getOrElse { error ->
        Log.w(MAP_RENDERER_CACHE_TAG, "Failed deleting cache bucket: ${dir.absolutePath}", error)
        false
    }
