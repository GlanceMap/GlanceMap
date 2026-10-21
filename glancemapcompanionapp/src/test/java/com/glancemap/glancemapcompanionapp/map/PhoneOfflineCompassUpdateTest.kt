package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneOfflineCompassUpdateTest {
    private val state =
        PhoneOfflineMapSurfaceState(
            map = PhoneOfflineMap(File("fixture.map"), contentRevision = "first"),
            themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
            initialCamera = defaultPhoneMapCamera,
            gpxOverlays = emptyList(),
            pois = emptyList(),
            mapMode = PhoneMapMode(),
            cameraCommand = null,
            location = PhoneMapLocation(51.0, 0.0, 10f, 1L),
            hasLocationPermission = true,
        )

    @Test
    fun northUpMarkerAndHeadingUpMapUpdatesCanBypassSemanticRendererWork() {
        PhoneMapOrientation.entries.forEach { orientation ->
            var previous = state
            listOf(350f, 359f, 0f, 1f, 90f, 180f, 270f).forEach { heading ->
                val next = state.copy(compassPresentation = phoneMapCompassPresentation(orientation, heading))
                assertTrue(next.hasOnlyCompassChangesFrom(previous))
                previous = next
            }
        }
        assertFalse(state.hasOnlyCompassChangesFrom(null))
    }

    @Test
    fun observedBearingEchoDoesNotReapplyTheLocationOrMapSettings() {
        val turned = state.copy(compassPresentation = phoneMapCompassPresentation(PhoneMapOrientation.HEADING_UP, 90f))
        val echo = turned.copy(initialCamera = state.initialCamera.copy(bearingDegrees = 90f))
        assertTrue(echo.hasOnlyCompassChangesFrom(turned))
        assertTrue(echo.hasOnlyCompassChangesFrom(echo))
    }

    @Test
    fun headingCannotHideSourceCameraLocationTrustOrOwnershipChanges() {
        val heading = state.copy(compassPresentation = phoneMapCompassPresentation(PhoneMapOrientation.HEADING_UP, 90f))
        listOf(
            heading.copy(map = state.map.copy(contentRevision = "replacement")),
            heading.copy(themeConfig = state.themeConfig.copy(styleId = "another-style")),
            heading.copy(mapSettings = state.mapSettings.copy(markerAnchor = PhoneMapMarkerAnchor.LOWER)),
            heading.copy(terrainDataVersion = 1L, hasTerrainData = true),
            heading.copy(baseLayerOpacity = 0.5f),
            heading.copy(initialCamera = state.initialCamera.copy(zoom = 16.0)),
            heading.copy(cameraOverride = state.initialCamera.copy(bearingDegrees = 90f)),
            heading.copy(cameraCommand = PhoneMapCameraCommand(id = 1L, zoomDelta = 1)),
            heading.copy(location = state.location?.copy(latitude = 51.001, fixElapsedRealtimeMillis = 2L)),
            heading.copy(hasLocationPermission = false, location = null),
            heading.copy(locationMarkerFresh = true),
            heading.copy(compassConeTrustworthy = true),
            heading.copy(isInteractive = false),
            heading.copy(gestureMapMode = PhoneMapMode(orientation = PhoneMapOrientation.HEADING_UP)),
        ).forEach { assertFalse(it.hasOnlyCompassChangesFrom(state)) }
    }

    @Test
    fun queuedSemanticChangesAreComparedWithTheAppliedStateRatherThanTheLastHeadingSample() {
        val queued = state.copy(location = state.location?.copy(latitude = 51.001))
        val heading =
            queued.copy(compassPresentation = phoneMapCompassPresentation(PhoneMapOrientation.HEADING_UP, 90f))
        assertFalse(heading.hasOnlyCompassChangesFrom(state))
        assertTrue(heading.hasOnlyCompassChangesFrom(queued))
    }
}
