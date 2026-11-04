package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMetricGapTest {
    @Test
    fun largerSideButtonsLeaveLessRoomForLiveMetric() {
        val small = liveMetricUpperArc(radius = 100f, edgePadding = 4f, buttonSize = 30f, clockWidth = 40f)
        val large = liveMetricUpperArc(radius = 100f, edgePadding = 4f, buttonSize = 50f, clockWidth = 40f)
        assertTrue(small.sweep > large.sweep)
    }

    @Test
    fun overlappingButtonLeavesNoNegativeSweep() {
        val arc = liveMetricUpperArc(radius = 20f, edgePadding = 4f, buttonSize = 40f, clockWidth = 20f)
        assertEquals(0f, arc.sweep, 0.001f)
    }

    @Test
    fun widerClockLeavesLessRoom() {
        val narrow = liveMetricUpperArc(radius = 100f, edgePadding = 4f, buttonSize = 40f, clockWidth = 40f)
        val wide = liveMetricUpperArc(radius = 100f, edgePadding = 4f, buttonSize = 40f, clockWidth = 70f)
        assertTrue(narrow.sweep > wide.sweep)
        assertEquals(narrow.start, wide.start, 0.001f)
    }

    @Test
    fun bothChipsShrinkOnlyWhenTheyNeedMoreRoom() {
        assertEquals(1f, liveMetricFitScale(available = 140f, contentWidth = 90f), 0.001f)
        assertEquals(1f, liveMetricFitScale(available = 90f, contentWidth = 90f), 0.001f)
        assertEquals(0.8f, liveMetricFitScale(available = 72f, contentWidth = 90f), 0.001f)
    }

    @Test
    fun unavailableRoomNeverProducesNegativeScale() {
        assertEquals(0f, liveMetricFitScale(available = 0f, contentWidth = 90f), 0.001f)
    }

    @Test
    fun compactChipsAccountForShorterRoundedEnds() {
        assertEquals(68f, liveMetricCompactContentWidth(textWidth = 28f, chipThickness = 12f), 0.001f)
    }
}
