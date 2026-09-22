package com.glancemap.glancemapwearos.core.service.diagnostics.export

import com.glancemap.glancemapwearos.core.service.diagnostics.COMPASS_DEEP_TRACE_SCHEMA_VERSION
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceEvent
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceEventRecord
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceSnapshot
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter.CompassTelemetryInsights
import com.glancemap.glancemapwearos.core.service.diagnostics.TelemetryFormatters
import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingProvenance

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
    appendLine("decisionEventCount=${snapshot.events.size}")
    appendLine("droppedDecisionEvents=${snapshot.droppedEvents}")
    appendLine("lastStopReason=${snapshot.lastStopReason ?: "na"}")
    if (snapshot.lines.isEmpty() && snapshot.events.isEmpty()) {
        appendLine("No compass deep trace captured.")
    } else {
        eventSummary?.let { writeCompassDeepTraceEventSummary(it) }
        headingSummary?.let { writeCompassHeadingTelemetrySummary(it) }
        if (snapshot.events.isNotEmpty()) {
            appendLine("Compass Deep Trace Decision Events")
            snapshot.events.forEach { event ->
                appendLine(event.toCompassDeepTraceLine())
            }
        }
        if (snapshot.lines.isNotEmpty()) {
            appendLine("Compass Deep Trace Aggregates")
            snapshot.lines.forEach(::appendLine)
        }
    }
}

@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
) // Typed event serialization intentionally keeps export tokens together.
private fun CompassDeepTraceEventRecord.toCompassDeepTraceLine(): String =
    buildString {
        append("trace_event id=").append(eventId)
        when (val value = event) {
            is CompassDeepTraceEvent.ProviderMeasurement -> {
                append(" type=provider_measurement atMs=").append(value.atElapsedMs)
                append(" provider=").append(value.provider)
                append(" sampleId=").append(value.sourceSampleId ?: "na")
                append(" sourceAtMs=").append(value.sourceMeasurementAtElapsedMs ?: "na")
                append(" callbackAtMs=").append(value.callbackArrivalAtElapsedMs ?: "na")
                append(" processingAtMs=").append(value.processingAtElapsedMs ?: "na")
                append(" disposition=").append(value.measurementDisposition ?: "na")
                append(" headingDeg=").append(value.headingDeg.formatTrace(1))
                append(" accuracy=").append(value.accuracy)
                append(" usable=").append(value.usable)
                append(" provenance=").append(value.provenance.traceToken())
            }
            is CompassDeepTraceEvent.IntegrityDecision -> {
                append(" type=integrity_decision atMs=").append(value.atElapsedMs)
                append(" provider=").append(value.provider)
                append(" sampleId=").append(value.sourceSampleId ?: "na")
                append(" headingDeg=").append(value.headingDeg.formatTrace(1))
                append(" liveErrorDeg=").append(value.liveHeadingErrorDeg.formatTrace(1))
                append(" conservativeErrorDeg=").append(value.conservativeHeadingErrorDeg.formatTrace(1))
                append(" state=").append(value.trackingState?.telemetryToken ?: "na")
                append(" reason=").append(value.trackingReason?.telemetryToken ?: "na")
                append(" relativeHeadingDeg=").append(value.relativeHeadingDeg.formatTrace(1))
                append(" disagreementDeg=").append(value.fusedRelativeDisagreementDeg.formatTrace(1))
                append(" targetDeg=").append(value.targetHeadingDeg.formatTrace(1))
                append(" trusted=").append(value.trusted)
                append(" quarantine=").append(value.quarantineActive)
                append(" recovery=").append(value.recoveryActive)
                append(" heldOutput=").append(value.heldOutput)
                append(" provenance=").append(value.provenance.traceToken())
            }
            is CompassDeepTraceEvent.Render -> {
                append(" type=render atMs=").append(value.atElapsedMs)
                append(" sampleId=").append(value.sourceSampleId ?: "na")
                append(" targetDeg=").append(value.targetHeadingDeg.formatTrace(1))
                append(" renderedDeg=").append(value.renderedHeadingDeg.formatTrace(1))
                append(" mapRotationDeg=").append(value.mapRotationDeg.formatTrace(1))
                append(" heldOutput=").append(value.heldOutput)
                append(" provenance=").append(value.provenance.traceToken())
            }
            is CompassDeepTraceEvent.Telemetry -> {
                append(" type=telemetry atMs=").append(value.atElapsedMs)
                append(" line=").append(value.line.replace('\n', ' '))
            }
            is CompassDeepTraceEvent.Marker -> {
                append(" type=marker atMs=").append(value.atElapsedMs)
                append(" marker=").append(value.type)
                append(" detail=").append(value.detail)
            }
            is CompassDeepTraceEvent.UiConfidence -> {
                append(" type=ui_confidence atMs=").append(value.atElapsedMs)
                append(" provider=").append(value.provider)
                append(" quality=").append(value.quality)
                append(" accuracyColorsEnabled=").append(value.accuracyColorsEnabled)
                append(" provenance=").append(value.provenance.traceToken())
            }
        }
    }

private fun CompassHeadingProvenance?.traceToken(): String =
    this?.let {
        "${it.provider.name.lowercase()}_${it.generation}"
    } ?: "na"

private fun Float?.formatTrace(decimals: Int): String = this?.let { TelemetryFormatters.decimal(it, decimals) } ?: "na"

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
