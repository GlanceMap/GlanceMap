package com.glancemap.glancemapwearos.presentation.features.routetools

import com.glancemap.glancemapwearos.presentation.features.gpx.GpxEtaModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RouteToolPlannerMappingTest {
    @Test
    fun coordinatesRouteMapsCurrentStartToEnteredDestination() {
        val current = LatLong(42.5, 1.6)
        val request =
            coordinateSession(
                startSource = RouteEndpointSource.CURRENT_LOCATION,
                destinationSource = RouteEndpointSource.COORDINATES,
                destination = LatLong(42.6, 1.7),
            ).toRoutePlannerRequest(currentLocation = current)

        assertEquals(current, request.origin)
        assertEquals(LatLong(42.6, 1.7), request.destination)
    }

    @Test
    fun coordinatesRouteMapsEnteredStartToCurrentDestination() {
        val current = LatLong(42.5, 1.6)
        val request =
            coordinateSession(
                startSource = RouteEndpointSource.COORDINATES,
                start = LatLong(42.4, 1.5),
                destinationSource = RouteEndpointSource.CURRENT_LOCATION,
            ).toRoutePlannerRequest(currentLocation = current)

        assertEquals(LatLong(42.4, 1.5), request.origin)
        assertEquals(current, request.destination)
    }

    @Test
    fun coordinatesRouteMapsTwoEnteredEndpointsWithoutCurrentLocation() {
        val request =
            coordinateSession(
                startSource = RouteEndpointSource.COORDINATES,
                start = LatLong(42.4, 1.5),
                destinationSource = RouteEndpointSource.COORDINATES,
                destination = LatLong(42.6, 1.7),
            ).toRoutePlannerRequest(currentLocation = null)

        assertEquals(LatLong(42.4, 1.5), request.origin)
        assertEquals(LatLong(42.6, 1.7), request.destination)
    }

    @Test
    fun coordinatesRouteRejectsCurrentToCurrent() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                coordinateSession(
                    startSource = RouteEndpointSource.CURRENT_LOCATION,
                    destinationSource = RouteEndpointSource.CURRENT_LOCATION,
                ).toRoutePlannerRequest(currentLocation = LatLong(42.5, 1.6))
            }

        assertTrue(error.message.orEmpty().contains("Choose coordinates"))
    }

    @Test
    fun coordinatesRouteRejectsEffectivelySameEndpoints() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                coordinateSession(
                    startSource = RouteEndpointSource.COORDINATES,
                    start = LatLong(42.500000, 1.600000),
                    destinationSource = RouteEndpointSource.COORDINATES,
                    destination = LatLong(42.500005, 1.600005),
                ).toRoutePlannerRequest(currentLocation = null)
            }

        assertTrue(error.message.orEmpty().contains("must be different"))
    }

    @Test
    fun loopRequestKeepsDistanceTargetWhenDistanceModeIsSelected() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopTargetMode = LoopTargetMode.DISTANCE,
                        loopDistanceKm = 12,
                    ),
            ).toRoundTripPlannerRequest(currentLocation = LatLong(42.5, 1.6))

        assertEquals(12_000, request.targetDistanceMeters)
        assertEquals("12 km", request.targetLabel)
    }

    @Test
    fun loopRequestConvertsTimeTargetIntoEstimatedDistance() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopTargetMode = LoopTargetMode.TIME,
                        loopDurationMinutes = 90,
                        routeStyle = RouteStylePreset.BALANCED_HIKE,
                        useElevation = true,
                    ),
            ).toRoundTripPlannerRequest(currentLocation = LatLong(42.5, 1.6))

        assertEquals(6_000, request.targetDistanceMeters)
        assertEquals("1 h 30", request.targetLabel)
    }

    @Test
    fun loopTimeRequestUsesConfiguredFlatSpeedWhenAvailable() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopTargetMode = LoopTargetMode.TIME,
                        loopDurationMinutes = 90,
                        routeStyle = RouteStylePreset.BALANCED_HIKE,
                        useElevation = true,
                    ),
            ).toRoundTripPlannerRequest(
                currentLocation = LatLong(42.5, 1.6),
                etaModelConfig = GpxEtaModelConfig(flatSpeedMps = 1.0),
            )

        assertEquals(5_400, request.targetDistanceMeters)
        assertEquals("1 h 30", request.targetLabel)
    }

    @Test
    fun loopRequestCanOverrideTargetDistanceAfterEtaCorrection() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopTargetMode = LoopTargetMode.TIME,
                        loopDurationMinutes = 90,
                    ),
            ).toRoundTripPlannerRequest(
                currentLocation = LatLong(42.5, 1.6),
                etaModelConfig = GpxEtaModelConfig(flatSpeedMps = 1.0),
                targetDistanceMetersOverride = 4_800,
            )

        assertEquals(4_800, request.targetDistanceMeters)
        assertEquals("1 h 30", request.targetLabel)
    }

    @Test
    fun loopRequestDefaultsToCircuitPreference() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                    ),
            ).toRoundTripPlannerRequest(currentLocation = LatLong(42.5, 1.6))

        assertFalse(request.allowOutAndBack)
        assertEquals(5, request.pointCount)
    }

    @Test
    fun loopRequestCanAllowOutAndBack() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.LOOP_AROUND_HERE,
                        loopShapeMode = LoopShapeMode.ALLOW_OUT_AND_BACK,
                    ),
            ).toRoundTripPlannerRequest(currentLocation = LatLong(42.5, 1.6))

        assertTrue(request.allowOutAndBack)
        assertEquals(5, request.pointCount)
    }

    @Test
    fun multiPointRequestUsesMiddlePointsAsViaPoints() {
        val request =
            RouteToolSession(
                options =
                    RouteToolOptions(
                        toolKind = RouteToolKind.CREATE,
                        createMode = RouteCreateMode.MULTI_POINT_CHAIN,
                    ),
                chainPoints =
                    listOf(
                        LatLong(42.50, 1.50),
                        LatLong(42.55, 1.55),
                        LatLong(42.60, 1.60),
                        LatLong(42.65, 1.65),
                    ),
            ).toRoutePlannerRequest(currentLocation = null)

        assertEquals(LatLong(42.50, 1.50), request.origin)
        assertEquals(LatLong(42.65, 1.65), request.destination)
        assertEquals(
            listOf(LatLong(42.55, 1.55), LatLong(42.60, 1.60)),
            request.viaPoints,
        )
    }

    private fun coordinateSession(
        startSource: RouteEndpointSource,
        start: LatLong? = null,
        destinationSource: RouteEndpointSource,
        destination: LatLong? = null,
    ): RouteToolSession =
        RouteToolSession(
            options =
                RouteToolOptions(
                    toolKind = RouteToolKind.CREATE,
                    createMode = RouteCreateMode.COORDINATES,
                    startEndpointSource = startSource,
                    startCoordinateLatitude = start?.latitude,
                    startCoordinateLongitude = start?.longitude,
                    destinationEndpointSource = destinationSource,
                    destinationCoordinateLatitude = destination?.latitude,
                    destinationCoordinateLongitude = destination?.longitude,
                ),
        )
}
