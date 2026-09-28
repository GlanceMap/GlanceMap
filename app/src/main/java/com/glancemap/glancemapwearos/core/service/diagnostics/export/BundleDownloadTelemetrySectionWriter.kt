package com.glancemap.glancemapwearos.core.service.diagnostics.export

internal fun Appendable.writeBundleDownloadSummarySection(summary: BundleDownloadTelemetrySummary) {
    appendLine()
    appendLine("Bundle Download Summary")
    appendLine("eventCount=${summary.eventCount}")
    appendLine("requestCount=${summary.requestCount}")
    appendLine("bundleStartedCount=${summary.bundleStartedCount}")
    appendLine("bundleCompletedCount=${summary.bundleCompletedCount}")
    appendLine("bundleFailedCount=${summary.bundleFailedCount}")
    writeBundleFileTotals("map", summary.mapFiles)
    writeBundleFileTotals("poi", summary.poiFiles)
    writeBundleFileTotals("routing", summary.routingFiles)
    writeBundleFileTotals("dem", summary.demFiles)
    appendLine("demPlanCount=${summary.demPlanCount}")
    appendLine("demPlannedTileCount=${summary.demPlannedTileCount}")
    appendLine("largeDetailedDemPlanCount=${summary.largeDetailedDemPlanCount}")
    appendLine("demSources=${summary.demSources.joinToString(",").ifBlank { "na" }}")
    appendLine("demCompletedCount=${summary.demCompletedCount}")
    appendLine("demDownloadedTileCount=${summary.demDownloadedTileCount}")
    appendLine("demReusedTileCount=${summary.demReusedTileCount}")
    appendLine("demKnownUnavailableTileCount=${summary.demKnownUnavailableTileCount}")
    appendLine("demNew404TileCount=${summary.demNew404TileCount}")
    appendLine("demReadyTileCount=${summary.demReadyTileCount}")
    appendLine("demUnavailableTileCount=${summary.demUnavailableTileCount}")
    appendLine("demReadyBytes=${summary.demReadyBytes}")
    appendLine("demDurationMs=${summary.demDurationMs}")
    appendLine("progressUpdatesRequested=${summary.progressUpdatesRequested}")
    appendLine("progressUpdatesEmitted=${summary.progressUpdatesEmitted}")
    appendLine("progressUpdatesSuppressed=${summary.progressUpdatesSuppressed}")
    appendLine("keepaliveAcquireCount=${summary.keepaliveAcquireCount}")
    appendLine("keepaliveReleaseCount=${summary.keepaliveReleaseCount}")
}

private fun Appendable.writeBundleFileTotals(
    prefix: String,
    totals: BundleDownloadFileTotals,
) {
    appendLine("${prefix}CompletedFileCount=${totals.completedCount}")
    appendLine("${prefix}DownloadedBytes=${totals.bytes}")
}
