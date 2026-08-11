@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapwearos.core.service.diagnostics.export

import com.glancemap.glancemapwearos.core.service.diagnostics.DemDownloadSummary
import com.glancemap.glancemapwearos.core.service.diagnostics.DiagnosticsExporter
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.ScreenStateDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TelemetryFormatters

internal fun Appendable.writeLineDumpSection(
    title: String,
    emptyMessage: String,
    lines: List<String>,
) {
    appendLine()
    appendLine(title)
    if (lines.isEmpty()) {
        appendLine(emptyMessage)
    } else {
        lines.forEach { line -> appendLine(line) }
    }
}

internal fun Appendable.writeEnergyByModeSummarySection(energySummary: EnergyDiagnostics.Summary) {
    appendLine()
    writeBatteryConsumptionSummary(energySummary.batteryUse)
    appendLine()
    appendLine("Energy By Mode Summary")
    if (energySummary.modes.isEmpty()) {
        appendLine("No energy diagnostics samples yet.")
    } else {
        energySummary.modes.forEach { (mode, stats) ->
            writeEnergyModeStats(mode, stats)
        }
    }
    appendLine()
    writeScreenStateEnergyAttribution(energySummary.screenStateEnergy)
    appendLine()
    writeGpsRuntimeSummary(energySummary.gpsRuntime)
    appendLine()
    writeProcessCpuSummary(energySummary.processCpu)
    appendLine()
    writeRuntimeAttributionSummary(energySummary.runtimeAttribution)
}

internal fun Appendable.writeScreenStateSummarySection(summary: ScreenStateDiagnostics.Summary) {
    appendLine()
    appendLine("Screen State Summary")
    appendLine("captureDurationMs=${summary.captureDurationMs}")
    appendLine("interactiveDurationMs=${summary.interactiveDurationMs}")
    appendLine("ambientDurationMs=${summary.ambientDurationMs}")
    appendLine("screenOffDurationMs=${summary.offDurationMs}")
    appendLine("appForegroundDurationMs=${summary.appForegroundDurationMs}")
    appendLine("displayTransitionCount=${summary.displayTransitionCount}")
    appendLine("appForegroundTransitionCount=${summary.appForegroundTransitionCount}")
    appendLine("currentDisplayState=${summary.currentDisplayState?.name ?: "na"}")
    appendLine("currentAppForeground=${summary.currentAppForeground?.toString() ?: "na"}")
    appendLine("openIntervalsIncluded=${summary.openIntervalsIncluded}")
}

private fun Appendable.writeBatteryConsumptionSummary(batteryUse: EnergyDiagnostics.BatteryUseStats?) {
    appendLine("Battery Consumption Summary")
    batteryUse?.let {
        appendLine("batteryUsedMah=${TelemetryFormatters.decimal(batteryUse.consumedMah, 2)}")
        appendLine("averageDrawMa=${TelemetryFormatters.decimal(batteryUse.averageDrawMa, 1)}")
        appendLine("durationMs=${batteryUse.durationMs}")
        appendLine("measurement=${batteryUse.measurement}")
        appendLine("confidence=${batteryUse.confidence}")
        appendLine("medianDrawMa=${TelemetryFormatters.decimalOrNa(batteryUse.medianDrawMa, 1)}")
        appendLine("p90DrawMa=${TelemetryFormatters.decimalOrNa(batteryUse.p90DrawMa, 1)}")
        appendLine("integratedCurrentMah=${TelemetryFormatters.decimalOrNa(batteryUse.integratedCurrentMah, 2)}")
        appendLine("chargeCounterStartUah=${batteryUse.chargeCounterStartUah?.toString() ?: "na"}")
        appendLine("chargeCounterEndUah=${batteryUse.chargeCounterEndUah?.toString() ?: "na"}")
    } ?: appendLine("No complete unplugged battery measurement yet.")
}

private fun Appendable.writeEnergyModeStats(
    mode: String,
    stats: EnergyDiagnostics.ModeStats,
) = appendLine(
    "mode[$mode]=samples=${stats.sampleCount} currentSamples=${stats.currentSampleCount} " +
        "avgCurNowUa=${stats.avgCurrentNowUa?.toString() ?: "na"} " +
        "medianAbsCurNowUa=${stats.medianAbsCurrentNowUa?.toString() ?: "na"} " +
        "medianAbsCurNowMa=${
            stats.medianAbsCurrentNowUa?.let { TelemetryFormatters.decimal(it / 1_000.0, 1) } ?: "na"
        } " +
        "minCurNowUa=${stats.minCurrentNowUa?.toString() ?: "na"} " +
        "maxCurNowUa=${stats.maxCurrentNowUa?.toString() ?: "na"} " +
        "levelMin=${stats.minLevelPct?.toString() ?: "na"} " +
        "levelMax=${stats.maxLevelPct?.toString() ?: "na"} " +
        "levelAvg=${TelemetryFormatters.decimalOrNa(stats.avgLevelPct, 1)} " +
        "tempMinC=${TelemetryFormatters.decimalOrNa(stats.minTempC, 1)} " +
        "tempMaxC=${TelemetryFormatters.decimalOrNa(stats.maxTempC, 1)} " +
        "tempAvgC=${TelemetryFormatters.decimalOrNa(stats.avgTempC, 1)}",
)

private fun Appendable.writeScreenStateEnergyAttribution(
    attribution: EnergyDiagnostics.ScreenStateEnergy?,
) {
    appendLine("Screen-State Energy Attribution")
    if (attribution == null) {
        appendLine("method=unavailable")
        appendLine("reason=charge_counter_intervals_unavailable")
        return
    }
    appendLine("method=${attribution.measurement}")
    appendLine("totalMeasuredMah=${TelemetryFormatters.decimal(attribution.totalMeasuredMah, 2)}")
    writeScreenEnergyUse(prefix = "screenOn", use = attribution.screenOn)
    writeScreenEnergyUse(prefix = "screenOff", use = attribution.screenOff)
    appendLine("attributedMah=${TelemetryFormatters.decimal(attribution.attributedMah, 2)}")
    appendLine("unattributedMah=${TelemetryFormatters.decimal(attribution.unattributedMah, 2)}")
    appendLine(
        "attributionCoveragePct=${TelemetryFormatters.decimal(attribution.attributionCoveragePct, 1)}",
    )
    appendLine("attributionConfidence=${attribution.confidence}")
}

private fun Appendable.writeScreenEnergyUse(
    prefix: String,
    use: EnergyDiagnostics.ScreenEnergyUse?,
) {
    appendLine("${prefix}MeasuredMah=${use?.consumedMah?.let { TelemetryFormatters.decimal(it, 2) } ?: "na"}")
    appendLine("${prefix}AttributedDurationMs=${use?.durationMs?.toString() ?: "na"}")
    appendLine("${prefix}ChargeCounterIntervalCount=${use?.intervalCount?.toString() ?: "na"}")
    appendLine(
        "${prefix}AverageDrawMa=${use?.averageDrawMa?.let { TelemetryFormatters.decimal(it, 1) } ?: "na"}",
    )
}

private fun Appendable.writeGpsRuntimeSummary(summary: EnergyDiagnostics.GpsRuntimeSummary) {
    appendLine("GPS Runtime Summary")
    writeGpsRuntimeStats(prefix = "screenOn", stats = summary.screenOn)
    writeGpsRuntimeStats(prefix = "screenOff", stats = summary.screenOff)
}

private fun Appendable.writeGpsRuntimeStats(
    prefix: String,
    stats: EnergyDiagnostics.GpsRuntimeStats,
) {
    appendLine("${prefix}RuntimeSampleCount=${stats.sampleCount}")
    appendLine("${prefix}GpsRequestActiveSampleCount=${stats.requestActiveSampleCount}")
    appendLine("${prefix}GpsRequestInactiveSampleCount=${stats.requestInactiveSampleCount}")
    appendLine(
        "${prefix}GpsBackends=${stats.observedBackends.ifEmpty { listOf("na") }.joinToString(",")}",
    )
    appendLine(
        "${prefix}GpsRequestIntervalsMs=${stats.observedRequestIntervalsMs.joinToString(",").ifBlank { "na" }}",
    )
}

private fun Appendable.writeProcessCpuSummary(stats: EnergyDiagnostics.ProcessCpuStats?) {
    appendLine("Process CPU Summary")
    if (stats == null) {
        appendLine("processCpuSampling=unavailable")
        return
    }
    appendLine("processCpuSampleCount=${stats.sampleCount}")
    appendLine("processCpuWallDurationMs=${stats.wallDurationMs}")
    appendLine("processCpuDurationMs=${stats.processCpuDurationMs}")
    appendLine(
        "processCpuAverageCoreUtilizationPct=" +
            TelemetryFormatters.decimalOrNa(stats.averageCoreUtilizationPct, 2),
    )
}

private fun Appendable.writeRuntimeAttributionSummary(
    summary: EnergyDiagnostics.RuntimeAttributionSummary,
) {
    appendLine("Passive Runtime Attribution")
    writeDurationStats(prefix = "partialWakeLock", stats = summary.partialWakeLocks)
    writeDurationStats(prefix = "recordingSensor", stats = summary.recordingSensors)
}

private fun Appendable.writeDurationStats(
    prefix: String,
    stats: Map<String, EnergyDiagnostics.DurationStats>,
) {
    if (stats.isEmpty()) {
        appendLine("$prefix=none")
        return
    }
    stats.forEach { (token, duration) ->
        appendLine(
            "$prefix[$token]=activations=${duration.activationCount} " +
                "observedDurationMs=${duration.observedDurationMs} activeCount=${duration.activeCount}",
        )
    }
}

internal fun Appendable.writeDemDownloadSections(
    demDownloadSummary: DemDownloadSummary,
    demDownloadLines: List<String>,
    demDownloadTruncated: Boolean,
) {
    appendLine()
    appendLine("Standalone DEM Download Summary")
    appendLine("eventCount=${demDownloadSummary.eventCount}")
    appendLine("bufferMaxLines=${demDownloadSummary.maxBufferedLines}")
    appendLine("droppedLines=${demDownloadSummary.droppedLineCount}")
    appendLine("truncated=$demDownloadTruncated")
    appendLine("startedCount=${demDownloadSummary.startedCount}")
    appendLine("completedCount=${demDownloadSummary.completedCount}")
    appendLine("downloadedCount=${demDownloadSummary.downloadedCount}")
    appendLine("skippedCount=${demDownloadSummary.skippedCount}")
    appendLine("missingCount=${demDownloadSummary.missingCount}")
    appendLine("failedCount=${demDownloadSummary.failedCount}")
    appendLine("resumeAttemptCount=${demDownloadSummary.resumeAttemptCount}")
    appendLine("resumeRestartCount=${demDownloadSummary.resumeRestartCount}")
    appendLine("validationFailureCount=${demDownloadSummary.validationFailureCount}")
    appendLine("networkUnavailableCount=${demDownloadSummary.networkUnavailableCount}")
    appendLine("activityState=${demDownloadSummary.activityState}")
    appendLine("diagnosticContext=${demDownloadSummary.diagnosticContext}")
    writeLineDumpSection(
        title = "DEM Download Events",
        emptyMessage = "No DEM download events captured yet.",
        lines = demDownloadLines,
    )
}

internal fun Appendable.writeGnssSections(
    gnssInsights: DiagnosticsExporter.GnssInsights,
    gnssLines: List<String>,
) {
    appendLine()
    appendLine("GNSS Summary")
    appendLine("statusSampleCount=${gnssInsights.statusSampleCount}")
    appendLine("startedCount=${gnssInsights.startedCount}")
    appendLine("stoppedCount=${gnssInsights.stoppedCount}")
    appendLine("firstFixCount=${gnssInsights.firstFixCount}")
    appendLine("firstFixTtffAvgMs=${if (gnssInsights.firstFixCount > 0) gnssInsights.firstFixTtffAvgMs else "na"}")
    appendLine("firstFixTtffMinMs=${if (gnssInsights.firstFixCount > 0) gnssInsights.firstFixTtffMinMs else "na"}")
    appendLine("firstFixTtffMaxMs=${if (gnssInsights.firstFixCount > 0) gnssInsights.firstFixTtffMaxMs else "na"}")
    appendLine("satellitesAvg=${if (gnssInsights.statusSampleCount > 0) TelemetryFormatters.decimal(gnssInsights.satellitesAvg, 2) else "na"}")
    appendLine("satellitesMax=${if (gnssInsights.statusSampleCount > 0) gnssInsights.satellitesMax else "na"}")
    appendLine("usedInFixAvg=${if (gnssInsights.statusSampleCount > 0) TelemetryFormatters.decimal(gnssInsights.usedInFixAvg, 2) else "na"}")
    appendLine("usedInFixMax=${if (gnssInsights.statusSampleCount > 0) gnssInsights.usedInFixMax else "na"}")
    appendLine("signalSatellitesAvg=${if (gnssInsights.statusSampleCount > 0) TelemetryFormatters.decimal(gnssInsights.signalSatellitesAvg, 2) else "na"}")
    appendLine("signalSatellitesMax=${if (gnssInsights.statusSampleCount > 0) gnssInsights.signalSatellitesMax else "na"}")
    appendLine("almanacSatellitesAvg=${if (gnssInsights.statusSampleCount > 0) TelemetryFormatters.decimal(gnssInsights.almanacSatellitesAvg, 2) else "na"}")
    appendLine("almanacSatellitesMax=${if (gnssInsights.statusSampleCount > 0) gnssInsights.almanacSatellitesMax else "na"}")
    appendLine("ephemerisSatellitesAvg=${if (gnssInsights.statusSampleCount > 0) TelemetryFormatters.decimal(gnssInsights.ephemerisSatellitesAvg, 2) else "na"}")
    appendLine("ephemerisSatellitesMax=${if (gnssInsights.statusSampleCount > 0) gnssInsights.ephemerisSatellitesMax else "na"}")
    appendLine("noRadioSignalStatusCount=${gnssInsights.noRadioSignalStatusCount}")
    appendLine("signalsNoEphemerisStatusCount=${gnssInsights.signalsNoEphemerisStatusCount}")
    appendLine("ephemerisNoFixStatusCount=${gnssInsights.ephemerisNoFixStatusCount}")
    appendLine("satellitesUsedStatusCount=${gnssInsights.satellitesUsedStatusCount}")
    appendLine("acquisitionSignalDetectedCount=${gnssInsights.acquisitionSignalDetectedCount}")
    appendLine("acquisitionEphemerisAvailableCount=${gnssInsights.acquisitionEphemerisAvailableCount}")
    appendLine("acquisitionSatellitesUsedCount=${gnssInsights.acquisitionSatellitesUsedCount}")
    appendLine("cn0AvgDbHz=${TelemetryFormatters.decimalOrNa(gnssInsights.cn0AvgDbHz, 2)}")
    appendLine("cn0MaxDbHz=${TelemetryFormatters.decimalOrNa(gnssInsights.cn0MaxDbHz, 1)}")
    appendLine("carrierFrequencyStatusCount=${gnssInsights.carrierFrequencyStatusCount}")
    appendLine("l1ObservedStatusCount=${gnssInsights.l1ObservedStatusCount}")
    appendLine("l5ObservedStatusCount=${gnssInsights.l5ObservedStatusCount}")
    appendLine("dualBandObservedStatusCount=${gnssInsights.dualBandObservedStatusCount}")
    appendLine("collectorRegisteredCount=${gnssInsights.collectorRegisteredCount}")
    appendLine("collectorUnregisteredCount=${gnssInsights.collectorUnregisteredCount}")
    appendLine("collectorInactiveCount=${gnssInsights.collectorInactiveCount}")
    appendLine("collectorPolicyDisabledCount=${gnssInsights.collectorPolicyDisabledCount}")
    appendLine("l1SatelliteMax=${if (gnssInsights.statusSampleCount > 0) gnssInsights.l1SatelliteMax else "na"}")
    appendLine("l5SatelliteMax=${if (gnssInsights.statusSampleCount > 0) gnssInsights.l5SatelliteMax else "na"}")
    writeLineDumpSection(
        title = "GNSS Events",
        emptyMessage = "No GNSS diagnostics samples captured yet.",
        lines = gnssLines,
    )
}
