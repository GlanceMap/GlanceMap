package com.glancemap.glancemapwearos.presentation.features.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OamBundleDownloaderTelemetryTest {
    @Test
    fun `DEM completion reports bounded outcome counts and samples`() {
        val results =
            listOf(
                demTile("N40E000", DemTileDownloadOutcome.DOWNLOADED),
                demTile("N40E001", DemTileDownloadOutcome.REUSED),
            ) +
                (1..6).map { demTile("N40E00${it + 1}", DemTileDownloadOutcome.KNOWN_UNAVAILABLE) } +
                (1..6).map { demTile("N41E00${it + 1}", DemTileDownloadOutcome.NEW_404) }

        val line =
            demCompleteTelemetryLine(
                areaId = "croatia",
                sourceId = "mapzen_skadi_1s",
                requiredTileCount = results.size,
                tileResults = results,
                bytes = 1234L,
                durationMs = 5678L,
            )

        assertTrue(line.contains("downloaded=1 reused=1 knownUnavailable=6 new404=6 ready=2 unavailable=12"))
        assertTrue(line.contains("knownUnavailableTiles=N40E002,N40E003,N40E004,N40E005,N40E006"))
        assertTrue(line.contains("moreKnownUnavailable=1"))
        assertTrue(line.contains("new404Tiles=N41E002,N41E003,N41E004,N41E005,N41E006"))
        assertTrue(line.contains("moreNew404=1"))
    }

    @Test
    fun `known missing and fresh 404 remain distinct and empty samples are omitted`() {
        val line =
            demCompleteTelemetryLine(
                areaId = "croatia",
                sourceId = "mapzen_skadi_1s",
                requiredTileCount = 2,
                tileResults =
                    listOf(
                        demTile("N42E004", DemTileDownloadOutcome.REUSED),
                        demTile("N42E005", DemTileDownloadOutcome.DOWNLOADED),
                    ),
                bytes = 10L,
                durationMs = 20L,
            )

        assertTrue(line.contains("knownUnavailable=0 new404=0 ready=2 unavailable=0"))
        assertFalse(line.contains("knownUnavailableTiles="))
        assertFalse(line.contains("new404Tiles="))
    }

    @Test
    fun `non404 terminal telemetry keeps tile context and bounds the error`() {
        val line =
            demFailedTelemetryLine(
                areaId = "croatia",
                sourceId = "mapzen_skadi_1s",
                tileId = "N43E006",
                error = IOException("HTTP 500 https://example.invalid/secret " + "x".repeat(200)),
            )

        assertTrue(line.startsWith("event=dem_failed area=croatia source=mapzen_skadi_1s tile=N43E006"))
        assertTrue(line.contains("errorType=IOException"))
        assertTrue(line.contains("error=HTTP_500_url_"))
        assertFalse(line.contains("https://example.invalid"))
        assertTrue(line.length < 260)
    }

    private fun demTile(
        tileId: String,
        outcome: DemTileDownloadOutcome,
    ): DemTileDownloadResult =
        DemTileDownloadResult(
            tileId = tileId,
            stored = true,
            downloaded = outcome == DemTileDownloadOutcome.DOWNLOADED,
            available = outcome == DemTileDownloadOutcome.DOWNLOADED || outcome == DemTileDownloadOutcome.REUSED,
            outcome = outcome,
        )
}
