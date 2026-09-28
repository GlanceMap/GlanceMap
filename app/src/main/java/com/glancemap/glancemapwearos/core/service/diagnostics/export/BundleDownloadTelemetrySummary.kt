package com.glancemap.glancemapwearos.core.service.diagnostics.export

internal data class BundleDownloadFileTotals(
    val completedCount: Int = 0,
    val bytes: Long = 0L,
)

internal data class BundleDownloadTelemetrySummary(
    val eventCount: Int = 0,
    val requestCount: Int = 0,
    val bundleStartedCount: Int = 0,
    val bundleCompletedCount: Int = 0,
    val bundleFailedCount: Int = 0,
    val mapFiles: BundleDownloadFileTotals = BundleDownloadFileTotals(),
    val poiFiles: BundleDownloadFileTotals = BundleDownloadFileTotals(),
    val routingFiles: BundleDownloadFileTotals = BundleDownloadFileTotals(),
    val demFiles: BundleDownloadFileTotals = BundleDownloadFileTotals(),
    val demPlanCount: Int = 0,
    val demPlannedTileCount: Int = 0,
    val largeDetailedDemPlanCount: Int = 0,
    val demSources: Set<String> = emptySet(),
    val demCompletedCount: Int = 0,
    val demDownloadedTileCount: Int = 0,
    val demReusedTileCount: Int = 0,
    val demKnownUnavailableTileCount: Int = 0,
    val demNew404TileCount: Int = 0,
    val demReadyTileCount: Int = 0,
    val demUnavailableTileCount: Int = 0,
    val demReadyBytes: Long = 0L,
    val demDurationMs: Long = 0L,
    val progressUpdatesRequested: Long = 0L,
    val progressUpdatesEmitted: Long = 0L,
    val progressUpdatesSuppressed: Long = 0L,
    val keepaliveAcquireCount: Int = 0,
    val keepaliveReleaseCount: Int = 0,
)

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun deriveBundleDownloadTelemetrySummary(lines: List<String>): BundleDownloadTelemetrySummary {
    var summary = BundleDownloadTelemetrySummary()
    val sources = linkedSetOf<String>()
    lines.filter { "[OamDownload]" in it }.forEach { line ->
        summary = summary.copy(eventCount = summary.eventCount + 1)
        when (line.telemetryField("event")) {
            "download_request" -> summary = summary.copy(requestCount = summary.requestCount + 1)
            "bundle_start" -> summary = summary.copy(bundleStartedCount = summary.bundleStartedCount + 1)
            "bundle_complete" -> summary = summary.copy(bundleCompletedCount = summary.bundleCompletedCount + 1)
            "bundle_failed" -> summary = summary.copy(bundleFailedCount = summary.bundleFailedCount + 1)
            "download_complete" -> {
                val bytes = line.telemetryLong("bytes")
                summary =
                    when (line.telemetryField("label")) {
                        "Map" -> summary.copy(mapFiles = summary.mapFiles.plusFile(bytes))
                        "POI" -> summary.copy(poiFiles = summary.poiFiles.plusFile(bytes))
                        "Routing" -> summary.copy(routingFiles = summary.routingFiles.plusFile(bytes))
                        "DEM" -> summary.copy(demFiles = summary.demFiles.plusFile(bytes))
                        else -> summary
                    }
            }
            "dem_plan" -> {
                line.telemetryField("source")?.let(sources::add)
                summary =
                    summary.copy(
                        demPlanCount = summary.demPlanCount + 1,
                        demPlannedTileCount = summary.demPlannedTileCount + line.telemetryInt("tiles"),
                        largeDetailedDemPlanCount =
                            summary.largeDetailedDemPlanCount +
                                if (line.telemetryField("large") == "true") 1 else 0,
                    )
            }
            "dem_complete" -> {
                line.telemetryField("source")?.let(sources::add)
                summary =
                    summary.copy(
                        demCompletedCount = summary.demCompletedCount + 1,
                        demDownloadedTileCount =
                            summary.demDownloadedTileCount + line.telemetryInt("downloaded"),
                        demReusedTileCount = summary.demReusedTileCount + line.telemetryInt("reused"),
                        demKnownUnavailableTileCount =
                            summary.demKnownUnavailableTileCount + line.telemetryInt("knownUnavailable"),
                        demNew404TileCount = summary.demNew404TileCount + line.telemetryInt("new404"),
                        demReadyTileCount = summary.demReadyTileCount + line.telemetryInt("ready"),
                        demUnavailableTileCount =
                            summary.demUnavailableTileCount + line.telemetryInt("unavailable"),
                        demReadyBytes = summary.demReadyBytes + line.telemetryLong("bytes"),
                        demDurationMs = summary.demDurationMs + line.telemetryLong("durationMs"),
                    )
            }
            "progress_throttle_summary" -> {
                summary =
                    summary.copy(
                        progressUpdatesRequested =
                            summary.progressUpdatesRequested + line.telemetryLong("requested"),
                        progressUpdatesEmitted =
                            summary.progressUpdatesEmitted + line.telemetryLong("emitted"),
                        progressUpdatesSuppressed =
                            summary.progressUpdatesSuppressed + line.telemetryLong("suppressed"),
                    )
            }
            "foreground_keepalive_acquired" ->
                summary = summary.copy(keepaliveAcquireCount = summary.keepaliveAcquireCount + 1)
            "foreground_keepalive_released" ->
                summary = summary.copy(keepaliveReleaseCount = summary.keepaliveReleaseCount + 1)
        }
    }
    return summary.copy(demSources = sources)
}

private fun BundleDownloadFileTotals.plusFile(bytes: Long) =
    copy(
        completedCount = completedCount + 1,
        bytes = this.bytes + bytes,
    )

private fun String.telemetryField(name: String): String? =
    Regex("(?:^|\\s)${Regex.escape(name)}=([^\\s]+)")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)

private fun String.telemetryInt(name: String): Int = telemetryField(name)?.toIntOrNull() ?: 0

private fun String.telemetryLong(name: String): Long = telemetryField(name)?.toLongOrNull() ?: 0L
