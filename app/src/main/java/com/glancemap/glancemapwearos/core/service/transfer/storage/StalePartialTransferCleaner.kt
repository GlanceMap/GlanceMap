package com.glancemap.glancemapwearos.core.service.transfer.storage

import android.content.Context
import android.util.Log
import com.glancemap.glancemapwearos.core.routing.routingSegmentsDir
import java.io.File
import java.util.concurrent.TimeUnit

internal object StalePartialTransferCleaner {
    private const val TAG = "StalePartialCleaner"
    private const val DEM_DIR_NAME = "dem3"
    private val STALE_AGE_MS = TimeUnit.DAYS.toMillis(7)

    fun scan(context: Context): PartialFilesSummary {
        val partialFiles = findPartialFiles(context, includeBundleArchives = true)
        return PartialFilesSummary(
            count = partialFiles.size,
            totalBytes = partialFiles.sumOf { it.length().coerceAtLeast(0L) },
        )
    }

    fun cleanStale(context: Context): CleanupResult {
        val now = System.currentTimeMillis()
        return deleteMatching(context) { file ->
            now - file.lastModified() >= STALE_AGE_MS
        }
    }

    fun clearAll(context: Context): CleanupResult = deleteMatching(context, includeBundleArchives = true) { true }

    private fun deleteMatching(
        context: Context,
        includeBundleArchives: Boolean = false,
        shouldDelete: (File) -> Boolean,
    ): CleanupResult {
        var removedFiles = 0
        var freedBytes = 0L
        var skippedInUseFiles = 0

        findPartialFiles(context, includeBundleArchives).forEach { file ->
            if (!shouldDelete(file)) return@forEach

            val size = file.length().coerceAtLeast(0L)
            when (partialFileUseGuard.deleteIfIdle(file)) {
                true -> {
                    removedFiles++
                    freedBytes += size
                }
                null -> skippedInUseFiles++
                false -> Unit
            }
        }

        if (removedFiles > 0) {
            Log.i(TAG, "Removed $removedFiles stale partial file(s), freed ${freedBytes}B")
        } else {
            Log.d(TAG, "No stale partial files to clean")
        }

        return CleanupResult(
            removedFiles = removedFiles,
            freedBytes = freedBytes,
            skippedInUseFiles = skippedInUseFiles,
        )
    }

    private fun findPartialFiles(
        context: Context,
        includeBundleArchives: Boolean,
    ): List<File> {
        val bundleDownloads = context.getDir("oam_downloads", Context.MODE_PRIVATE)
        val roots =
            linkedSetOf<File>().apply {
                add(context.getDir("gpx", Context.MODE_PRIVATE))
                add(context.getDir("maps", Context.MODE_PRIVATE))
                add(context.getDir("poi", Context.MODE_PRIVATE))
                add(bundleDownloads)
                add(routingSegmentsDir(context))
                add(File(context.filesDir, "mapsforge-themes/.transfers"))
                add(
                    context.getExternalFilesDir(DEM_DIR_NAME)
                        ?: File(context.getDir("maps", Context.MODE_PRIVATE), DEM_DIR_NAME),
                )
            }

        return findPartialCleanupFiles(roots, bundleDownloads, includeBundleArchives)
    }

    internal data class PartialFilesSummary(
        val count: Int,
        val totalBytes: Long,
    )

    internal data class CleanupResult(
        val removedFiles: Int,
        val freedBytes: Long,
        val skippedInUseFiles: Int = 0,
    )
}
