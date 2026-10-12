package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MapToolPanelSizingTest {
    @Test
    fun freshPanelUses32PercentAndSkipsMissingOrDuplicateCustomSize() {
        val panel = MapToolPanelState().select(MapTool.MAPS)
        assertEquals(0.32f, panel.splitFraction, 0f)
        assertNull(panel.customSplitFraction)
        assertEquals(MapToolPanelMode.EXPANDED, panel.cycleSize().mode)
        assertEquals(panel, panel.cycleSize().cycleSize())
        assertEquals(MapToolPanelSizeTarget.FULL, panel.resize(0.32f).nextSizeTarget)
        assertEquals(MapToolPanelSizeTarget.FULL, panel.resize(0.324f).nextSizeTarget)
    }

    @Test
    fun buttonCyclesFullDefaultCustomWithoutOverwritingTheCustomSize() {
        val custom = MapToolPanelState().select(MapTool.GPX).resize(0.48f)
        val full = custom.cycleSize()
        val default = full.cycleSize()
        val restored = default.cycleSize()

        assertEquals(MapToolPanelMode.EXPANDED, full.mode)
        assertEquals(0.48f, full.customSplitFraction)
        assertEquals(MapToolPanelMode.SPLIT, default.mode)
        assertEquals(0.32f, default.splitFraction, 0f)
        assertEquals(0.48f, default.customSplitFraction)
        assertEquals(custom, restored)
        assertEquals(full, restored.cycleSize())
    }

    @Test
    fun closeReopenToolSwitchAndBackKeepTheLastNonFullscreenSize() {
        val custom = MapToolPanelState().select(MapTool.MAPS).resize(0.61f)
        assertEquals(custom.copy(activeTool = MapTool.POI), custom.select(MapTool.POI))
        assertEquals(custom, custom.close().select(MapTool.MAPS))
        assertEquals(custom, custom.expand().close().select(MapTool.MAPS))
        assertEquals(custom, custom.expand().back())
        assertEquals(custom.close(), custom.back())

        val default = custom.expand().cycleSize()
        assertEquals(default, default.close().select(MapTool.MAPS))
        assertEquals(0.61f, default.customSplitFraction)
    }

    @Test
    fun resizeCanLeaveFullscreenAndRejectsInvalidOrClosedRequests() {
        val panel = MapToolPanelState().select(MapTool.MAPS).expand()
        val resized = panel.resize(0.42f)
        assertEquals(MapToolPanelMode.SPLIT, resized.mode)
        assertEquals(0.42f, resized.customSplitFraction)
        assertSame(panel, panel.resize(Float.NaN))
        assertSame(panel, panel.resize(Float.POSITIVE_INFINITY))
        val closed = MapToolPanelState()
        assertSame(closed, closed.resize(0.5f))
        assertSame(closed, closed.cycleSize())
    }

    @Test
    fun storedSizingNormalizesNonfiniteAndOutOfRangeValues() {
        val corrupt = MapToolPanelState(splitFraction = Float.NaN, customSplitFraction = Float.POSITIVE_INFINITY)
        assertEquals(MapToolPanelState(), corrupt.normalizedSizing())
        val bounded = MapToolPanelState(splitFraction = -1f, customSplitFraction = 2f).normalizedSizing()
        assertEquals(0.05f, bounded.splitFraction, 0f)
        assertEquals(0.95f, bounded.customSplitFraction)
    }

    @Test
    fun physicalMinimumsKeepPanelAndMapUsableAcrossWindowSizes() {
        val portrait = mapToolPanelFractionRange(1_000, 144, 160)
        assertEquals(0.144f, portrait.start, 0.0001f)
        assertEquals(0.84f, portrait.endInclusive, 0.0001f)
        val wide = mapToolPanelFractionRange(800, 200, 240)
        assertEquals(0.25f, wide.start, 0.0001f)
        assertEquals(0.7f, wide.endInclusive, 0.0001f)
        assertEquals(0.5f..0.5f, mapToolPanelFractionRange(100, 144, 160))
        assertEquals(0.32f..0.32f, mapToolPanelFractionRange(0, 144, 160))
    }
}
