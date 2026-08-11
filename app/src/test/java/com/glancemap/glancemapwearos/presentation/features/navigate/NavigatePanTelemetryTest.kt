package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatePanTelemetryTest {
    @Test
    fun panSummarySeparatesInputCadenceFromViewportCadence() {
        var nowElapsedMs = 0L
        val messages = mutableListOf<String>()
        val telemetry =
            NavigatePanTelemetry(
                nowElapsedMs = { nowElapsedMs },
                debugEnabled = { true },
                log = { message -> messages += message },
            )

        telemetry.onPanStarted(
            navMode = NavMode.COMPASS_FOLLOW,
            routeToolSessionActive = false,
            zoomLevel = 15,
        )
        nowElapsedMs = 10L
        telemetry.onInputMove()
        nowElapsedMs = 25L
        telemetry.onViewportChanged(routeToolOverlayRefreshed = false)
        nowElapsedMs = 85L
        telemetry.onInputMove()
        nowElapsedMs = 100L
        telemetry.onViewportChanged(routeToolOverlayRefreshed = false)
        nowElapsedMs = 120L

        val summary =
            telemetry.onPanFinished(
                navMode = NavMode.PANNING,
                reason = "touch_up",
                zoomLevel = 15,
            )

        assertEquals(120L, summary?.durationMs ?: -1L)
        assertEquals(2, summary?.inputMoveCount ?: -1)
        assertEquals(75L, summary?.maxInputGapMs ?: -1L)
        assertEquals(2, summary?.viewportUpdateCount ?: -1)
        assertEquals(25L, summary?.firstViewportLatencyMs ?: -1L)
        assertEquals(75L, summary?.maxViewportGapMs ?: -1L)
        assertEquals(1, summary?.slowViewportGapCount ?: -1)
        assertEquals(0, summary?.routeToolOverlayRefreshCount ?: -1)
        assertTrue(messages.last().contains("event=map_pan stage=end"))
    }

    @Test
    fun panSummaryCountsRouteToolOverlayRefreshes() {
        var nowElapsedMs = 0L
        val telemetry =
            NavigatePanTelemetry(
                nowElapsedMs = { nowElapsedMs },
                debugEnabled = { true },
                log = {},
            )

        telemetry.onPanStarted(
            navMode = NavMode.PANNING,
            routeToolSessionActive = true,
            zoomLevel = 16,
        )
        nowElapsedMs = 10L
        telemetry.onViewportChanged(routeToolOverlayRefreshed = true)
        nowElapsedMs = 20L

        val summary =
            telemetry.onPanFinished(
                navMode = NavMode.PANNING,
                reason = "touch_up",
                zoomLevel = 16,
            )

        assertEquals(1, summary?.routeToolOverlayRefreshCount ?: -1)
    }
}
