package com.glancemap.glancemapwearos.core.maps

import android.content.Context
import com.glancemap.glancemapwearos.core.service.diagnostics.TerrainDiagnostics
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class DemSignatureCacheDecision(
    val cacheHit: Boolean,
    val cacheAgeMs: Long?,
)

internal fun classifyDemSignatureCache(
    cachedSignaturePresent: Boolean,
    lastScanMs: Long,
    nowMs: Long,
    maxAgeMs: Long,
): DemSignatureCacheDecision {
    val ageMs = lastScanMs.takeIf { it > 0L }?.let { (nowMs - it).coerceAtLeast(0L) }
    return DemSignatureCacheDecision(
        cacheHit = cachedSignaturePresent && (nowMs - lastScanMs) <= maxAgeMs,
        cacheAgeMs = ageMs,
    )
}

/**
 * Caches DEM directory signature and refreshes it only when marked dirty
 * or when the cache ages out, to avoid scanning DEM files on every map refresh.
 */
object DemSignatureStore {
    private const val PREFS_NAME = "dem_signature_store"
    private const val KEY_SIGNATURE_PREFIX = "signature_"
    private const val KEY_LAST_SCAN_MS_PREFIX = "last_scan_ms_"
    private const val KEY_DETAILED_FILE_COUNT_PREFIX = "detailed_file_count_"
    private const val KEY_STANDARD_FILE_COUNT_PREFIX = "standard_file_count_"
    private const val EMPTY_SIGNATURE_SENTINEL = "DEM:EMPTY"
    private const val SIGNATURE_MAX_AGE_MS = 5L * 60L * 1000L

    private val invalidationGeneration = AtomicLong(0L)
    private val resolvedGenerationByCacheKey = ConcurrentHashMap<String, Long>()

    fun markDirty(context: Context) {
        val generation = invalidationGeneration.incrementAndGet()
        prefs(context)
            .edit()
            .clear()
            .apply()
        TerrainDiagnostics.record(
            event = "dem_signature_invalidated",
            detail = "generation=$generation",
        )
    }

    fun resolveSignature(
        context: Context,
        demRootDir: File,
        maxDepth: Int,
    ): String? =
        resolveSignature(
            context = context,
            demRootDirs = listOf(demRootDir),
            maxDepth = maxDepth,
        )

    fun resolveSignature(
        context: Context,
        demRootDirs: List<File>,
        maxDepth: Int,
    ): String? {
        val now = System.currentTimeMillis()
        val sharedPrefs = prefs(context)
        val cacheKey = cacheKeyFor(demRootDirs)
        val cachedSignature = sharedPrefs.getString(KEY_SIGNATURE_PREFIX + cacheKey, null)
        val lastScanMs = sharedPrefs.getLong(KEY_LAST_SCAN_MS_PREFIX + cacheKey, 0L)
        val generation = invalidationGeneration.get()
        val dirtySinceLastResolve =
            resolvedGenerationByCacheKey[cacheKey]?.let { resolved -> resolved != generation }
                ?: (generation > 0L)
        val cacheDecision =
            classifyDemSignatureCache(
                cachedSignaturePresent = cachedSignature != null,
                lastScanMs = lastScanMs,
                nowMs = now,
                maxAgeMs = SIGNATURE_MAX_AGE_MS,
            )

        if (cacheDecision.cacheHit) {
            val signature = cachedSignature.takeUnless { it == EMPTY_SIGNATURE_SENTINEL }
            resolvedGenerationByCacheKey[cacheKey] = generation
            recordResolution(
                resolution =
                    DemSignatureResolution(
                        state = "cache_hit",
                        cacheKey = cacheKey,
                        signature = signature,
                        cacheAgeMs = cacheDecision.cacheAgeMs,
                        dirtySinceLastResolve = dirtySinceLastResolve,
                        detailedFileCount = sharedPrefs.getInt(KEY_DETAILED_FILE_COUNT_PREFIX + cacheKey, -1),
                        standardFileCount = sharedPrefs.getInt(KEY_STANDARD_FILE_COUNT_PREFIX + cacheKey, -1),
                    ),
            )
            return signature
        }

        val scan = scanDemSignature(demRootDirs = demRootDirs, maxDepth = maxDepth)
        sharedPrefs
            .edit()
            .putString(KEY_SIGNATURE_PREFIX + cacheKey, scan.signature ?: EMPTY_SIGNATURE_SENTINEL)
            .putLong(KEY_LAST_SCAN_MS_PREFIX + cacheKey, now)
            .putInt(KEY_DETAILED_FILE_COUNT_PREFIX + cacheKey, scan.detailedFileCount)
            .putInt(KEY_STANDARD_FILE_COUNT_PREFIX + cacheKey, scan.standardFileCount)
            .apply()
        resolvedGenerationByCacheKey[cacheKey] = generation
        recordResolution(
            resolution =
                DemSignatureResolution(
                    state = "fresh_recompute",
                    cacheKey = cacheKey,
                    signature = scan.signature,
                    cacheAgeMs = null,
                    dirtySinceLastResolve = dirtySinceLastResolve,
                    detailedFileCount = scan.detailedFileCount,
                    standardFileCount = scan.standardFileCount,
                ),
        )
        return scan.signature
    }

    private fun cacheKeyFor(demRootDirs: List<File>): String =
        demRootDirs
            .map { it.absolutePath }
            .sorted()
            .joinToString("|")
            .hashCode()
            .toString()

    private data class DemSignatureScan(
        val signature: String?,
        val detailedFileCount: Int,
        val standardFileCount: Int,
    )

    private data class DemSignatureResolution(
        val state: String,
        val cacheKey: String,
        val signature: String?,
        val cacheAgeMs: Long?,
        val dirtySinceLastResolve: Boolean,
        val detailedFileCount: Int,
        val standardFileCount: Int,
    )

    private fun scanDemSignature(
        demRootDirs: List<File>,
        maxDepth: Int,
    ): DemSignatureScan {
        val accumulator = DemSignatureAccumulator()
        demRootDirs.forEach { demRootDir ->
            scanDemRoot(demRootDir, maxDepth, accumulator)
        }
        return accumulator.toScan()
    }

    private fun scanDemRoot(
        demRootDir: File,
        maxDepth: Int,
        accumulator: DemSignatureAccumulator,
    ) {
        if (!demRootDir.exists() || !demRootDir.isDirectory) return
        demRootDir.walkTopDown().maxDepth(maxDepth).forEach { file ->
            if (file.isFile) accumulator.add(file, demRootDir)
        }
    }

    private class DemSignatureAccumulator {
        private var count = 0
        private var totalBytes = 0L
        private var latestModified = 0L
        private var detailedFileCount = 0
        private var standardFileCount = 0

        fun add(
            file: File,
            demRootDir: File,
        ) {
            val lowerName = file.name.lowercase(Locale.ROOT)
            val isDem =
                lowerName.endsWith(".hgt") ||
                    lowerName.endsWith(".hgt.zip") ||
                    lowerName.endsWith(".hgt.gz") ||
                    lowerName.endsWith(".hgt.missing")
            if (!isDem) return

            count += 1
            totalBytes += file.length()
            latestModified = maxOf(latestModified, file.lastModified())
            if (file.length() > 0L && lowerName.isRenderableDemFile()) {
                when (demRootDir.name.lowercase(Locale.ROOT)) {
                    DemSource.MAPZEN_SKADI_1S.rootDirName -> detailedFileCount += 1
                    DemSource.MAPSFORGE_DEM3.rootDirName -> standardFileCount += 1
                }
            }
        }

        fun toScan(): DemSignatureScan =
            DemSignatureScan(
                signature = if (count == 0) null else "count=$count|bytes=$totalBytes|lm=$latestModified",
                detailedFileCount = detailedFileCount,
                standardFileCount = standardFileCount,
            )
    }

    private fun recordResolution(
        resolution: DemSignatureResolution,
    ) {
        TerrainDiagnostics.record(
            event = "dem_signature_resolved",
            detail =
                "state=${resolution.state} cacheKey=${resolution.cacheKey} " +
                    "cacheAgeMs=${resolution.cacheAgeMs ?: "na"} " +
                    "dirtySinceLastResolve=${resolution.dirtySinceLastResolve} " +
                    "signature=${TerrainDiagnostics.signatureIdentity(resolution.signature)} " +
                    "detailedRenderableFiles=${resolution.detailedFileCount} " +
                    "standardRenderableFiles=${resolution.standardFileCount}",
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )

    private fun String.isRenderableDemFile(): Boolean = endsWith(".hgt") || endsWith(".hgt.zip") || endsWith(".hgt.gz")
}
