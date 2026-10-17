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
    fun libraryAndHikeOpenAtRememberedSplitSizeAndBackCollapseThenCloseThem() {
        val previousPanels =
            listOf(
                MapToolPanelState(),
                MapToolPanelState().select(MapTool.GPX).expand(),
                MapToolPanelState().select(MapTool.MAPS).resize(0.8f).showFeatureSettings(),
                MapToolPanelState().select(MapTool.LAYER).resize(1f).close(),
            )
        listOf(MapTool.LIBRARY, MapTool.HIKE).forEach { tool ->
            previousPanels.forEach { panel ->
                val before = PhoneMapUiState(toolPanel = panel)
                val opened = before.selectTool(tool)
                assertEquals(tool, opened.toolPanel.activeTool)
                assertEquals(MapToolPanelMode.SPLIT, opened.toolPanel.mode)
                assertEquals(panel.splitFraction, opened.toolPanel.splitFraction)
                assertEquals(panel.customSplitFraction, opened.toolPanel.customSplitFraction)
                assertEquals(MapToolContentMode.MAIN, opened.toolPanel.contentMode)
                assertEquals(before.source, opened.source)
                assertEquals(before.comparison, opened.comparison)
                assertEquals(before.contentVisibility, opened.contentVisibility)
                assertEquals(opened, opened.showFeatureSettings())
                assertEquals(MapToolPanelMode.EXPANDED, opened.expandTool().toolPanel.mode)
                assertEquals(opened, opened.expandTool().onMapBack())
                assertEquals(MapToolPanelMode.CLOSED, opened.onMapBack().toolPanel.mode)
                assertEquals(MapToolPanelMode.CLOSED, opened.selectTool(tool).toolPanel.mode)
            }
        }
    }

    @Test
    fun contentSettingsOpenFullScreenWithoutChangingMapContentAndOpeningAgainDoesNotCloseThem() {
        val before = PhoneMapUiState().selectTool(MapTool.LAYER).toggleToolLauncher()
        listOf(MapTool.POI, MapTool.GPX, MapTool.MAPS).forEach { tool ->
            val opened = before.openToolPage(tool, featureSettings = true)
            assertEquals(MapToolPanelMode.EXPANDED, opened.toolPanel.mode)
            assertEquals(MapToolContentMode.FEATURE_SETTINGS, opened.toolPanel.contentMode)
            assertEquals(MapToolFeatureSettingsSection.ROOT, opened.toolPanel.featureSettingsSection)
            assertEquals(tool, opened.toolPanel.activeTool)
            assertFalse(opened.toolLauncherExpanded)
            assertEquals(before.source, opened.source)
            assertEquals(before.comparison, opened.comparison)
            assertEquals(before.contentVisibility, opened.contentVisibility)
            assertEquals(opened, opened.openToolPage(tool, featureSettings = true))
        }
        val general = before.openToolPage(MapTool.SETTINGS, featureSettings = false)
        assertEquals(MapToolPanelMode.EXPANDED, general.toolPanel.mode)
        assertEquals(MapToolContentMode.MAIN, general.toolPanel.contentMode)
    }

    @Test
    fun gpxToolsOpenAsASharedPanelWithRememberedSizeAndNormalBackBehavior() {
        val before =
            PhoneMapUiState(
                toolPanel = MapToolPanelState().select(MapTool.LAYER).resize(0.61f).expand(),
            )
        val opened = before.openToolPage(MapTool.GPX_TOOLS, featureSettings = false)
        assertEquals(MapTool.GPX_TOOLS, opened.toolPanel.activeTool)
        assertEquals(MapToolPanelMode.SPLIT, opened.toolPanel.mode)
        assertEquals(0.61f, opened.toolPanel.splitFraction, 0f)
        assertEquals(0.61f, opened.toolPanel.customSplitFraction)
        assertEquals(before.source, opened.source)
        assertEquals(before.contentVisibility, opened.contentVisibility)
        assertEquals(opened, opened.expandTool().onMapBack())
        assertEquals(MapToolPanelMode.CLOSED, opened.onMapBack().toolPanel.mode)
    }

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
    }

    @Test
    fun selectingTheActiveToolClosesThePanelFromEitherOpenMode() {
        val split = MapToolPanelState().select(MapTool.GPX)
        val expanded = split.expand()

        assertEquals(MapToolPanelState(), split.select(MapTool.GPX))
        assertEquals(MapToolPanelState(), expanded.select(MapTool.GPX))
    }

    @Test
    fun libraryShortcutsKeepExistingContentPanels() {
        assertEquals(listOf(MapTool.POI, MapTool.GPX, MapTool.MAPS), libraryMapTools)
    }

    @Test
    fun backDismissesLauncherWithoutChangingTheUnderlyingPanelOrMap() {
        val state = PhoneMapUiState().selectTool(MapTool.LAYER).expandTool().toggleToolLauncher()
        val dismissed = state.onMapBack()

        assertFalse(dismissed.toolLauncherExpanded)
        assertEquals(state.toolPanel, dismissed.toolPanel)
        assertEquals(state.source, dismissed.source)
        assertEquals(state.contentVisibility, dismissed.contentVisibility)
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
        assertFalse(libraryMapTools.contains(selected.toolPanel.activeTool))

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
    fun explicitExpandAndCollapsePreserveTheSplitMode() {
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
    fun featureSettingsSectionsReturnToRootBeforeTheToolPanel() {
        val root = MapToolPanelState().select(MapTool.MAPS).showFeatureSettings()
        val display = root.showFeatureSettingsSection(MapToolFeatureSettingsSection.MAP_DISPLAY)

        assertEquals(MapToolFeatureSettingsSection.MAP_DISPLAY, display.featureSettingsSection)
        assertEquals(MapToolFeatureSettingsSection.ROOT, display.back().featureSettingsSection)
        assertEquals(MapToolContentMode.MAIN, display.back().back().contentMode)
    }

    @Test
    fun onlineMapSettingsOpenFromMapsAndReturnToTheSettingsRoot() {
        val root = MapToolPanelState().select(MapTool.MAPS).showFeatureSettings()
        val online = root.showFeatureSettingsSection(MapToolFeatureSettingsSection.MAP_ONLINE)

        assertEquals(MapToolFeatureSettingsSection.MAP_ONLINE, online.featureSettingsSection)
        assertEquals(root, online.back())
    }

    @Test
    fun featureSettingsRejectSectionsBelongingToAnotherTool() {
        val poiSettings = MapToolPanelState().select(MapTool.POI).showFeatureSettings()

        assertEquals(
            poiSettings,
            poiSettings.showFeatureSettingsSection(MapToolFeatureSettingsSection.MAP_ZOOM),
        )
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
    fun generalSensorSettingsUseTheSameSubpageBackStackAsFeatureSettings() {
        val root = MapToolPanelState().select(MapTool.SETTINGS)
        val sensors = root.showFeatureSettingsSection(MapToolFeatureSettingsSection.GENERAL_SENSORS)

        assertEquals(MapToolContentMode.FEATURE_SETTINGS, sensors.contentMode)
        assertEquals(MapToolFeatureSettingsSection.GENERAL_SENSORS, sensors.featureSettingsSection)
        assertEquals(MapToolFeatureSettingsSection.ROOT, sensors.back().featureSettingsSection)
        assertEquals(MapToolContentMode.MAIN, sensors.back().back().contentMode)
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
    fun searchTargetsDetachFollowAndOldCompletionCannotConsumeNewerCommands() {
        val target = PhoneMapCoordinate(48.85, 2.35)
        val first = PhoneMapUiState().requestZoom(1)
        val searched = first.requestSearchTarget(target)
        val command = requireNotNull(searched.cameraCommand)
        assertEquals(target, command.target)
        assertEquals(0, command.zoomDelta)
        assertEquals(2L, command.id)
        assertTrue(searched.mapMode.isDetachedFromLocation)
        assertEquals(command, searched.consumeCommand(1L).cameraCommand)
        assertNull(searched.consumeCommand(command.id).cameraCommand)
        assertEquals(1, requireNotNull(searched.requestZoom(1).cameraCommand).zoomDelta)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSearchCoordinatesCannotReachARenderer() {
        PhoneMapUiState().requestSearchTarget(PhoneMapCoordinate(Double.NaN, 2.35))
    }

    @Test
    fun zoomCommandIdsRemainMonotonicAfterConsumption() {
        val first = PhoneMapUiState().requestZoom(1)
        val second = first.consumeCommand(requireNotNull(first.cameraCommand).id).requestZoom(-1)

        assertEquals(1L, requireNotNull(first.cameraCommand).id)
        assertEquals(2L, requireNotNull(second.cameraCommand).id)
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

    @Test
    fun comparisonAcceptsInstalledOfflineMapsAboveEitherBase() {
        val offlineMap = PhoneOfflineMap(File("alps.map"))
        val onlineWithOfflineLayer =
            PhoneMapUiState()
                .selectComparisonLayer(PhoneMapComparisonLayer.Offline(offlineMap))

        assertTrue(onlineWithOfflineLayer.comparison.isAvailableFor(PhoneMapSource.Online, listOf(offlineMap)))
        assertTrue(
            onlineWithOfflineLayer.comparison.isAvailableFor(
                PhoneMapSource.Offline(offlineMap),
                listOf(offlineMap),
            ),
        )
        assertEquals(
            PhoneMapComparisonLayer.Offline(offlineMap),
            onlineWithOfflineLayer
                .copy(source = PhoneMapSource.Offline(offlineMap))
                .clearUnavailableComparison(listOf(offlineMap))
                .comparison.layer,
        )
    }

    @Test
    fun onlineComparisonSourceCanRemainSelectedAcrossRendererModes() {
        val offlineMap = PhoneOfflineMap(File("alps.map"))
        val onlineLayer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP)
        val state = PhoneMapUiState().selectComparisonLayer(onlineLayer)

        assertTrue(state.comparison.isAvailableFor(PhoneMapSource.Online, listOf(offlineMap)))
        assertTrue(state.comparison.isAvailableFor(PhoneMapSource.Offline(offlineMap), listOf(offlineMap)))
    }

    @Test
    fun comparisonOptionsExcludeTheActiveOnlineSourceByIdentity() {
        val options =
            comparisonLayerOptions(
                base = PhoneMapSource.Online,
                baseOnlineSource = PhoneOnlineMapSource.CYCLOSM,
                offlineMaps = emptyList(),
                onlineSources =
                    listOf(
                        PhoneOnlineMapSource.CYCLOSM,
                        PhoneOnlineMapSource.TRACESTRACK_TOPO,
                        PhoneOnlineMapSource.SATELLITE,
                    ),
            )

        assertFalse(options.contains(PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.CYCLOSM)))
        assertTrue(options.contains(PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.TRACESTRACK_TOPO)))
        assertTrue(options.contains(PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.SATELLITE)))
    }
}
