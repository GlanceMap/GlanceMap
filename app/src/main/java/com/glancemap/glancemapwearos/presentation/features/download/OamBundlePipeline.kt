package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

@Suppress("ReturnCount")
internal fun reusableBundleArchiveOrNull(
    directory: File,
    fileName: String,
    entryExtension: String,
    expectedSize: Long? = null,
): File? {
    val archive = File(directory, File(fileName).name)
    if (!archive.isFile || archive.length() <= 0L) return null
    if (expectedSize != null && expectedSize > 0L && archive.length() != expectedSize) {
        archive.delete()
        return null
    }
    val containsExpectedEntry =
        runCatching {
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory && entry.name.endsWith(entryExtension, ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    if (containsExpectedEntry) return archive
    archive.delete()
    return null
}

internal class OamBundleProgressArbiter(
    private val emit: (OamDownloadProgress) -> Unit,
) {
    private val lock = Any()
    private var networkOperationCount = 0
    private var activeExtraction: Job? = null
    private var awaitedExtraction: Deferred<*>? = null
    private var latestExtractionProgress: OamDownloadProgress? = null

    suspend fun <T> runNetwork(block: suspend ((OamDownloadProgress) -> Unit) -> T): T {
        synchronized(lock) { networkOperationCount += 1 }
        return try {
            block(::onNetworkProgress)
        } finally {
            synchronized(lock) {
                networkOperationCount = (networkOperationCount - 1).coerceAtLeast(0)
                emitExtractionIfVisible()
            }
        }
    }

    suspend fun <T> runExtraction(block: suspend ((OamDownloadProgress) -> Unit) -> T): T {
        val extractionJob = coroutineContext.job
        synchronized(lock) {
            activeExtraction = extractionJob
            latestExtractionProgress = null
        }
        return try {
            block(::onExtractionProgress)
        } finally {
            synchronized(lock) {
                activeExtraction = null
                latestExtractionProgress = null
            }
        }
    }

    suspend fun <T> awaitExtraction(extraction: Deferred<T>): T =
        try {
            synchronized(lock) {
                awaitedExtraction = extraction
                emitExtractionIfVisible()
            }
            extraction.await()
        } finally {
            synchronized(lock) { awaitedExtraction = null }
        }

    fun emitForeground(progress: OamDownloadProgress) {
        synchronized(lock) { emit(progress) }
    }

    private fun onNetworkProgress(progress: OamDownloadProgress) {
        synchronized(lock) { emit(progress) }
    }

    private fun onExtractionProgress(progress: OamDownloadProgress) {
        synchronized(lock) {
            latestExtractionProgress = progress
            emitExtractionIfVisible()
        }
    }

    private fun emitExtractionIfVisible() {
        if (activeExtraction != null && activeExtraction === awaitedExtraction && networkOperationCount == 0) {
            latestExtractionProgress?.let(emit)
        }
    }
}

internal class OamProgressThrottler(
    private val minimumIntervalMs: Long = 1_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var lastEmittedAtMs: Long? = null
    private var lastPhase: String? = null

    var requestedCount: Long = 0L
        private set
    var emittedCount: Long = 0L
        private set

    val suppressedCount: Long
        get() = requestedCount - emittedCount

    @Synchronized
    fun shouldEmit(progress: OamDownloadProgress): Boolean {
        requestedCount += 1L
        val now = nowMs()
        val previousAt = lastEmittedAtMs
        val shouldEmit =
            previousAt == null ||
                progress.phase != lastPhase ||
                now - previousAt >= minimumIntervalMs
        if (shouldEmit) {
            emittedCount += 1L
            lastEmittedAtMs = now
            lastPhase = progress.phase
        }
        return shouldEmit
    }
}

internal fun shouldFetchRemoteMetadataBeforeDownload(
    localFileAvailable: Boolean,
    completedArchiveAvailable: Boolean = false,
    forceDownload: Boolean = false,
): Boolean = !forceDownload && (localFileAvailable || completedArchiveAvailable)

// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("LongParameterList")
internal fun OamDownloadProgress.withDemBatchContext(
    tileIndex: Int,
    tileCount: Int,
    tileId: String,
    sourceLabel: String,
    isLargeDetailedDownload: Boolean,
    resources: Resources,
): OamDownloadProgress =
    copy(
        detail =
            resources.getString(
                R.string.download_dem_tile_progress_detail,
                if (isLargeDetailedDownload) {
                    resources.getString(
                        R.string.download_large_detailed_dem,
                    )
                } else {
                    resources.getString(R.string.download_dem, sourceLabel)
                },
                tileIndex + 1,
                tileCount,
                tileId,
            ),
    )
