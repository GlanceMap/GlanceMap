package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapContentVisibility
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MapToolPanelStateTest {
    @Test
    fun toolSelectionAndBackFollowClosedSplitExpandedOrder() {
        val split = MapToolPanelState().select(MapTool.GPX)
        val expanded = split.expand()

        assertEquals(MapToolPanelMode.SPLIT, split.mode)
        assertEquals(MapToolPanelMode.EXPANDED, expanded.mode)
        assertEquals(MapToolPanelMode.SPLIT, expanded.back().mode)
        assertEquals(MapToolPanelMode.CLOSED, expanded.back().back().mode)
    }

    @Test
    fun switchingToolsInSplitKeepsThePanelOpenAndTappingTheActiveToolClosesIt() {
        val switched = MapToolPanelState().select(MapTool.POI).select(MapTool.MAPS)

        assertEquals(MapTool.MAPS, switched.activeTool)
        assertEquals(MapToolPanelMode.SPLIT, switched.mode)
        assertEquals(MapToolPanelState(), switched.select(MapTool.MAPS))
        assertEquals(
            MapToolPanelMode.SPLIT,
            MapToolPanelState()
                .select(MapTool.GPX)
                .expand()
                .select(MapTool.GPX)
                .mode,
        )
    }

    @Test
    fun panelTransitionsPreserveMapSourceAndSemanticVisibility() {
        val initial =
            PhoneMapUiState(
                source = PhoneMapSource.Offline(PhoneOfflineMap(File("alps.map"))),
                contentVisibility = MapContentVisibility(gpxTracks = false, pois = true),
            )

        val changed =
            initial
                .selectTool(MapTool.MAPS)
                .expandTool()
                .collapseTool()
                .onToolBack()

        assertEquals(initial.source, changed.source)
        assertEquals(initial.contentVisibility, changed.contentVisibility)
        assertEquals(MapToolPanelMode.CLOSED, changed.toolPanel.mode)
    }

    @Test
    fun rendererSwitchingRetainsGlobalOverlayVisibility() {
        val visibility = MapContentVisibility(gpxTracks = true, pois = true)
        val offlineMap = PhoneOfflineMap(File("alps.map"))
        val onlineAgain =
            PhoneMapUiState(contentVisibility = visibility)
                .copy(source = PhoneMapSource.Offline(offlineMap))
                .copy(source = PhoneMapSource.Online)

        assertEquals(visibility, onlineAgain.contentVisibility)
    }
}
