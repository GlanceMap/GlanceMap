package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneOfflineMapsforgeOpacityUpdateTest {
    private val state =
        PhoneOfflineMapSurfaceState(
            map = PhoneOfflineMap(File("fixture.map"), contentRevision = "first"),
            themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
            initialCamera = defaultPhoneMapCamera,
            gpxOverlays =
                listOf(
                    PhoneMapGpxOverlay(
                        "recording",
                        "recording.gpx",
                        listOf(PhoneMapRouteSegment(listOf(GeoPoint(51.0, 0.0), GeoPoint(51.1, 0.1)))),
                        renderRevision = 1L,
                    ),
                ),
            pois = emptyList(),
            mapMode = PhoneMapMode(),
            cameraCommand = null,
            location = PhoneMapLocation(51.0, 0.0, 10f, 1L),
            hasLocationPermission = true,
        )

    @Test
    fun opacityCanMoveAcrossTheWholeRangeWithoutUpdatingSemanticContent() {
        var previous = state
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 0.75f, 0.5f, 0.25f, 0f).forEach { opacity ->
            val next = state.copy(baseLayerOpacity = opacity)
            assertTrue(next.isOpacityOnlyChangeFrom(previous))
            previous = next
        }
        assertFalse(state.isOpacityOnlyChangeFrom(null))
        assertFalse(state.isOpacityOnlyChangeFrom(state.copy()))
    }

    @Test
    fun sourceAndThemeChangesMustRunTheFullUpdateEvenWhileDraggingOpacity() {
        val faded = state.copy(baseLayerOpacity = 0.5f)
        listOf(
            faded.copy(map = state.map.copy(contentRevision = "replacement")),
            faded.copy(themeConfig = state.themeConfig.copy(styleId = "another-style")),
            faded.copy(mapSettings = state.mapSettings.copy(markerAnchor = PhoneMapMarkerAnchor.LOWER)),
            faded.copy(terrainDataVersion = 1L, hasTerrainData = true),
        ).forEach { assertFalse(it.isOpacityOnlyChangeFrom(state)) }
    }

    @Test
    fun cameraAndHeadingUpdatesMustNotBeDroppedDuringAnOpacityChange() {
        val faded = state.copy(baseLayerOpacity = 0.5f)
        listOf(
            faded.copy(initialCamera = state.initialCamera.copy(zoom = 16.0)),
            faded.copy(cameraOverride = state.initialCamera.copy(longitude = 0.2)),
            faded.copy(cameraCommand = PhoneMapCameraCommand(id = 1L, zoomDelta = 1)),
            faded.copy(compassPresentation = phoneMapCompassPresentation(PhoneMapOrientation.HEADING_UP, 90f)),
            faded.copy(gestureMapMode = PhoneMapMode(orientation = PhoneMapOrientation.HEADING_UP)),
        ).forEach { assertFalse(it.isOpacityOnlyChangeFrom(state)) }
    }

    @Test
    fun locationAndLiveRecordingUpdatesMustNotBeDroppedDuringAnOpacityChange() {
        val faded = state.copy(baseLayerOpacity = 0.5f)
        listOf(
            faded.copy(location = state.location?.copy(latitude = 51.001, fixElapsedRealtimeMillis = 2L)),
            faded.copy(hasLocationPermission = false, location = null),
            faded.copy(gpxOverlays = state.gpxOverlays.map { it.copy(renderRevision = 2L) }),
        ).forEach { assertFalse(it.isOpacityOnlyChangeFrom(state)) }
    }

    @Test
    fun ownershipChangesAtSliderEndpointsStillUpdateInteractionAndOverlays() {
        listOf(0f, 1f).forEach { opacity ->
            val passive =
                state.copy(
                    baseLayerOpacity = opacity,
                    isInteractive = false,
                    measurementEnabled = false,
                    gpxOverlays = emptyList(),
                    location = null,
                    hasLocationPermission = false,
                )
            assertFalse(passive.isOpacityOnlyChangeFrom(state.copy(baseLayerOpacity = 0.5f)))
            assertFalse(state.copy(baseLayerOpacity = 0.5f).isOpacityOnlyChangeFrom(passive))
        }
    }
}
