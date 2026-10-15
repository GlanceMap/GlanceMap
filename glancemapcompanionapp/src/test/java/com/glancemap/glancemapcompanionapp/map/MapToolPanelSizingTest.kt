package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MapToolPanelSizingTest {
    @Test
    fun freshPanelUses35PercentAndSkipsMissingOrDuplicateCustomSize() {
        val panel = MapToolPanelState().select(MapTool.MAPS)
        assertEquals(0.35f, panel.splitFraction, 0f)
        assertNull(panel.customSplitFraction)
        assertEquals(MapToolPanelMode.EXPANDED, panel.cycleSize().mode)
        assertEquals(panel, panel.cycleSize().cycleSize())
        assertEquals(MapToolPanelSizeTarget.FULL, panel.resize(0.35f).nextSizeTarget)
        assertEquals(MapToolPanelSizeTarget.FULL, panel.resize(0.354f).nextSizeTarget)
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
        assertEquals(0.35f, default.splitFraction, 0f)
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
        assertEquals(1f, bounded.customSplitFraction)
    }

    @Test
    fun everyDistinctCustomRatioCanBeRestoredIncludingNearDefaultAndNearFullscreen() {
        for (fraction in listOf(0.354f, 0.987f, 1f)) {
            val custom = MapToolPanelState().select(MapTool.MAPS).resize(fraction)
            assertEquals(custom, custom.cycleSize().cycleSize().cycleSize())
        }
    }

    @Test
    fun toolbarPanelsShareTheDraggedSizeThroughSwitchCloseAndReopen() {
        val custom = MapToolPanelState().select(MapTool.HIKE).resize(0.61f)
        val layers = custom.select(MapTool.LAYER)
        val library = layers.select(MapTool.LIBRARY)
        val hike = library.select(MapTool.HIKE)
        assertEquals(custom, hike)
        listOf(MapTool.HIKE, MapTool.LAYER, MapTool.LIBRARY).forEach { tool ->
            assertEquals(custom.copy(activeTool = tool), custom.close().select(tool))
        }
        val default = hike.cycleSize().cycleSize()
        assertEquals(0.35f, default.select(MapTool.LAYER).select(MapTool.LIBRARY).splitFraction, 0f)
        assertEquals(0.61f, default.select(MapTool.LAYER).cycleSize().splitFraction, 0f)
    }

    @Test
    fun oldDefaultUpgradesWhileExistingCustomSizesRemainAvailable() {
        listOf(null, 0.61f).forEach { custom ->
            val migrated =
                MapToolPanelState(splitFraction = 0.32f, customSplitFraction = custom).normalizedSizing()
            assertEquals(0.35f, migrated.splitFraction, 0f)
            assertEquals(custom, migrated.customSplitFraction)
        }
        val custom = MapToolPanelState().select(MapTool.HIKE).resize(0.32f)
        assertEquals(custom, custom.normalizedSizing())
        assertEquals(custom, custom.cycleSize().cycleSize().cycleSize())
    }

    @Test
    fun portraitRangeOnlyReservesTheToolbarAndHeader() {
        val range = mapToolPanelFractionRange(1_000, 112, 0)
        assertEquals(0.112f, range.start, 0.0001f)
        assertEquals(1f, range.endInclusive, 0f)
        assertEquals(1f..1f, mapToolPanelFractionRange(100, 112, 0))
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
        assertEquals(0.35f..0.35f, mapToolPanelFractionRange(0, 144, 160))
    }
}
