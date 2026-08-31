package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.BoundingBox

class PhoneOfflineMapRuntimeDiagnosticsTest {
    private val mapBounds = BoundingBox(47.0, 11.0, 48.0, 12.0)
    private val insideLocation = phoneMapLocation(latitude = 47.5, longitude = 11.5)
    private val outsideLocation = phoneMapLocation(latitude = 46.5, longitude = 11.5)

    @Test
    fun insideLocationInFollowModeAllowsRecentering() {
        val decision =
            phoneOfflineLocationFollowDecision(
                location = insideLocation,
                mapBounds = mapBounds,
                followMode = PhoneMapFollowMode.FOLLOW_LOCATION,
            )

        assertEquals(true, decision.locationInsideMapBounds)
        assertTrue(decision.shouldCenterOnLocation)
    }

    @Test
    fun outsideLocationNeverAllowsRecenteringOutsideTheMap() {
        val decision =
            phoneOfflineLocationFollowDecision(
                location = outsideLocation,
                mapBounds = mapBounds,
                followMode = PhoneMapFollowMode.FOLLOW_LOCATION,
            )

        assertEquals(false, decision.locationInsideMapBounds)
        assertFalse(decision.shouldCenterOnLocation)
    }

    @Test
    fun noLocationPreservesCameraAndRecenteringPreservesOrientation() {
        val decision =
            phoneOfflineLocationFollowDecision(
                location = null,
                mapBounds = mapBounds,
                followMode = PhoneMapFollowMode.FOLLOW_LOCATION,
            )
        val recentered =
            PhoneMapMode(
                orientation = PhoneMapOrientation.HEADING_UP,
                follow = PhoneMapFollowMode.FREE,
            ).recenterOnLocation()

        assertNull(decision.locationInsideMapBounds)
        assertFalse(decision.shouldCenterOnLocation)
        assertEquals(PhoneMapOrientation.HEADING_UP, recentered.orientation)
        assertEquals(PhoneMapFollowMode.FOLLOW_LOCATION, recentered.follow)
    }

    @Test
    fun stoppingUpdatesRetainsTheLatestValidLocation() {
        val retained =
            PhoneMapLocationSubscription()
                .start()
                .update(insideLocation)
                .stop()

        assertFalse(retained.isActive)
        assertEquals(insideLocation, retained.latestLocation)
    }

    @Test
    fun runtimeReportDistinguishesDrawAndVisibleTilesWithoutCoordinates() {
        val notDrawn =
            runtime(
                androidDrawObserved = false,
                tileLayerDrawObserved = false,
                firstVisibleTile = false,
                location = null,
            ).toReportSection()
        val androidDrawnWithoutTileLayer =
            runtime(
                androidDrawObserved = true,
                tileLayerDrawObserved = false,
                firstVisibleTile = false,
                location = outsideLocation,
            ).toReportSection()
        val tileLayerDrawnWithoutVisibleTile =
            runtime(
                androidDrawObserved = true,
                tileLayerDrawObserved = true,
                firstVisibleTile = false,
                location = outsideLocation,
            ).toReportSection()
        val visibleTile =
            runtime(
                androidDrawObserved = true,
                tileLayerDrawObserved = true,
                firstVisibleTile = true,
                location = insideLocation,
            ).toReportSection()

        assertTrue(notDrawn.contains("Android MapView draw observed: false"))
        assertTrue(notDrawn.contains("MapView render ready: true"))
        assertTrue(notDrawn.contains("Tile layer draw observed: false"))
        assertTrue(notDrawn.contains("First visible base tile: false"))
        assertTrue(notDrawn.contains("Location available: false"))
        assertTrue(androidDrawnWithoutTileLayer.contains("Android MapView draw observed: true"))
        assertTrue(androidDrawnWithoutTileLayer.contains("Tile layer draw observed: false"))
        assertTrue(tileLayerDrawnWithoutVisibleTile.contains("Tile layer draw observed: true"))
        assertTrue(tileLayerDrawnWithoutVisibleTile.contains("First visible base tile: false"))
        assertTrue(tileLayerDrawnWithoutVisibleTile.contains("Location inside map bounds: false"))
        assertTrue(visibleTile.contains("First visible base tile: true"))
        assertTrue(visibleTile.contains("Drawable visible tiles: 2"))
        assertTrue(visibleTile.contains("Location inside map bounds: true"))
        assertTrue(visibleTile.contains("Location marker attached: true"))
        assertTrue(visibleTile.contains("Location marker bitmap draw observed: true"))
        assertFalse(visibleTile.contains("47.5"))
        assertFalse(visibleTile.contains("11.5"))
    }

    @Test
    fun runtimeReportDistinguishesMarkerCallFromBitmapDraw() {
        val report =
            runtime(
                androidDrawObserved = true,
                tileLayerDrawObserved = true,
                firstVisibleTile = false,
                location = insideLocation,
            ).copy(
                locationMarkerBitmapDrawObserved = false,
                locationMarkerLastDrawResult = "outside_viewport",
            ).toReportSection()

        assertTrue(report.contains("Location marker draw calls: 1"))
        assertTrue(report.contains("Location marker bitmap draw observed: false"))
        assertTrue(report.contains("Location marker last draw result: outside_viewport"))
    }

    private fun runtime(
        androidDrawObserved: Boolean,
        tileLayerDrawObserved: Boolean,
        firstVisibleTile: Boolean,
        location: PhoneMapLocation?,
    ): PhoneOfflineMapRuntimeDiagnostics =
        PhoneOfflineMapRuntimeDiagnostics(
            displayName = "alps.map",
            rendererId = 11,
            mapViewId = 22,
            layerId = 33,
            cacheId = "phone-offline-test",
            mapViewAttached = true,
            mapViewHasWindowFocus = true,
            mapViewWidth = 1080,
            mapViewHeight = 1800,
            mapViewRenderReady = true,
            androidMapViewDrawObserved = androidDrawObserved,
            tileLayerDrawObserved = tileLayerDrawObserved,
            firstVisibleBaseTileObserved = firstVisibleTile,
            layerCount = 2,
            tileLayerPresent = true,
            tileLayerVisible = true,
            frameBufferDimensionAvailable = true,
            frameBufferWidth = 1080,
            frameBufferHeight = 1800,
            frameBufferDrawingBitmapReady = true,
            zoom = 14,
            cameraInsideMapBounds = true,
            visibleTileCount = 6,
            drawableVisibleTileCount = 2,
            parentFallbackTileCount = 1,
            pendingTileJobCount = 3,
            locationPermissionGranted = true,
            locationAvailable = location != null,
            locationAgeMillis = location?.ageMillis(nowMs = 50_000L),
            locationAccuracyMeters = location?.accuracyMeters,
            locationInsideMapBounds = location?.let { mapBounds.contains(it.latitude, it.longitude) },
            followMode = PhoneMapFollowMode.FOLLOW_LOCATION,
            orientation = PhoneMapOrientation.NORTH_UP,
            locationMarkerAttached = location != null,
            locationMarkerVisible = location != null,
            locationMarkerDrawCalls = if (location == null) 0 else 1,
            locationMarkerBitmapDrawObserved = location != null,
            locationMarkerLastDrawResult = if (location == null) null else "bitmap_drawn",
        )

    private fun phoneMapLocation(
        latitude: Double,
        longitude: Double,
    ): PhoneMapLocation =
        PhoneMapLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = 8f,
            fixElapsedRealtimeMillis = 45_000L,
        )
}
