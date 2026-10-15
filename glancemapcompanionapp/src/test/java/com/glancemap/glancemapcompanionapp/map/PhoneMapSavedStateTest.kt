package com.glancemap.glancemapcompanionapp.map

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.glancemap.trailcore.map.MapContentVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneMapSavedStateTest {
    private val offline = PhoneOfflineMap(File("/map-rotation-test/alps.map"), contentRevision = "revision-a")
    private val scope =
        object : SaverScope {
            override fun canBeSaved(
                value: Any,
            ): Boolean = value is String || value is Boolean || value is Double || value is Float
        }

    @Test
    fun libraryAndHikePanelsSurviveRecreationInSplitAndFullScreen() {
        listOf(MapTool.LIBRARY, MapTool.HIKE).forEach { tool ->
            val split = PhoneMapUiState().selectTool(tool)
            listOf(split, split.expandTool()).forEach { state ->
                assertEquals(state, phoneMapUiStateSaver.restore(save(phoneMapUiStateSaver, state)))
            }
        }
    }

    @Test
    fun allComparisonCombinationsKeepPresentationAtEveryTransparency() {
        val combinations =
            listOf(
                PhoneMapSource.Online to PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
                PhoneMapSource.Online to PhoneMapComparisonLayer.Offline(offline),
                PhoneMapSource.Offline(offline) to PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
            )
        combinations.forEach { (source, layer) ->
            listOf(0f, 25f, 50f, 75f, 100f).forEach { transparency ->
                val state =
                    PhoneMapUiState(
                        source = source,
                        comparison = PhoneMapComparisonState(layer, transparency),
                        contentVisibility = MapContentVisibility(gpxTracks = false, routes = false, pois = false),
                        toolPanel =
                            MapToolPanelState(
                                activeTool = MapTool.MAPS,
                                mode = MapToolPanelMode.EXPANDED,
                                contentMode = MapToolContentMode.FEATURE_SETTINGS,
                                featureSettingsSection = MapToolFeatureSettingsSection.MAP_ZOOM,
                            ),
                        toolLauncherExpanded = true,
                        mapMode = PhoneMapMode(PhoneMapOrientation.HEADING_UP, PhoneMapFollowMode.FREE, 123f),
                    )

                assertEquals(state, phoneMapUiStateSaver.restore(save(phoneMapUiStateSaver, state)))
            }
        }
    }

    @Test
    fun pendingCommandsAndValidationRequestsAreNotReplayed() {
        val state =
            PhoneMapUiState()
                .selectComparisonLayer(PhoneMapComparisonLayer.Offline(offline))
                .nextComparisonSelectionRequest()
                .requestZoom(1)
        val saved = save(phoneMapUiStateSaver, state)
        val restored = requireNotNull(phoneMapUiStateSaver.restore(saved))

        assertEquals(state.comparison.layer, restored.comparison.layer)
        assertNull(restored.cameraCommand)
        assertEquals(0L, restored.comparison.selectionGeneration)
        assertEquals(0L, restored.comparison.validationRequestGeneration)
        assertTrue((saved as List<*>).all { it == null || scope.canBeSaved(it) })
    }

    @Test
    fun cameraRetainsFractionalZoomBearingPhysicalResolutionAndAuthority() {
        listOf(null, 2.75).forEach { resolution ->
            val state =
                PhoneMapCameraState(
                    PhoneMapCameraSnapshot(46.5, 7.5, 12.75, 123f, resolution),
                    PhoneMapCameraRenderer.COMPARISON,
                )

            assertEquals(state, phoneMapCameraStateSaver.restore(save(phoneMapCameraStateSaver, state)))
        }
        val defaultCamera = PhoneMapCameraState(defaultPhoneMapCamera)
        val saved = (save(phoneMapCameraStateSaver, defaultCamera) as List<*>).toMutableList()
        saved[saved.indexOf("latitude") + 1] = Double.NaN
        assertNull(phoneMapCameraStateSaver.restore(saved))
    }

    @Test
    fun movedMeasurementEndpointsSurviveAndClearedMeasurementsStayCleared() {
        val original = PhoneMapDistanceMeasurement(PhoneMapCoordinate(46.0, 7.0), PhoneMapCoordinate(46.2, 7.3))
        val moved = original.moveEndpoint(1, PhoneMapCoordinate(46.5, 7.5))
        val saved = save(phoneMapDistanceMeasurementSaver, moved)
        val restored = requireNotNull(phoneMapDistanceMeasurementSaver.restore(saved))

        assertEquals(moved, restored)
        assertEquals(moved.distanceMeters, restored.distanceMeters, 0.001)
        assertNull(with(phoneMapDistanceMeasurementSaver) { scope.save(null) })
    }

    @Test
    fun restoredOfflineComparisonWaitsForDiscoveryThenRefreshesOrClears() {
        val selected =
            PhoneMapUiState(comparison = PhoneMapComparisonState(PhoneMapComparisonLayer.Offline(offline), 75f))
        val restored = requireNotNull(phoneMapUiStateSaver.restore(save(phoneMapUiStateSaver, selected)))
        val replacement = offline.copy(contentRevision = "revision-b")

        assertSame(restored, restored.refreshOfflineMapSelections(null))
        val refreshed = restored.refreshOfflineMapSelections(listOf(replacement))
        assertEquals(PhoneMapComparisonLayer.Offline(replacement), refreshed.comparison.layer)
        assertEquals(75f, refreshed.comparison.transparencyPercent)
        assertNull(refreshed.refreshOfflineMapSelections(emptyList()).comparison.layer)
    }

    @Test
    fun restoredOfflineBaseRebindsTheCurrentRevisionAndKeepsOnlineComparison() {
        val selected =
            PhoneMapUiState(
                source = PhoneMapSource.Offline(offline),
                comparison =
                    PhoneMapComparisonState(
                        layer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
                    ),
            )
        val restored = requireNotNull(phoneMapUiStateSaver.restore(save(phoneMapUiStateSaver, selected)))
        val replacement = offline.copy(contentRevision = "revision-b")
        val refreshed = restored.refreshOfflineMapSelections(listOf(replacement))

        assertEquals(PhoneMapSource.Offline(replacement), refreshed.source)
        assertEquals(selected.comparison.layer, refreshed.comparison.layer)
    }

    @Test
    fun removedOnlineProviderDoesNotRestoreAComparisonOrChangeTheBase() {
        val state =
            PhoneMapUiState(
                source = PhoneMapSource.Offline(offline),
                comparison =
                    PhoneMapComparisonState(
                        layer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
                    ),
            )
        val saved = (save(phoneMapUiStateSaver, state) as List<*>).toMutableList()
        saved[saved.indexOf("comparisonOnline") + 1] = "removed-provider"
        val restored = requireNotNull(phoneMapUiStateSaver.restore(saved))

        assertEquals(state.source, restored.source)
        assertNull(restored.comparison.layer)
    }

    @Test
    fun panelSizingSurvivesRestorationAndOldSnapshotsDefaultSafely() {
        val custom = MapToolPanelState().select(MapTool.MAPS).resize(0.48f)
        listOf(custom, custom.expand(), custom.expand().cycleSize(), custom.close()).forEach { panel ->
            val state = PhoneMapUiState(toolPanel = panel)
            assertEquals(state, phoneMapUiStateSaver.restore(save(phoneMapUiStateSaver, state)))
        }
        val saved = save(phoneMapUiStateSaver, PhoneMapUiState(toolPanel = custom)) as List<*>
        val oldSnapshot =
            saved
                .chunked(2)
                .filterNot { it[0] in listOf("panelFraction", "panelCustomFraction") }
                .flatten()
        val restored = requireNotNull(phoneMapUiStateSaver.restore(oldSnapshot))
        assertEquals(DEFAULT_MAP_TOOL_PANEL_FRACTION, restored.toolPanel.splitFraction)
        assertNull(restored.toolPanel.customSplitFraction)
        assertEquals(MapTool.MAPS, restored.toolPanel.activeTool)
    }

    @Test
    fun restoredDraggedSizeIsSharedAcrossHikeLayersAndLibrary() {
        val custom = MapToolPanelState().select(MapTool.HIKE).resize(0.61f)
        val state = PhoneMapUiState(toolPanel = custom)
        val restored = requireNotNull(phoneMapUiStateSaver.restore(save(phoneMapUiStateSaver, state)))
        val switched = restored.selectTool(MapTool.LAYER).selectTool(MapTool.LIBRARY).selectTool(MapTool.HIKE)
        assertEquals(custom, switched.toolPanel)
    }

    private fun <T> save(
        saver: Saver<T, Any>,
        state: T,
    ): Any = requireNotNull(with(saver) { scope.save(state) })
}
