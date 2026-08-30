package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapContentVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun switchingToolsInSplitKeepsThePanelOpenWithoutResettingTheActiveTool() {
        val switched = MapToolPanelState().select(MapTool.POI).select(MapTool.MAPS)

        assertEquals(MapTool.MAPS, switched.activeTool)
        assertEquals(MapToolPanelMode.SPLIT, switched.mode)
        assertEquals(
            MapToolPanelMode.EXPANDED,
            MapToolPanelState()
                .select(MapTool.GPX)
                .expand()
                .select(MapTool.GPX)
                .mode,
        )
    }

    @Test
    fun primaryMapControlsStayOutsideTheSecondaryToolsLauncher() {
        assertEquals(listOf(MapTool.MAPS, MapTool.GPX, MapTool.POI), primaryMapTools)
        assertEquals(listOf(MapTool.SETTINGS), secondaryMapTools)
    }

    @Test
    fun toolsLauncherCollapsesIndependentlyFromTheSelectedSettingsPanel() {
        val selected =
            PhoneMapUiState()
                .toggleToolLauncher()
                .selectTool(MapTool.SETTINGS)

        assertTrue(selected.toolLauncherExpanded)
        assertEquals(MapTool.SETTINGS, selected.toolPanel.activeTool)
        assertEquals(MapToolPanelMode.SPLIT, selected.toolPanel.mode)
        assertFalse(primaryMapTools.contains(selected.toolPanel.activeTool))

        val collapsed = selected.toggleToolLauncher()

        assertFalse(collapsed.toolLauncherExpanded)
        assertEquals(selected.toolPanel, collapsed.toolPanel)
    }

    @Test
    fun backReturnsFeatureSettingsToItsMainPanelBeforeCollapsingThePanel() {
        val settings = PhoneMapUiState().selectTool(MapTool.GPX).expandTool().showFeatureSettings()

        assertEquals(MapToolContentMode.FEATURE_SETTINGS, settings.toolPanel.contentMode)
        val main = settings.onMapBack()
        assertEquals(MapToolContentMode.MAIN, main.toolPanel.contentMode)
        assertEquals(MapToolPanelMode.EXPANDED, main.toolPanel.mode)
        val split = main.onMapBack()
        assertEquals(MapToolPanelMode.SPLIT, split.toolPanel.mode)
        assertEquals(
            MapToolPanelMode.CLOSED,
            split.onMapBack().toolPanel.mode,
        )
    }

    @Test
    fun headerSwipeOnlyExpandsAndCollapsesAtTheApplicablePanelBoundary() {
        val split = MapToolPanelState().select(MapTool.MAPS)
        val expanded = split.onHeaderSwipe(MapToolHeaderSwipe.UP)

        assertEquals(MapToolPanelMode.EXPANDED, expanded.mode)
        assertEquals(MapToolPanelMode.SPLIT, expanded.onHeaderSwipe(MapToolHeaderSwipe.DOWN).mode)
        assertEquals(split, split.onHeaderSwipe(MapToolHeaderSwipe.DOWN))
        assertEquals(expanded, expanded.onHeaderSwipe(MapToolHeaderSwipe.UP))
    }

    @Test
    fun headerExpandButtonActionsToggleSplitAndExpandedModes() {
        val split = MapToolPanelState().select(MapTool.MAPS)
        val expanded = split.expand()

        assertEquals(MapToolPanelMode.EXPANDED, expanded.mode)
        assertEquals(MapToolPanelMode.SPLIT, expanded.collapse().mode)
    }

    @Test
    fun onlyFeatureSettingsExposeTheVisibleSubpageBackAction() {
        val featureSettings = MapToolPanelState().select(MapTool.POI).showFeatureSettings()
        val main = featureSettings.back()

        assertTrue(featureSettings.hasFeatureSettingsBack)
        assertEquals(MapToolContentMode.MAIN, main.contentMode)
        assertFalse(main.hasFeatureSettingsBack)
        assertFalse(MapToolPanelState().select(MapTool.POI).hasFeatureSettingsBack)
    }

    @Test
    fun featureSettingsDoNotChangeMapSourceOrOverlayVisibility() {
        val initial =
            PhoneMapUiState(
                source = PhoneMapSource.Offline(PhoneOfflineMap(File("alps.map"))),
                contentVisibility = MapContentVisibility(gpxTracks = true, pois = false),
            )

        val returnedToMain =
            initial
                .selectTool(MapTool.MAPS)
                .showFeatureSettings()
                .onMapBack()

        assertEquals(initial.source, returnedToMain.source)
        assertEquals(initial.contentVisibility, returnedToMain.contentVisibility)
        assertEquals(MapToolContentMode.MAIN, returnedToMain.toolPanel.contentMode)
    }

    @Test
    fun mapModeToggleAndRecenterKeepOrientationAndFollowAsSeparateState() {
        val northUp = PhoneMapMode()
        val headingUp = northUp.toggleOrientation()
        val northUpAgain = headingUp.toggleOrientation()
        val detachedNorthUp = northUp.detachFromLocation()
        val detachedHeadingUp = headingUp.detachFromLocation()

        assertEquals(PhoneMapOrientation.NORTH_UP, northUp.orientation)
        assertEquals(PhoneMapFollowMode.FOLLOW_LOCATION, northUp.follow)
        assertEquals(PhoneMapOrientation.HEADING_UP, headingUp.orientation)
        assertEquals(PhoneMapFollowMode.FOLLOW_LOCATION, headingUp.follow)
        assertEquals(PhoneMapOrientation.NORTH_UP, northUpAgain.orientation)
        assertTrue(detachedNorthUp.isDetachedFromLocation)
        assertEquals(PhoneMapOrientation.NORTH_UP, detachedNorthUp.orientation)
        assertTrue(detachedHeadingUp.isDetachedFromLocation)
        assertEquals(PhoneMapOrientation.HEADING_UP, detachedHeadingUp.orientation)
        assertEquals(northUp, detachedNorthUp.recenterOnLocation())
        assertEquals(headingUp, detachedHeadingUp.recenterOnLocation())
        assertFalse(detachedHeadingUp.recenterOnLocation().isDetachedFromLocation)
    }

    @Test
    fun zoomCommandsAreRendererNeutralAndConsumedOnce() {
        val requested = PhoneMapUiState().requestZoom(1)
        val command = requireNotNull(requested.cameraCommand)

        assertEquals(1L, command.id)
        assertEquals(1, command.zoomDelta)
        assertNull(requested.consumeCommand(command.id).cameraCommand)
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
                .onMapBack()

        assertEquals(initial.source, changed.source)
        assertEquals(initial.contentVisibility, changed.contentVisibility)
        assertEquals(MapToolPanelMode.CLOSED, changed.toolPanel.mode)
    }

    @Test
    fun panelTransitionsAndToolSwitchesLeaveRendererInputsUnchanged() {
        val initial =
            PhoneMapUiState(
                source = PhoneMapSource.Offline(PhoneOfflineMap(File("alps.map"))),
                contentVisibility = MapContentVisibility(gpxTracks = true, pois = false),
            ).requestZoom(1)

        val changed =
            initial
                .selectTool(MapTool.MAPS)
                .selectTool(MapTool.GPX)
                .expandTool()
                .collapseTool()
                .closeTool()

        assertEquals(initial.source, changed.source)
        assertEquals(initial.contentVisibility, changed.contentVisibility)
        assertEquals(initial.cameraCommand, changed.cameraCommand)
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
