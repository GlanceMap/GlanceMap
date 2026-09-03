package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMapDistanceMeasurementTest {
    @Test
    fun distanceUsesGreatCircleMeters() {
        val distance =
            phoneMapDistanceMeters(
                PhoneMapCoordinate(latitude = 0.0, longitude = 0.0),
                PhoneMapCoordinate(latitude = 0.0, longitude = 1.0),
            )

        assertEquals(111_195.0, distance, 100.0)
    }

    @Test
    fun distanceFormatsUsingSelectedUnits() {
        assertEquals("1.5 km", formatPhoneMapMeasuredDistance(1_500.0, isMetric = true))
        assertEquals("1.0 mi", formatPhoneMapMeasuredDistance(1_609.344, isMetric = false))
    }

    @Test
    fun liveDistancePrefersRenderedMarkerOriginOverLocationFallback() {
        val renderedMarker = PhoneMapCoordinate(latitude = 46.0, longitude = 6.0)
        val locationFallback = PhoneMapCoordinate(latitude = 46.0, longitude = 7.0)
        val target = PhoneMapCoordinate(latitude = 46.0, longitude = 6.01)
        val position =
            PhoneMapLiveMetricsPosition(
                target = target,
                userScreenPoint = PhoneMapScreenPoint(x = 0f, y = 0f),
                origin = renderedMarker,
            )

        val liveDistance = phoneMapLiveDistanceMeters(position, locationFallback)

        assertEquals(phoneMapDistanceMeters(renderedMarker, target), liveDistance ?: Double.NaN, 0.0)
    }

    @Test
    fun liveMetricsOriginUsesMarkerPositionInsteadOfMapCenter() {
        val marker = PhoneMapCoordinate(latitude = 46.0, longitude = 6.0)
        val mapCenter = PhoneMapCoordinate(latitude = 46.0, longitude = 7.0)

        assertEquals(
            marker,
            resolvePhoneMapLiveMetricsOrigin(
                markerPosition = marker,
                locationFallback = mapCenter,
            ),
        )
    }

    @Test
    fun measurementCreatesTwoDistinctMapMarkers() {
        val first = PhoneMapCoordinate(latitude = 46.0, longitude = 6.0)
        val second = PhoneMapCoordinate(latitude = 46.1, longitude = 6.1)

        val markers =
            phoneMapDistanceMeasurementMarkers(
                PhoneMapDistanceMeasurement(first = first, second = second),
            )

        assertEquals(
            listOf(
                PhoneMapPointSelectionMarkerKind.MEASUREMENT_FIRST,
                PhoneMapPointSelectionMarkerKind.MEASUREMENT_SECOND,
            ),
            markers.map { marker -> marker.kind },
        )
        assertEquals(first, markers.first().point)
        assertEquals(second, markers.last().point)
    }
}
