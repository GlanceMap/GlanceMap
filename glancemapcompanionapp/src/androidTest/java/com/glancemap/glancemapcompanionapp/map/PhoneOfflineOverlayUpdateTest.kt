package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.poi.PoiType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PhoneOfflineOverlayUpdateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var mapView: MapView
    private lateinit var overlays: PhoneOfflineMapsforgeOverlayLayers
    private val showMap = mutableStateOf(true)
    private val track =
        PhoneMapGpxOverlay(
            "fixture",
            "fixture.gpx",
            listOf(PhoneMapRouteSegment(listOf(GeoPoint(51.0, 0.0), GeoPoint(51.1, 0.1)))),
        )
    private val state =
        PhoneOfflineMapSurfaceState(
            map = PhoneOfflineMap(File("fixture.map"), contentRevision = "fixture"),
            themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
            initialCamera = defaultPhoneMapCamera,
            gpxOverlays = listOf(track),
            pois = listOf(PhoneMapPoi("poi", "fixture", GeoPoint(51.0, 0.0), PoiType.WATER, null, null)),
            mapMode = PhoneMapMode(),
            cameraCommand = null,
        )

    @After
    fun releaseMap() {
        if (::mapView.isInitialized) {
            composeRule.runOnIdle { showMap.value = false }
            composeRule.waitForIdle()
        }
    }

    @Test
    fun locationAndMeasurementUpdatesReuseGpxPaintAndPoiMarkers() {
        setContent()
        composeRule.runOnIdle {
            overlays.update(state)
            val line =
                mapView.layerManager.layers
                    .filterIsInstance<Polyline>()
                    .single()
            val paint = line.paintStroke
            val marker =
                mapView.layerManager.layers
                    .filterIsInstance<Marker>()
                    .single()
            (1..30).forEach { index ->
                overlays.update(state.copy(location = PhoneMapLocation(51.0, 0.0, 10f, index.toLong())))
            }
            overlays.update(
                state.copy(
                    distanceMeasurement =
                        PhoneMapDistanceMeasurement(PhoneMapCoordinate(51.0, 0.0), PhoneMapCoordinate(51.2, 0.2)),
                ),
            )
            assertSame(paint, line.paintStroke)
            assertSame(
                marker,
                mapView.layerManager.layers
                    .filterIsInstance<Marker>()
                    .single(),
            )
            assertEquals(
                2,
                mapView.layerManager.layers
                    .filterIsInstance<Polyline>()
                    .size,
            )

            val updatedWidth = state.gpxSettings.trackWidth + 2f
            overlays.update(state.copy(gpxSettings = state.gpxSettings.copy(trackWidth = updatedWidth)))
            assertNotSame(paint, line.paintStroke)
            assertEquals(updatedWidth, line.paintStroke.strokeWidth, 0f)
            assertEquals(
                1,
                mapView.layerManager.layers
                    .filterIsInstance<Polyline>()
                    .size,
            )
        }
    }

    @Test
    fun queuedGpxChangesSurviveLaterLocationUpdatesDuringAGesture() {
        setContent()
        val changed = state.copy(gpxOverlays = listOf(track.copy(segments = track.segments + track.segments)))
        composeRule.runOnIdle {
            overlays.update(state)
            PhoneMapLayerMutationCoordinator.setGestureActive(mapView, true)
            overlays.update(changed)
            overlays.update(changed.copy(location = PhoneMapLocation(51.0, 0.0, 10f, 1L)))
            assertEquals(
                1,
                mapView.layerManager.layers
                    .filterIsInstance<Polyline>()
                    .size,
            )
            PhoneMapLayerMutationCoordinator.setGestureActive(mapView, false)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            mapView.layerManager.layers
                .filterIsInstance<Polyline>()
                .size == 2
        }
    }

    @Test
    fun revertingToAppliedStateReplacesAQueuedChangeAndOwnershipClearRemovesOverlays() {
        setContent()
        val flushed = AtomicBoolean()
        composeRule.runOnIdle {
            overlays.update(state)
            PhoneMapLayerMutationCoordinator.setGestureActive(mapView, true)
            overlays.update(state.copy(gpxOverlays = emptyList(), pois = emptyList()))
            overlays.update(state)
            PhoneMapLayerMutationCoordinator.mutateLayers(mapView) { flushed.set(true) }
            PhoneMapLayerMutationCoordinator.setGestureActive(mapView, false)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { flushed.get() }
        composeRule.runOnIdle {
            assertEquals(
                1,
                mapView.layerManager.layers
                    .filterIsInstance<Polyline>()
                    .size,
            )
            assertEquals(
                1,
                mapView.layerManager.layers
                    .filterIsInstance<Marker>()
                    .size,
            )
            overlays.update(state.copy(gpxOverlays = emptyList(), pois = emptyList()))
            assertEquals(
                0,
                mapView.layerManager.layers
                    .filterIsInstance<Polyline>()
                    .size,
            )
            assertEquals(
                0,
                mapView.layerManager.layers
                    .filterIsInstance<Marker>()
                    .size,
            )
        }
    }

    private fun setContent() {
        composeRule.runOnUiThread { AndroidGraphicFactory.createInstance(composeRule.activity.application) }
        composeRule.setContent {
            if (showMap.value) {
                AndroidView(
                    factory = { context ->
                        MapView(context).also { view ->
                            mapView = view
                            overlays = PhoneOfflineMapsforgeOverlayLayers(view, baseLayer = { null }, onPoiSelected = {})
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        overlays.dispose()
                        view.destroyAll()
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }
}
