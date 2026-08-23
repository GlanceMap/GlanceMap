package com.glancemap.glancemapwearos.core.service.diagnostics.export

import com.glancemap.glancemapwearos.core.service.diagnostics.COMPASS_DEEP_TRACE_SCHEMA_VERSION
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceSnapshot
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.CompassTelemetryInsights

internal fun Appendable.writeCompassDeepTraceSection(
    snapshot: CompassDeepTraceSnapshot,
    eventSummary: CompassTelemetryInsights? = null,
    headingSummary: CompassHeadingTelemetrySummary? = null,
) {
    appendLine()
    appendLine("Compass Deep Trace")
    appendLine("schemaVersion=$COMPASS_DEEP_TRACE_SCHEMA_VERSION")
    appendLine("activeAtExport=${snapshot.active}")
    appendLine("sessionCount=${snapshot.sessionCount}")
    appendLine("aggregateWindowCount=${snapshot.windowCount}")
    appendLine("droppedAggregateLines=${snapshot.droppedLines}")
    appendLine("lastStopReason=${snapshot.lastStopReason ?: "na"}")
    if (snapshot.lines.isEmpty()) {
        appendLine("No compass deep trace captured.")
    } else {
        eventSummary?.let { writeCompassDeepTraceEventSummary(it) }
        headingSummary?.let { writeCompassHeadingTelemetrySummary(it) }
        snapshot.lines.forEach(::appendLine)
    }
}

private fun Appendable.writeCompassDeepTraceEventSummary(summary: CompassTelemetryInsights) {
    appendLine()
    appendLine("Compass Deep Trace Event Summary")
    appendLine("managerStartCount=${summary.managerStartCount}")
    appendLine("rotationSettleHoldCount=${summary.rotationSettleHoldCount}")
    appendLine("rotationSettleReleaseCount=${summary.rotationSettleReleaseCount}")
    appendLine("rotationSettleReleaseReasons=${summary.rotationSettleReleaseReasons}")
    appendLine("staleSampleCount=${summary.staleSampleCount}")
    appendLine("largeJumpPendingCount=${summary.largeJumpPendingCount}")
    appendLine("largeJumpAcceptedCount=${summary.largeJumpAcceptedCount}")
    appendLine("startupSummaryCount=${summary.startupSummaryCount}")
    appendLine("fusedReadyCount=${summary.fusedReadyCount}")
    appendLine("fusedFallbackActivationCount=${summary.fusedFallbackActivationCount}")
    appendLine("continuityStartCount=${summary.continuityStartCount}")
    appendLine("continuityCompleteCount=${summary.continuityCompleteCount}")
    appendLine("headingLooksWrongReportCount=${summary.headingLooksWrongReportCount}")
    appendLine("renderPerfEventCount=${summary.renderPerfEventCount}")
}
