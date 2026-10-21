package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneOfflineComparisonUpdateTest {
    private val state =
        PhoneOfflineMapSurfaceState(
            map = PhoneOfflineMap(File("fixture.map"), contentRevision = "first"),
            themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
            initialCamera = defaultPhoneMapCamera,
            gpxOverlays = emptyList(),
            pois = emptyList(),
            mapMode = PhoneMapMode(follow = PhoneMapFollowMode.FREE),
            cameraCommand = null,
            location = PhoneMapLocation(0.0, 0.0, 10f, 1L),
            hasLocationPermission = true,
        )

    @Test
    fun panningZoomingAndRotatingComparisonDoNotRedrawAnUnchangedLocationLayer() {
        val camera = state.initialCamera.copy(longitude = 0.1, zoom = 15.5, bearingDegrees = 90f)
        val next =
            state.copy(
                initialCamera = camera,
                cameraOverride = camera,
                mapMode = state.mapMode.withManualBearing(90f),
                compassPresentation = phoneMapCompassPresentation(PhoneMapOrientation.HEADING_UP, 90f),
            )
        assertFalse(next.needsLocationUpdateFrom(state))
        assertFalse(state.needsLocationUpdateFrom(state))
    }

    @Test
    fun transparencyAndThemeChangesDoNotReapplyTheSameLocationMarker() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { opacity ->
            val next =
                state.copy(baseLayerOpacity = opacity, themeConfig = state.themeConfig.copy(styleId = "another-style"))
            assertFalse(next.needsLocationUpdateFrom(state))
        }
    }

    @Test
    fun freshFixesTrustStyleFollowAndOwnershipChangesStillUpdateLocation() {
        assertTrue(state.needsLocationUpdateFrom(null))
        listOf(
            state.copy(location = state.location?.copy(fixElapsedRealtimeMillis = 2L)),
            state.copy(location = state.location?.copy(latitude = 0.001)),
            state.copy(location = null, hasLocationPermission = false),
            state.copy(hasLocationPermission = false),
            state.copy(locationMarkerFresh = true),
            state.copy(compassConeTrustworthy = true),
            state.copy(mapSettings = state.mapSettings.copy(markerStyle = PhoneMapMarkerStyle.TRIANGLE)),
            state.copy(mapMode = state.mapMode.recenterOnLocation()),
            state.copy(gestureMapMode = state.gestureMapMode.recenterOnLocation()),
            state.copy(map = state.map.copy(contentRevision = "replacement")),
        ).forEach { assertTrue(it.needsLocationUpdateFrom(state)) }
    }

    @Test
    fun immediateLocationOwnershipChangeSupersedesTheQueuedGestureUpdate() {
        val queue = PhoneMapLayerMutationQueue()
        val executed = mutableListOf<String>()
        queue.setGestureActive(true)
        assertFalse(queue.submit("location") { executed += "stale-location" })
        queue.setGestureActive(false)
        val latest = { executed += "current-owner" }
        if (queue.submit("location", latest)) latest()
        queue.drainAfterGestureIdle().forEach { it.invoke() }
        assertEquals(listOf("current-owner"), executed)
        assertFalse(queue.hasPendingMutations())
    }

    @Test
    fun supersedingLocationDoesNotDropOtherPendingOverlayWork() {
        val queue = PhoneMapLayerMutationQueue()
        val executed = mutableListOf<String>()
        queue.setGestureActive(true)
        assertFalse(queue.submit("location") { executed += "stale-location" })
        assertFalse(queue.submit("gpx") { executed += "gpx" })
        queue.setGestureActive(false)
        val latest = { executed += "current-owner" }
        if (queue.submit("location", latest)) latest()
        queue.drainAfterGestureIdle().forEach { it.invoke() }
        assertEquals(listOf("current-owner", "gpx"), executed)
    }

    @Test
    fun revertingAQueuedLocationUpdateMustReplaceItWhileCameraKeepsMoving() {
        val queued = state.copy(location = state.location?.copy(latitude = 0.001))
        val reverted = state.copy(cameraOverride = state.initialCamera.copy(longitude = 0.1))
        assertTrue(reverted.needsLocationUpdateFrom(queued))
        assertFalse(reverted.needsLocationUpdateFrom(state))
    }
}
