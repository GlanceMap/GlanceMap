package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class PhoneOfflineMapImportStage {
    DOCUMENT_SELECTED,
    METADATA_READ,
    STREAM_OPEN,
    COPY,
    PROMOTION,
    VALIDATION,
    MAPFILE_OPEN,
    MAP_METADATA,
    COMPLETE,
}

internal enum class PhoneOfflineMapImportOutcome {
    SUCCESS,
    FAILED,
}

internal enum class PhoneOfflineMapFailureCategory {
    PERMISSION,
    SOURCE_UNAVAILABLE,
    UNSUPPORTED_SOURCE,
    MAP_VALIDATION,
    COPY_OR_STORAGE,
    FOLDER_SCAN,
    OTHER,
}

internal fun phoneOfflineMapFailureCategory(
    error: PhoneOfflineMapError?,
    exception: Throwable? = null,
): PhoneOfflineMapFailureCategory =
    when {
        exception is SecurityException || error == PhoneOfflineMapError.FOLDER_PERMISSION_LOST ->
            PhoneOfflineMapFailureCategory.PERMISSION
        error == PhoneOfflineMapError.FILE_NOT_MAP ->
            PhoneOfflineMapFailureCategory.UNSUPPORTED_SOURCE
        error == PhoneOfflineMapError.MISSING || error == PhoneOfflineMapError.FILE_NOT_READABLE ->
            PhoneOfflineMapFailureCategory.SOURCE_UNAVAILABLE
        error == PhoneOfflineMapError.INVALID -> PhoneOfflineMapFailureCategory.MAP_VALIDATION
        error == PhoneOfflineMapError.FOLDER_SCAN_FAILED -> PhoneOfflineMapFailureCategory.FOLDER_SCAN
        error == PhoneOfflineMapError.COPY_FAILED -> PhoneOfflineMapFailureCategory.COPY_OR_STORAGE
        else -> PhoneOfflineMapFailureCategory.OTHER
    }

internal data class PhoneOfflineMapsforgeMetadata(
    val boundingBoxAvailable: Boolean,
    val minZoom: Int?,
    val maxZoom: Int?,
    val startPositionAvailable: Boolean,
)

internal data class PhoneOfflineMapImportAttempt(
    val outcome: PhoneOfflineMapImportOutcome,
    val failureStage: PhoneOfflineMapImportStage?,
    val sourceSizeBytes: Long?,
    val streamOpened: Boolean?,
    val bytesCopied: Long?,
    val destinationSizeBytes: Long?,
    val candidateValid: Boolean?,
    val mapFileOpened: Boolean?,
    val metadata: PhoneOfflineMapsforgeMetadata?,
    val finalError: PhoneOfflineMapError?,
    val attemptId: String = UUID.randomUUID().toString(),
    val captureSessionId: Long? = null,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val endedAtEpochMs: Long = System.currentTimeMillis(),
    val durationMillis: Long = (endedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L),
    val completionCaptured: Boolean = false,
    val stageDurationsMillis: Map<PhoneOfflineMapImportStage, Long> = emptyMap(),
    val candidateSizeBytes: Long? = null,
    val destinationSpaceBytesAtStart: Long? = null,
    val destinationSpaceBytesAtEnd: Long? = null,
    val failureCategory: PhoneOfflineMapFailureCategory? = finalError?.let { phoneOfflineMapFailureCategory(it) },
) {
    @Suppress("CyclomaticComplexMethod") // This is a linear, intentionally readable diagnostic report.
    fun toReportSection(reportSessionId: Long? = null): String =
        buildString {
            appendLine("Latest offline map import")
            appendLine(
                "Result relation: ${offlineMapResultRelation(captureSessionId, completionCaptured, reportSessionId)}",
            )
            appendLine("Attempt ID: $attemptId")
            appendLine("Capture session ID: ${captureSessionId ?: "none"}")
            appendLine("Completion event captured: $completionCaptured")
            appendLine("Started at (epoch ms): $startedAtEpochMs")
            appendLine("Ended at (epoch ms): $endedAtEpochMs")
            appendLine("Duration (ms): $durationMillis")
            appendLine("Stage durations (ms): ${stageDurationsMillis.toDiagnosticText()}")
            appendLine("Outcome: $outcome")
            appendLine("Stage: ${failureStage ?: PhoneOfflineMapImportStage.COMPLETE}")
            appendLine("Failure category: ${failureCategory ?: "none"}")
            appendLine("Source size: ${sourceSizeBytes ?: "unknown"}")
            appendLine("Stream opened: ${streamOpened ?: "unknown"}")
            appendLine("Bytes copied: ${bytesCopied ?: "unknown"}")
            appendLine("Candidate size: ${candidateSizeBytes ?: "unknown"}")
            appendLine("Installed destination size (bytes): ${destinationSizeBytes ?: "unknown"}")
            appendLine("Destination space at start: ${destinationSpaceBytesAtStart ?: "unknown"}")
            appendLine("Destination space at end: ${destinationSpaceBytesAtEnd ?: "unknown"}")
            appendLine("Candidate validation: ${candidateValid ?: "unknown"}")
            appendLine("Mapsforge MapFile opened: ${mapFileOpened ?: "unknown"}")
            metadata?.let {
                appendLine("Bounding box available: ${it.boundingBoxAvailable}")
                appendLine("Min zoom: ${it.minZoom ?: "unknown"}")
                appendLine("Max zoom: ${it.maxZoom ?: "unknown"}")
                appendLine("Start position available: ${it.startPositionAvailable}")
            }
            appendLine("Final error: ${finalError ?: "none"}")
            append("Destination: companion private maps")
        }

    fun toCaptureLine(): String =
        "event=offline_map_import_completed attemptId=$attemptId captureSessionId=${captureSessionId ?: "none"} " +
            "startedAtMs=$startedAtEpochMs endedAtMs=$endedAtEpochMs durationMs=$durationMillis outcome=$outcome " +
            "stage=${failureStage ?: PhoneOfflineMapImportStage.COMPLETE} " +
            "stageMs=${stageDurationsMillis.toDiagnosticText()} sourceBytes=${sourceSizeBytes ?: "unknown"} " +
            "copiedBytes=${bytesCopied ?: "unknown"} candidateBytes=${candidateSizeBytes ?: "unknown"} " +
            "destinationBytes=${destinationSizeBytes ?: "unknown"} " +
            "destinationSpaceStartBytes=${destinationSpaceBytesAtStart ?: "unknown"} " +
            "destinationSpaceEndBytes=${destinationSpaceBytesAtEnd ?: "unknown"} " +
            "candidate=${candidateValid ?: "unknown"} " +
            "mapFile=${mapFileOpened ?: "unknown"} error=${finalError ?: "none"} category=${failureCategory ?: "none"}"
}

/** Uses the existing local phone debug capture and retains only one safe latest-attempt summary. */
internal object PhoneOfflineMapImportDiagnostics {
    const val TAG = "PhoneOfflineMapImport"

    private val latestAttempt = AtomicReference<PhoneOfflineMapImportAttempt?>(null)
    private val latestFolderScan = AtomicReference<PhoneOfflineMapFolderScanSummary?>(null)

    fun activeCaptureSessionId(): Long? = PhoneDebugCapture.activeSessionId()

    fun importStarted(
        attemptId: String,
        startedAtEpochMs: Long,
        captureSessionId: Long?,
        destinationSpaceBytes: Long?,
    ) {
        captureSessionId?.let { sessionId ->
            PhoneDebugCapture.log(
                TAG,
                "event=offline_map_import_started attemptId=$attemptId captureSessionId=$sessionId " +
                    "startedAtMs=$startedAtEpochMs destinationSpaceBytes=${destinationSpaceBytes ?: "unknown"}",
                expectedSessionId = sessionId,
            )
        }
    }

    fun record(attempt: PhoneOfflineMapImportAttempt) {
        val completionCaptured =
            attempt.captureSessionId?.let { sessionId ->
                PhoneDebugCapture.log(TAG, attempt.toCaptureLine(), expectedSessionId = sessionId)
            } == true
        val retained = attempt.copy(completionCaptured = completionCaptured)
        latestAttempt.set(retained)
        if (completionCaptured) {
            retained.captureSessionId?.let { sessionId ->
                PhoneDebugCapture.updateSection(
                    "offline_map_import",
                    retained.toReportSection(sessionId),
                    expectedSessionId = sessionId,
                )
            }
        }
    }

    fun latestReportSection(
        reportSessionId: Long? = null,
    ): String? = latestAttempt.get()?.toReportSection(reportSessionId)

    fun beginFolderScan(): PhoneOfflineMapFolderScanTrace {
        val scanId = UUID.randomUUID().toString()
        val startedAtEpochMs = System.currentTimeMillis()
        val startedAtNanos = System.nanoTime()
        val captureSessionId = activeCaptureSessionId()
        captureSessionId?.let { sessionId ->
            PhoneDebugCapture.log(
                TAG,
                "event=offline_map_folder_scan_started scanId=$scanId " +
                    "captureSessionId=$sessionId startedAtMs=$startedAtEpochMs",
                expectedSessionId = sessionId,
            )
        }
        return PhoneOfflineMapFolderScanTrace(scanId, captureSessionId, startedAtEpochMs, startedAtNanos)
    }

    internal fun recordFolderScan(summary: PhoneOfflineMapFolderScanSummary): PhoneOfflineMapFolderScanSummary {
        val completionCaptured =
            summary.captureSessionId?.let { sessionId ->
                PhoneDebugCapture.log(TAG, summary.toCaptureLine(), expectedSessionId = sessionId)
            } == true
        val retained = summary.copy(completionCaptured = completionCaptured)
        latestFolderScan.set(retained)
        if (completionCaptured) {
            retained.captureSessionId?.let { sessionId ->
                PhoneDebugCapture.updateSection(
                    "offline_map_folder_scan",
                    retained.toReportSection(sessionId),
                    expectedSessionId = sessionId,
                )
            }
        }
        return retained
    }

    fun latestFolderScanReportSection(
        reportSessionId: Long? = null,
    ): String? = latestFolderScan.get()?.toReportSection(reportSessionId)

    internal fun latestAttempt(): PhoneOfflineMapImportAttempt? = latestAttempt.get()

    internal fun clear() {
        latestAttempt.set(null)
        latestFolderScan.set(null)
    }
}

internal data class PhoneOfflineMapFolderScanSummary(
    val scanId: String,
    val captureSessionId: Long?,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMillis: Long,
    val candidateCount: Int?,
    val importedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val failureCategory: PhoneOfflineMapFailureCategory?,
    val completionCaptured: Boolean = false,
) {
    fun toCaptureLine(): String =
        "event=offline_map_folder_scan_completed scanId=$scanId captureSessionId=${captureSessionId ?: "none"} " +
            "startedAtMs=$startedAtEpochMs endedAtMs=$endedAtEpochMs durationMs=$durationMillis " +
            "candidates=${candidateCount ?: "unknown"} imported=$importedCount " +
            "skipped=$skippedCount failed=$failedCount " +
            "category=${failureCategory ?: "none"}"

    fun toReportSection(reportSessionId: Long? = null): String =
        buildString {
            appendLine("Latest offline map folder scan")
            appendLine(
                "Result relation: ${offlineMapResultRelation(captureSessionId, completionCaptured, reportSessionId)}",
            )
            appendLine("Scan ID: $scanId")
            appendLine("Capture session ID: ${captureSessionId ?: "none"}")
            appendLine("Completion event captured: $completionCaptured")
            appendLine("Started at (epoch ms): $startedAtEpochMs")
            appendLine("Ended at (epoch ms): $endedAtEpochMs")
            appendLine("Duration (ms): $durationMillis")
            appendLine("Candidates: ${candidateCount ?: "unknown"}")
            appendLine("Imported: $importedCount")
            appendLine("Skipped: $skippedCount")
            appendLine("Failed: $failedCount")
            append("Failure category: ${failureCategory ?: "none"}")
        }
}

internal class PhoneOfflineMapFolderScanTrace internal constructor(
    private val scanId: String,
    private val captureSessionId: Long?,
    private val startedAtEpochMs: Long,
    private val startedAtNanos: Long,
) {
    private var candidates: Int? = null
    private var candidateInProgress = false
    private var imported = 0
    private var skipped = 0
    private var failed = 0
    private var failureCategory: PhoneOfflineMapFailureCategory? = null

    fun candidatesDiscovered(count: Int) {
        candidates = count
    }

    fun candidateStarted() {
        candidateInProgress = true
    }

    fun candidateCompleted(result: PhoneOfflineMapFolderSyncResult) {
        candidateInProgress = false
        imported += result.importedCount
        skipped += result.skippedCount
        if (result.error != null) {
            failed += 1
            recordFailureCategory(phoneOfflineMapFailureCategory(result.error))
        }
    }

    fun failed(category: PhoneOfflineMapFailureCategory) {
        if (candidateInProgress) failed += 1
        candidateInProgress = false
        recordFailureCategory(category)
    }

    fun fail(error: PhoneOfflineMapError): PhoneOfflineMapFolderSyncResult {
        failed(phoneOfflineMapFailureCategory(error))
        return finish(PhoneOfflineMapFolderSyncResult(error = error))
    }

    fun finish(result: PhoneOfflineMapFolderSyncResult): PhoneOfflineMapFolderSyncResult {
        finish(result.error)
        return result
    }

    private fun recordFailureCategory(category: PhoneOfflineMapFailureCategory) {
        if (failureCategory == null || category == PhoneOfflineMapFailureCategory.PERMISSION) failureCategory = category
    }

    fun finish(error: PhoneOfflineMapError?): PhoneOfflineMapFolderScanSummary {
        val finalCategory = error?.let { phoneOfflineMapFailureCategory(it) }
        val summary =
            PhoneOfflineMapFolderScanSummary(
                scanId = scanId,
                captureSessionId = captureSessionId,
                startedAtEpochMs = startedAtEpochMs,
                endedAtEpochMs = System.currentTimeMillis(),
                durationMillis = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L),
                candidateCount = candidates,
                importedCount = imported,
                skippedCount = skipped,
                failedCount = failed,
                failureCategory =
                    if (finalCategory == PhoneOfflineMapFailureCategory.PERMISSION) {
                        finalCategory
                    } else {
                        failureCategory ?: finalCategory
                    },
            )
        return PhoneOfflineMapImportDiagnostics.recordFolderScan(summary)
    }
}

private fun Map<PhoneOfflineMapImportStage, Long>.toDiagnosticText(): String =
    entries
        .sortedBy { it.key.ordinal }
        .joinToString(",") { (stage, millis) -> "${stage.name}:$millis" }

private fun offlineMapResultRelation(
    captureSessionId: Long?,
    completionCaptured: Boolean,
    reportSessionId: Long?,
): String =
    if (completionCaptured && captureSessionId != null && captureSessionId == reportSessionId) {
        "CURRENT_CAPTURE"
    } else {
        "HISTORICAL_RETAINED_RESULT"
    }
