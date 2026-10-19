package com.glancemap.glancemapwearos.core.service.transfer.storage

import android.content.Context
import android.util.Log
import com.glancemap.glancemapwearos.core.routing.routingSegmentsDir
import java.io.File
import java.util.concurrent.TimeUnit

internal object StalePartialTransferCleaner {
    private const val TAG = "StalePartialCleaner"
    private const val DEM_DIR_NAME = "dem3"
    private const val PARTIAL_FILE_SUFFIX = ".part"
    private val STALE_AGE_MS = TimeUnit.DAYS.toMillis(7)

    fun scan(context: Context): PartialFilesSummary {
        val partialFiles = findPartialFiles(context)
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

    fun clearAll(context: Context): CleanupResult = deleteMatching(context) { true }

    private fun deleteMatching(
        context: Context,
        shouldDelete: (File) -> Boolean,
    ): CleanupResult {
        var removedFiles = 0
        var freedBytes = 0L

        findPartialFiles(context).forEach { file ->
            if (!shouldDelete(file)) return@forEach

            val size = file.length().coerceAtLeast(0L)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                removedFiles++
                freedBytes += size
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
        )
    }

    private fun findPartialFiles(context: Context): List<File> {
        val roots =
            linkedSetOf<File>().apply {
                add(context.getDir("gpx", Context.MODE_PRIVATE))
                add(context.getDir("maps", Context.MODE_PRIVATE))
                add(context.getDir("poi", Context.MODE_PRIVATE))
                add(context.getDir("oam_downloads", Context.MODE_PRIVATE))
                add(routingSegmentsDir(context))
                add(File(context.filesDir, "mapsforge-themes/.transfers"))
                add(
                    context.getExternalFilesDir(DEM_DIR_NAME)
                        ?: File(context.getDir("maps", Context.MODE_PRIVATE), DEM_DIR_NAME),
                )
            }

        return buildList {
            roots.forEach { root ->
                if (!root.exists()) return@forEach
                root.walkTopDown().forEach { file ->
                    if (!file.isFile) return@forEach
                    if (!file.name.startsWith(".") || !file.name.endsWith(PARTIAL_FILE_SUFFIX, ignoreCase = true)) {
                        return@forEach
                    }
                    add(file)
                }
            }
        }
    }

    internal data class PartialFilesSummary(
        val count: Int,
        val totalBytes: Long,
    )

    internal data class CleanupResult(
        val removedFiles: Int,
        val freedBytes: Long,
    )
}
