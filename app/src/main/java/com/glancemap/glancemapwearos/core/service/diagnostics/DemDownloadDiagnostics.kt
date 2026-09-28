package com.glancemap.glancemapwearos.core.service.diagnostics

import java.util.ArrayDeque

internal data class DemDownloadSummary(
    val eventCount: Int,
    val droppedLineCount: Int,
    val maxBufferedLines: Int,
    val startedCount: Int,
    val terminalCount: Int,
    val readyCount: Int,
    val completeWithUnavailableCount: Int,
    val partialCount: Int,
    val terminalFailureCount: Int,
    val downloadedCount: Int,
    val skippedCount: Int,
    val missingCount: Int,
    val failedCount: Int,
    val resumeAttemptCount: Int,
    val resumeRestartCount: Int,
    val validationFailureCount: Int,
    val networkUnavailableCount: Int,
    val terminalSummariesBySource: List<DemDownloadSourceTerminalSummary>,
) {
    val activityState: String
        get() =
            when {
                eventCount == 0 -> "no_events"
                failedCount > 0 || partialCount > 0 || terminalFailureCount > 0 -> "events_with_failures"
                readyCount > 0 || completeWithUnavailableCount > 0 -> "completed"
                startedCount > 0 -> "active_or_interrupted"
                else -> "events_without_download_start"
            }

    val diagnosticContext: String
        get() =
            if (eventCount == 0) {
                "no_dem_download_activity_captured; likely_disabled_not_requested_or_no_dem_region_open"
            } else {
                "dem_download_activity_captured"
            }
}

internal data class DemDownloadSourceTerminalSummary(
    val sourceId: String,
    val terminalCount: Int,
    val readyCount: Int,
    val completeWithUnavailableCount: Int,
    val partialCount: Int,
    val terminalFailureCount: Int,
)

internal object DemDownloadDiagnostics {
    private const val TAG = "DemDownload"
    private const val MAX_LINES = 1200

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var droppedLines: Int = 0

    fun clear() {
        synchronized(lock) {
            lines.clear()
            droppedLines = 0
        }
    }

    fun snapshotLines(): List<String> = synchronized(lock) { lines.toList() }

    fun droppedLineCount(): Int = synchronized(lock) { droppedLines }

    fun maxBufferedLines(): Int = MAX_LINES

    fun summary(): DemDownloadSummary =
        synchronized(lock) {
            val snapshot = lines.toList()
            val terminalLines = snapshot.filter { it.startsWith("event=complete ") }
            DemDownloadSummary(
                eventCount = snapshot.size,
                droppedLineCount = droppedLines,
                maxBufferedLines = MAX_LINES,
                startedCount = snapshot.count { it.startsWith("event=start ") },
                terminalCount = terminalLines.size,
                readyCount = terminalLines.count { it.hasTerminalStatus("ready") },
                completeWithUnavailableCount =
                    terminalLines.count { it.hasTerminalStatus("complete_with_unavailable") },
                partialCount = terminalLines.count { it.hasTerminalStatus("partial") },
                terminalFailureCount =
                    terminalLines.count { it.hasTerminalStatus("map_area_failed") },
                downloadedCount = snapshot.count { it.startsWith("event=tile_downloaded ") },
                skippedCount = snapshot.count { it.startsWith("event=tile_skipped ") },
                missingCount = snapshot.count { it.startsWith("event=tile_missing ") },
                failedCount = snapshot.count { it.startsWith("event=tile_failed ") },
                resumeAttemptCount = snapshot.count { it.startsWith("event=tile_resume_attempt ") },
                resumeRestartCount = snapshot.count { it.startsWith("event=tile_resume_restart ") },
                validationFailureCount = snapshot.count { it.startsWith("event=tile_validation_failed ") },
                networkUnavailableCount = snapshot.count { it.contains(" networkUnavailable=true") },
                terminalSummariesBySource = terminalLines.toTerminalSummariesBySource(),
            )
        }

    fun record(
        event: String,
        detail: String = "",
    ) {
        val line =
            buildString {
                append("event=").append(event)
                if (detail.isNotBlank()) {
                    append(' ').append(detail)
                }
            }
        push(line)
        DebugTelemetry.log(TAG, line)
    }

    private fun push(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                droppedLines += 1
            }
        }
    }
}

private fun String.hasTerminalStatus(status: String): Boolean {
    val token = " status=$status"
    return contains("$token ") || endsWith(token)
}

private fun List<String>.toTerminalSummariesBySource(): List<DemDownloadSourceTerminalSummary> =
    groupBy { line -> line.substringAfter("source=", missingDelimiterValue = "unknown").substringBefore(' ') }
        .map { (sourceId, lines) ->
            DemDownloadSourceTerminalSummary(
                sourceId = sourceId,
                terminalCount = lines.size,
                readyCount = lines.count { it.hasTerminalStatus("ready") },
                completeWithUnavailableCount =
                    lines.count { it.hasTerminalStatus("complete_with_unavailable") },
                partialCount = lines.count { it.hasTerminalStatus("partial") },
                terminalFailureCount = lines.count { it.hasTerminalStatus("map_area_failed") },
            )
        }.sortedBy(DemDownloadSourceTerminalSummary::sourceId)
