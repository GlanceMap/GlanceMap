package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldWindRenderProgressTest {
    @Test
    fun completeVisibleImageryDoesNotWaitForUnrelatedImageryRequests() {
        val progress = WorldWindRenderProgress()
        val visible = WorldWindVisibleTiles(4, 4)
        assertEquals(99, progress.update(visible, 0, 2, 0))
        assertEquals(100, progress.update(visible, 0, 2, 500))
        assertEquals(75, progress.update(WorldWindVisibleTiles(3, 4), 0, 2, 600))
    }

    @Test
    fun unknownOrEmptyVisibilityStillWaitsForImageryWork() {
        val progress = WorldWindRenderProgress()
        assertNull(progress.update(null, 0, 1, 0))
        assertEquals(99, progress.update(WorldWindVisibleTiles(0, 0), 0, 1, 100))
        assertEquals(99, progress.update(WorldWindVisibleTiles(0, 0), 0, 1, 1000))
    }

    @Test
    fun reportsVisibleTileProgressAndRestartsForANewView() {
        val progress = WorldWindRenderProgress()
        assertNull(progress.update(null, pendingElevation = 0, pendingImagery = 0, nowMs = 0))
        assertEquals(50, progress.update(WorldWindVisibleTiles(2, 4), 0, 2, 100))
        assertEquals(99, progress.update(WorldWindVisibleTiles(4, 4), 0, 0, 200))
        assertEquals(100, progress.update(WorldWindVisibleTiles(4, 4), 0, 0, 700))
        assertEquals(75, progress.update(WorldWindVisibleTiles(3, 4), 0, 1, 800))
    }

    @Test
    fun elevationAndUnknownVisibleImageryWorkRestartTheQuietPeriod() {
        val progress = WorldWindRenderProgress()
        val visible = WorldWindVisibleTiles(4, 4)
        assertEquals(99, progress.update(visible, 1, 0, 0))
        assertEquals(99, progress.update(visible, 0, 0, 100))
        assertEquals(99, progress.update(WorldWindVisibleTiles(0, 0), 0, 1, 500))
        assertEquals(99, progress.update(visible, 0, 0, 600))
        assertEquals(100, progress.update(visible, 0, 0, 1100))
    }

    @Test
    fun renderTimingMeasuresFirstCompletionFromSessionStartAndEachBusyPeriod() {
        val timing = WorldWindRenderTiming(sessionStartedMs = 1_000)
        assertNull(timing.update(complete = false, nowMs = 1_500))
        assertEquals(3_000L, timing.update(complete = true, nowMs = 4_000))
        assertNull(timing.update(complete = true, nowMs = 4_250))
        assertEquals(3_000L, timing.firstCompleteMs)

        assertNull(timing.update(complete = false, nowMs = 10_000))
        assertNull(timing.update(complete = false, nowMs = 10_250))
        assertEquals(1_500L, timing.update(complete = true, nowMs = 11_500))

        assertEquals(3_000L, timing.firstCompleteMs)
        assertEquals(2L, timing.completions.get())
        assertEquals(4_500L, timing.busyTotalMs.get())
        assertEquals(3_000L, timing.busyMaxMs.get())
        assertEquals(
            "render_first_complete_ms=3000 render_completions=2 render_busy_ms_total=4500 render_busy_ms_max=3000",
            timing.logFields(),
        )
    }

    @Test
    fun renderTimingDropsABusyPeriodInterruptedByScreenOff() {
        val timing = WorldWindRenderTiming(sessionStartedMs = 0)
        timing.update(complete = false, nowMs = 100)
        timing.pause()
        assertNull(timing.update(complete = true, nowMs = 60_000))

        timing.update(complete = false, nowMs = 61_000)
        assertEquals(400L, timing.update(complete = true, nowMs = 61_400))
        assertEquals(1L, timing.completions.get())
        assertEquals(61_400L, timing.firstCompleteMs)
    }
}
