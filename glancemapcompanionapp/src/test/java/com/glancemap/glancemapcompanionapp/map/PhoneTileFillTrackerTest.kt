package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTileFillTrackerTest {
    @Test
    fun firstViewportFillIsTimedFromTheFirstDraw() {
        val tracker = PhoneTileFillTracker()
        tracker.onFirstDraw(nowMs = 1_000L, coverage = coverage(drawable = 0), renderedCount = 40)

        assertTrue(tracker.isFilling)
        assertNull(tracker.onCoverage(coverage(drawable = 10, pending = 15), nowMs = 1_400L, renderedCount = 50))
        val fill = tracker.onCoverage(coverage(drawable = 25, pending = 0), nowMs = 2_250L, renderedCount = 65)!!

        assertTrue(fill.firstViewport)
        assertEquals(1_250L, fill.fillMs)
        assertEquals(0, fill.drawableAtStart)
        assertEquals(25, fill.renderedDuringFill)
        assertEquals(20.0, fill.tilesPerSecond!!, 0.001)
        assertFalse(tracker.isFilling)
    }

    @Test
    fun aWarmViewportCompletesAtTheFirstSample() {
        val tracker = PhoneTileFillTracker()
        tracker.onFirstDraw(nowMs = 500L, coverage = coverage(drawable = 25))

        val fill = tracker.onCoverage(coverage(drawable = 25), nowMs = 520L)!!

        assertEquals(20L, fill.fillMs)
        assertEquals(25, fill.drawableAtStart)
        assertNull(fill.tilesPerSecond)
    }

    @Test
    fun laterPansAreTimedFromTheFirstIncompleteSample() {
        val tracker = PhoneTileFillTracker()
        tracker.onFirstDraw(nowMs = 0L, coverage = coverage(drawable = 25))
        tracker.onCoverage(coverage(drawable = 25), nowMs = 10L)

        assertNull(tracker.onCoverage(coverage(drawable = 25), nowMs = 100L))
        assertNull(tracker.onCoverage(coverage(drawable = 18, pending = 7), nowMs = 2_000L))
        assertNull(tracker.onCoverage(coverage(drawable = 20, pending = 5), nowMs = 2_300L))
        val fill = tracker.onCoverage(coverage(drawable = 25), nowMs = 2_900L)!!

        assertFalse(fill.firstViewport)
        assertEquals(900L, fill.fillMs)
        assertEquals(18, fill.drawableAtStart)
    }

    @Test
    fun pendingJobsKeepTheViewportIncompleteAndEmptyCoverageIsIgnored() {
        val tracker = PhoneTileFillTracker()
        tracker.onFirstDraw(nowMs = 0L, coverage = coverage(drawable = 0))

        assertNull(tracker.onCoverage(PhoneFirstVisibleTileCoverage(), nowMs = 50L))
        assertNull(tracker.onCoverage(coverage(drawable = 25, pending = 1), nowMs = 100L))
        assertTrue(tracker.isFilling)
    }

    @Test
    fun detailLineCarriesTheCountersNeededForDeviceComparison() {
        val fill = PhoneTileFillEvent(true, 812L, 25, coverage(drawable = 25), renderedDuringFill = 0)
        val stats = PhoneMapsforgeTileCacheStats(true, 0, 25, 0, true)

        assertEquals(
            "renderer=3 layer=7 first=true zoom=8 fillMs=812 visible=25 drawableAtStart=25 " +
                "renderedDuringFill=0 tilesPerSec=na renderedTiles=0 diskReads=25 diskReadFailures=0",
            phoneViewportFillDetail(3L, 7, 8, fill, stats),
        )
    }

    @Test
    fun theRenderRateUsesOnlyTilesRenderedDuringThatFill() {
        val tracker = PhoneTileFillTracker()
        tracker.onFirstDraw(nowMs = 0L, coverage = coverage(drawable = 25), renderedCount = 100)
        tracker.onCoverage(coverage(drawable = 25), nowMs = 10L, renderedCount = 100)

        tracker.onCoverage(coverage(drawable = 5, pending = 20), nowMs = 1_000L, renderedCount = 130)
        val fill = tracker.onCoverage(coverage(drawable = 25), nowMs = 3_000L, renderedCount = 150)!!

        assertEquals(20, fill.renderedDuringFill)
        assertEquals(10.0, fill.tilesPerSecond!!, 0.001)
    }

    private fun coverage(
        drawable: Int,
        pending: Int = 0,
    ) = PhoneFirstVisibleTileCoverage(
        visibleTileCount = 25,
        drawableVisibleTiles = drawable,
        parentFallbackTiles = 0,
        pendingJobCount = pending,
    )
}
