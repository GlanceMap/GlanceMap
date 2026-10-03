package com.glancemap.glancemapwearos.core.service.diagnostics.export

import org.junit.Assert.assertEquals
import org.junit.Test

class BundleDownloadTelemetrySummaryTest {
    @Test
    fun `bundle telemetry separates aggregate DEM and progress overhead`() {
        val summary =
            deriveBundleDownloadTelemetrySummary(
                listOf(
                    "07-14 19:20:00.000 [OamDownload] event=download_request areas=1 dem=true",
                    "07-14 19:20:01.000 [OamDownload] event=bundle_start area=ethiopia_somalia",
                    "07-14 19:20:02.000 [OamDownload] event=download_complete label=Map bytes=1000",
                    "07-14 19:20:03.000 [OamDownload] event=dem_plan source=mapzen_skadi_1s tiles=506 large=true",
                    "07-14 20:00:00.000 [OamDownload] event=download_complete label=DEM bytes=7000",
                    "07-14 20:00:01.000 [OamDownload] event=dem_complete source=mapzen_skadi_1s " +
                        "tiles=506 downloaded=500 reused=4 knownUnavailable=1 new404=1 " +
                        "ready=504 unavailable=2 bytes=3500000 durationMs=2323412",
                    "07-14 20:00:02.000 [OamDownload] event=progress_throttle_summary owner=service " +
                        "requested=8000 emitted=1200 suppressed=6800",
                    "07-14 20:00:03.000 [OamDownload] event=foreground_keepalive_acquired " +
                        "wakeLock=true wifiLock=true",
                    "07-14 20:00:04.000 [OamDownload] event=foreground_keepalive_released " +
                        "wakeLock=true wifiLock=true",
                    "07-14 20:00:05.000 [OamDownload] event=bundle_complete area=ethiopia_somalia durationMs=3000000",
                    "07-14 20:00:06.000 [Other] event=download_complete label=DEM bytes=9999",
                ),
            )

        assertEquals(10, summary.eventCount)
        assertEquals(1, summary.requestCount)
        assertEquals(1, summary.bundleStartedCount)
        assertEquals(1, summary.bundleCompletedCount)
        assertEquals(BundleDownloadFileTotals(1, 1000L), summary.mapFiles)
        assertEquals(BundleDownloadFileTotals(1, 7000L), summary.demFiles)
        assertEquals(506, summary.demPlannedTileCount)
        assertEquals(1, summary.largeDetailedDemPlanCount)
        assertEquals(setOf("mapzen_skadi_1s"), summary.demSources)
        assertEquals(500, summary.demDownloadedTileCount)
        assertEquals(4, summary.demReusedTileCount)
        assertEquals(1, summary.demKnownUnavailableTileCount)
        assertEquals(1, summary.demNew404TileCount)
        assertEquals(504, summary.demReadyTileCount)
        assertEquals(2, summary.demUnavailableTileCount)
        assertEquals(3_500_000L, summary.demReadyBytes)
        assertEquals(2_323_412L, summary.demDurationMs)
        assertEquals(8_000L, summary.progressUpdatesRequested)
        assertEquals(1_200L, summary.progressUpdatesEmitted)
        assertEquals(6_800L, summary.progressUpdatesSuppressed)
        assertEquals(1, summary.keepaliveAcquireCount)
        assertEquals(1, summary.keepaliveReleaseCount)
    }
}
