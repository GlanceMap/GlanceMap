package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong

class NavigateLiveDistanceSupportTest {
    @Test
    fun liveDistanceOriginPrefersCurrentMarkerPosition() {
        val marker = LatLong(45.001, 6.001)
        val fallback = LatLong(45.0, 6.0)

        assertEquals(marker, resolveLiveDistanceOrigin(marker, fallback))
    }

    @Test
    fun liveDistanceOriginFallsBackToLastKnownLocation() {
        val fallback = LatLong(45.0, 6.0)

        assertEquals(fallback, resolveLiveDistanceOrigin(null, fallback))
    }

    @Test
    fun liveDistanceMetersMeasureFromMarkerOriginToVisibleTarget() {
        val markerOrigin = LatLong(45.0, 6.0)
        val visibleTarget = LatLong(45.0, 6.01)

        val distanceMeters =
            resolveLiveDistanceMeters(
                origin = markerOrigin,
                target = visibleTarget,
            )

        assertEquals(787.0, distanceMeters, 1.0)
    }

    @Test
    fun visibleScreenCenterStaysAtMapCenterForMiddlePivot() {
        val mapSpacePoint =
            visibleScreenCenterToMapSpace(
                mapWidthPx = 400.0,
                mapHeightPx = 400.0,
                visibleHeightPx = 400,
                mapRotationDeg = 90.0,
                rotationPivot = ScreenAnchor(200.0, 200.0),
            )

        assertEquals(200.0, mapSpacePoint.x, 0.001)
        assertEquals(200.0, mapSpacePoint.y, 0.001)
    }

    @Test
    fun visibleScreenCenterUnrotatesAroundLowerMapPivot() {
        val mapSpacePoint =
            visibleScreenCenterToMapSpace(
                mapWidthPx = 400.0,
                mapHeightPx = 640.0,
                visibleHeightPx = 400,
                mapRotationDeg = 90.0,
                rotationPivot = ScreenAnchor(200.0, 320.0),
            )

        assertEquals(80.0, mapSpacePoint.x, 0.001)
        assertEquals(320.0, mapSpacePoint.y, 0.001)
    }
}
