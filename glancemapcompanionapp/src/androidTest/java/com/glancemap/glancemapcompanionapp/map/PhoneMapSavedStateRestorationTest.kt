package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneMapSavedStateRestorationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composeRestorationKeepsAllThreeComparisonModesCameraAndMeasurement() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var ui: MutableState<PhoneMapUiState>
        lateinit var camera: MutableState<PhoneMapCameraState>
        lateinit var measurement: MutableState<PhoneMapDistanceMeasurement?>
        lateinit var fittedGpx: MutableState<Boolean>
        lateinit var centerInitialLocation: MutableState<Boolean>
        restoration.setContent {
            ui = rememberSaveable(stateSaver = phoneMapUiStateSaver) { mutableStateOf(PhoneMapUiState()) }
            camera =
                rememberSaveable(stateSaver = phoneMapCameraStateSaver) {
                    mutableStateOf(PhoneMapCameraState(defaultPhoneMapCamera))
                }
            measurement =
                rememberSaveable(stateSaver = phoneMapDistanceMeasurementSaver) {
                    mutableStateOf<PhoneMapDistanceMeasurement?>(null)
                }
            fittedGpx = rememberSaveable { mutableStateOf(false) }
            centerInitialLocation = rememberSaveable { mutableStateOf(true) }
        }
        val offline = PhoneOfflineMap(File(composeRule.activity.cacheDir, "restoration.map"), contentRevision = "fixture")
        val combinations =
            listOf(
                PhoneMapSource.Online to PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
                PhoneMapSource.Online to PhoneMapComparisonLayer.Offline(offline),
                PhoneMapSource.Offline(offline) to PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
            )
        val expectedCamera =
            PhoneMapCameraState(
                PhoneMapCameraSnapshot(46.5, 7.5, 12.75, 123f, 2.75),
                PhoneMapCameraRenderer.COMPARISON,
            )
        val expectedMeasurement =
            PhoneMapDistanceMeasurement(PhoneMapCoordinate(46.0, 7.0), PhoneMapCoordinate(46.5, 7.5))
        combinations.forEach { (source, layer) ->
            listOf(0f, 25f, 50f, 75f, 100f).forEach { transparency ->
                val expectedUi =
                    PhoneMapUiState(
                        source = source,
                        comparison = PhoneMapComparisonState(layer, transparency),
                        toolPanel = MapToolPanelState(MapTool.LAYER, MapToolPanelMode.EXPANDED),
                        mapMode = PhoneMapMode(PhoneMapOrientation.HEADING_UP, PhoneMapFollowMode.FREE, 123f),
                    )
                val previousUi = ui
                composeRule.runOnIdle {
                    ui.value = expectedUi
                    camera.value = expectedCamera
                    measurement.value = expectedMeasurement
                    fittedGpx.value = true
                    centerInitialLocation.value = false
                }

                restoration.emulateSavedInstanceStateRestore()

                composeRule.runOnIdle {
                    assertNotSame(previousUi, ui)
                    assertEquals(expectedUi, ui.value)
                    assertEquals(expectedCamera, camera.value)
                    assertEquals(expectedMeasurement, measurement.value)
                    assertTrue(fittedGpx.value)
                    assertFalse(centerInitialLocation.value)
                }
            }
        }
    }

    @Test
    fun routeInspectionRestoresAAndBSelectingPhaseAndSummaryIdWithoutSavingTrackGeometry() {
        lateinit var selection: MutableState<PhoneMapRouteAnalysisSavedSelection?>
        lateinit var selectingB: MutableState<Boolean>
        lateinit var summaryId: MutableState<String?>
        val content: @Composable () -> Unit = {
            selection = rememberSaveable(stateSaver = phoneMapRouteAnalysisSelectionSaver) { mutableStateOf(null) }
            selectingB = rememberSaveable { mutableStateOf(false) }
            summaryId = rememberSaveable { mutableStateOf(null) }
        }
        composeRule.setContent(content)
        val item =
            PhoneMapGpxItem(
                "fixture",
                "Fixture",
                PhoneMapGpxTrack(
                    "fixture",
                    listOf(TrailPoint(GeoPoint(46.0, 7.0), 10.0), TrailPoint(GeoPoint(46.01, 7.01), 100.0)),
                ),
                enabled = true,
            )
        val pointA =
            PhoneMapRouteAnalysisHit(
                item,
                PhoneMapRouteAnalysisPosition(0, 0.25),
                PhoneMapCoordinate(46.0025, 7.0025),
                0.0,
            )
        val pointB =
            pointA.copy(
                position = PhoneMapRouteAnalysisPosition(0, 0.75),
                coordinate = PhoneMapCoordinate(46.0075, 7.0075),
            )
        listOf(null, pointB).forEach { second ->
            val expected =
                PhoneMapRouteAnalysisRequestResult(
                    buildPhoneMapRouteAnalysis(pointA, second, PhoneMapGpxSettings(), PhoneGeneralSettings()),
                    PhoneMapRouteAnalysisTrackRevision(item.id, item.displayName, item.track),
                )
            composeRule.runOnIdle {
                selection.value = expected.savedSelection()
                selectingB.value = second == null
                summaryId.value = item.id
            }

            composeRule.activityRule.scenario.recreate()
            composeRule.activityRule.scenario.onActivity { activity -> activity.setContent(content = content) }

            composeRule.runOnIdle {
                val restored =
                    restorePhoneMapRouteAnalysis(
                        requireNotNull(selection.value),
                        listOf(item.copy(track = item.track.copy(points = item.track.points.toList()))),
                        PhoneMapGpxSettings(),
                        PhoneGeneralSettings(),
                    )
                assertEquals(expected.analysis, restored?.analysis)
                assertEquals(second == null, selectingB.value)
                assertEquals(item.id, summaryId.value)
            }
        }
    }
}
