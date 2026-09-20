package com.glancemap.glancemapwearos.presentation.features.routetools

import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RouteToolPreflightTest {
    @Test
    fun coordinatesRouteNeedsGpsOnlyForCurrentEndpoints() {
        assertTrue(
            RouteToolOptions(
                createMode = RouteCreateMode.COORDINATES,
                startEndpointSource = RouteEndpointSource.CURRENT_LOCATION,
                destinationEndpointSource = RouteEndpointSource.COORDINATES,
            ).needsCurrentLocationForCreate(),
        )
        assertTrue(
            RouteToolOptions(
                createMode = RouteCreateMode.COORDINATES,
                startEndpointSource = RouteEndpointSource.COORDINATES,
                destinationEndpointSource = RouteEndpointSource.CURRENT_LOCATION,
            ).needsCurrentLocationForCreate(),
        )
        assertFalse(
            RouteToolOptions(
                createMode = RouteCreateMode.COORDINATES,
                startEndpointSource = RouteEndpointSource.COORDINATES,
                destinationEndpointSource = RouteEndpointSource.COORDINATES,
            ).needsCurrentLocationForCreate(),
        )
    }

    @Test
    fun coordinatesRouteValidatesMissingAndOutOfRangeValues() {
        val missing =
            RouteToolOptions(
                createMode = RouteCreateMode.COORDINATES,
                destinationEndpointSource = RouteEndpointSource.COORDINATES,
            )
        assertTrue(missing.coordinateEndpointValidationMessage(null).orEmpty().contains("valid destination"))

        val invalid =
            missing.copy(
                destinationCoordinateLatitude = 91.0,
                destinationCoordinateLongitude = 2.0,
            )
        assertTrue(invalid.coordinateEndpointValidationMessage(null).orEmpty().contains("valid destination"))
    }

    @Test
    fun coordinateValuesSurviveSwitchingEndpointSources() {
        val entered =
            RouteToolOptions(
                createMode = RouteCreateMode.COORDINATES,
                startEndpointSource = RouteEndpointSource.COORDINATES,
                startCoordinateLatitude = 48.1,
                startCoordinateLongitude = 2.2,
                destinationEndpointSource = RouteEndpointSource.COORDINATES,
                destinationCoordinateLatitude = 48.3,
                destinationCoordinateLongitude = 2.4,
            )
        val switchedBack =
            entered
                .copy(startEndpointSource = RouteEndpointSource.CURRENT_LOCATION)
                .copy(destinationEndpointSource = RouteEndpointSource.CURRENT_LOCATION)
                .copy(startEndpointSource = RouteEndpointSource.COORDINATES)
                .copy(destinationEndpointSource = RouteEndpointSource.COORDINATES)
                .seedCoordinateEndpoints(seed = LatLong(0.0, 0.0))

        assertTrue(switchedBack.startCoordinateLatitude == 48.1)
        assertTrue(switchedBack.startCoordinateLongitude == 2.2)
        assertTrue(switchedBack.destinationCoordinateLatitude == 48.3)
        assertTrue(switchedBack.destinationCoordinateLongitude == 2.4)
    }

    @Test
    fun routeToolAcceptsRecentOriginWhenProviderTemporarilyReportsUnavailable() {
        val usable =
            hasUsableRouteToolCurrentLocation(
                currentLocation = LatLong(9.03, 38.74),
                gpsSignalSnapshot =
                    GpsSignalSnapshot(
                        lastFixElapsedRealtimeMs = 90_000L,
                        isLocationAvailable = false,
                        lastFixFresh = false,
                    ),
                nowElapsedMs = 100_000L,
            )

        assertTrue(usable)
    }

    @Test
    fun routeToolRejectsOriginOlderThanTenSeconds() {
        val usable =
            hasUsableRouteToolCurrentLocation(
                currentLocation = LatLong(9.03, 38.74),
                gpsSignalSnapshot = GpsSignalSnapshot(lastFixElapsedRealtimeMs = 90_000L),
                nowElapsedMs = 100_001L,
            )

        assertFalse(usable)
    }

    @Test
    fun routeToolRequiresAnAcceptedLocation() {
        val usable =
            hasUsableRouteToolCurrentLocation(
                currentLocation = null,
                gpsSignalSnapshot = GpsSignalSnapshot(lastFixElapsedRealtimeMs = 100_000L),
                nowElapsedMs = 100_000L,
            )

        assertFalse(usable)
    }
}
