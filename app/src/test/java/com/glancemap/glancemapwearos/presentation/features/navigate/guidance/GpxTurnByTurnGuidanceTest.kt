package com.glancemap.glancemapwearos.presentation.features.navigate.guidance

import com.glancemap.glancemapwearos.presentation.features.gpx.GpxGuidanceHint
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxGuidanceHintSource
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class GpxTurnByTurnGuidanceTest {
    @Test
    fun deriveInstructionsDetectsLeftTurnFromGpxGeometry() {
        val session =
            buildGpxGuidanceSession(
                trackId = "left.gpx",
                trackTitle = "Left route",
                trackPoints =
                    listOf(
                        point(0.0, 0.0),
                        point(0.0, 0.001),
                        point(0.001, 0.001),
                    ),
                startReached = true,
            )

        assertEquals(RouteInstructionCommand.LEFT, session.instructions.first().command)
        assertEquals(RouteInstructionCommand.FINISH, session.instructions.last().command)
    }

    @Test
    fun hintedInstructionsArePreferredOverGpxGeometry() {
        val session =
            buildGpxGuidanceSession(
                trackId = "hinted.gpx",
                trackTitle = "Hinted route",
                trackPoints =
                    listOf(
                        point(0.0, 0.0),
                        point(
                            lat = 0.0,
                            lon = 0.001,
                            guidanceHint =
                                GpxGuidanceHint(
                                    commandCode = "TR",
                                    message = "right",
                                    source = GpxGuidanceHintSource.BROUTER,
                                ),
                        ),
                        point(0.001, 0.001),
                    ),
                startReached = true,
            )

        assertEquals(RouteInstructionSource.BROUTER_HINT, session.instructions.first().source)
        assertEquals(RouteInstructionCommand.RIGHT, session.instructions.first().command)
        assertEquals("Right", session.instructions.first().message)
        assertEquals(RouteInstructionCommand.FINISH, session.instructions.last().command)
    }

    @Test
    fun reversedGuidanceInvertsDirectionSpecificHints() {
        val session =
            buildGpxGuidanceSession(
                trackId = "hinted-reverse.gpx",
                trackTitle = "Hinted reverse route",
                trackPoints =
                    listOf(
                        point(0.001, 0.001),
                        point(
                            lat = 0.0,
                            lon = 0.001,
                            guidanceHint =
                                GpxGuidanceHint(
                                    commandCode = "TR",
                                    message = "right",
                                    source = GpxGuidanceHintSource.BROUTER,
                                ),
                        ),
                        point(0.0, 0.0),
                    ),
                startReached = true,
                reversed = true,
            )

        assertEquals(RouteInstructionSource.BROUTER_HINT, session.instructions.first().source)
        assertEquals(RouteInstructionCommand.LEFT, session.instructions.first().command)
        assertEquals("Left", session.instructions.first().message)
    }

    @Test
    fun reversedHintDerivationKeepsNonDirectionalHints() {
        val instructions =
            deriveHintedRouteInstructions(
                trackPoints =
                    listOf(
                        point(0.0, 0.0),
                        point(
                            lat = 0.0,
                            lon = 0.001,
                            guidanceHint =
                                GpxGuidanceHint(
                                    commandCode = "C",
                                    message = "continue",
                                    source = GpxGuidanceHintSource.BROUTER,
                                ),
                        ),
                        point(0.0, 0.002),
                    ),
                reverseDirection = true,
            )

        assertEquals(RouteInstructionCommand.CONTINUE, instructions.first().command)
        assertEquals("Continue", instructions.first().message)
    }

    @Test
    fun guidanceStartsByPointingToGpxStartWhenStartNotReached() {
        val session =
            buildGpxGuidanceSession(
                trackId = "route.gpx",
                trackTitle = "Route",
                trackPoints =
                    listOf(
                        point(45.0, 6.0),
                        point(45.0, 6.001),
                    ),
                startReached = false,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.01),
            )

        assertEquals(GuidanceMode.TO_START, state.mode)
        assertTrue((state.distanceToStartMeters ?: 0.0) > 700.0)
        assertNotNull(state.bearingToStartDegrees)
    }

    @Test
    fun guidanceFollowsRouteAfterStartIsReached() {
        val session =
            buildGpxGuidanceSession(
                trackId = "route.gpx",
                trackTitle = "Route",
                trackPoints =
                    listOf(
                        point(45.0, 6.0),
                        point(45.0, 6.001),
                        point(45.001, 6.001),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.0002),
            )

        assertEquals(GuidanceMode.FOLLOW_ROUTE, state.mode)
        assertEquals(RouteInstructionCommand.LEFT, state.nextInstruction?.command)
        assertTrue((state.distanceRemainingMeters ?: 0.0) > 0.0)
        assertTrue((state.routeProgressFraction ?: 0f) > 0f)
        assertTrue((state.routeProgressFraction ?: 1f) < 1f)
    }

    @Test
    fun guidanceKeepsTurnUntilProgressConfirmsOutgoingLeg() {
        val session =
            buildGpxGuidanceSession(
                trackId = "retained-turn.gpx",
                trackTitle = "Retained turn",
                trackPoints =
                    listOf(
                        point(45.0, 6.0),
                        point(45.0, 6.001),
                        point(45.001, 6.001),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.00007, 6.001),
                previousDistanceFromStartMeters = session.instructions.first().distanceFromStartMeters,
            )

        assertEquals(RouteInstructionCommand.LEFT, state.nextInstruction?.command)
        assertEquals(0.0, state.distanceToInstructionMeters ?: -1.0, 0.01)
    }

    @Test
    fun guidanceAdvancesAfterOutgoingLegConfirmsTurn() {
        val session =
            buildGpxGuidanceSession(
                trackId = "confirmed-turn.gpx",
                trackTitle = "Confirmed turn",
                trackPoints =
                    listOf(
                        point(45.0, 6.0),
                        point(45.0, 6.001),
                        point(45.001, 6.001),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.00014, 6.001),
                previousDistanceFromStartMeters = session.instructions.first().distanceFromStartMeters,
            )

        assertEquals(RouteInstructionCommand.FINISH, state.nextInstruction?.command)
    }

    @Test
    fun loopRouteDoesNotFinishAtItsStart() {
        val session =
            buildGpxGuidanceSession(
                trackId = "loop.gpx",
                trackTitle = "Loop",
                trackPoints =
                    listOf(
                        point(45.0, 6.0),
                        point(45.0, 6.001),
                        point(45.001, 6.001),
                        point(45.001, 6.0),
                        point(45.0, 6.0),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.0),
            )

        assertEquals(GuidanceMode.FOLLOW_ROUTE, state.mode)
        assertTrue((state.routeProgressFraction ?: 1f) < 0.1f)
    }

    @Test
    fun guidanceReportsRemainingAscentAndDescent() {
        val session =
            buildGpxGuidanceSession(
                trackId = "elevation.gpx",
                trackTitle = "Elevation route",
                trackPoints =
                    listOf(
                        point(45.0, 6.0, elevation = 100.0),
                        point(45.0, 6.001, elevation = 200.0),
                        point(45.0, 6.002, elevation = 150.0),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.0001),
            )

        assertTrue((state.remainingAscentMeters ?: 0.0) > 80.0)
        assertTrue((state.remainingDescentMeters ?: 0.0) > 40.0)
    }

    @Test
    fun guidancePreviewsUphillTerrainAfterTheNextManeuver() {
        val session =
            buildGpxGuidanceSession(
                trackId = "terrain-up.gpx",
                trackTitle = "Terrain route",
                trackPoints =
                    listOf(
                        point(45.0, 6.0, elevation = 200.0),
                        point(45.0, 6.001, elevation = 100.0),
                        point(45.001, 6.001, elevation = 130.0),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.0001),
            )

        assertEquals(RouteInstructionCommand.LEFT, state.nextInstruction?.command)
        assertEquals(GuidanceTerrainDirection.UPHILL, state.nextSegmentTerrain?.direction)
        assertTrue((state.nextSegmentTerrain?.elevationChangeMeters ?: 0.0) > 25.0)

        val confirmedState =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.00025, 6.001),
            )

        assertEquals(RouteInstructionCommand.LEFT, confirmedState.recentManeuverTerrain?.maneuver)
        assertEquals(GuidanceTerrainDirection.UPHILL, confirmedState.recentManeuverTerrain?.terrain?.direction)
    }

    @Test
    fun guidancePreviewsFlatWhenNextSegmentIsBelowTerrainThreshold() {
        val session =
            buildGpxGuidanceSession(
                trackId = "terrain-flat.gpx",
                trackTitle = "Terrain route",
                trackPoints =
                    listOf(
                        point(45.0, 6.0, elevation = 200.0),
                        point(45.0, 6.001, elevation = 100.0),
                        point(45.001, 6.001, elevation = 104.0),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.0001),
            )

        assertEquals(GuidanceTerrainDirection.FLAT, state.nextSegmentTerrain?.direction)
    }

    @Test
    fun guidanceFinishesWhenNearRouteEnd() {
        val session =
            buildGpxGuidanceSession(
                trackId = "route.gpx",
                trackTitle = "Route",
                trackPoints =
                    listOf(
                        point(45.0, 6.0),
                        point(45.0, 6.001),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.001),
            )

        assertEquals(GuidanceMode.FINISHED, state.mode)
        assertEquals(RouteInstructionCommand.FINISH, state.nextInstruction?.command)
    }

    @Test
    fun guidanceDoesNotFinishWhenProjectedPastEndButFarFromRoute() {
        val session =
            buildGpxGuidanceSession(
                trackId = "route.gpx",
                trackTitle = "Route",
                trackPoints =
                    listOf(
                        point(45.0, 6.0),
                        point(45.0, 6.001),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.002, 6.0012),
            )

        assertEquals(GuidanceMode.FOLLOW_ROUTE, state.mode)
        assertEquals(RouteInstructionCommand.FINISH, state.nextInstruction?.command)
        assertTrue(state.offRoute)
        assertTrue((state.distanceToRouteMeters ?: 0.0) > 100.0)
    }

    @Test
    fun projectionTracksDistanceAlongRoute() {
        val points =
            listOf(
                LatLong(45.0, 6.0),
                LatLong(45.0, 6.002),
            )
        val projection =
            projectLocationToRoute(
                points = points,
                location = LatLong(45.0, 6.001),
            )

        assertNotNull(projection)
        assertEquals(0, projection?.segmentIndex)
        assertEquals(0.5, projection?.t ?: 0.0, 0.05)
    }

    @Test
    fun projectionPrefersProgressContinuityAtRouteCrossing() {
        val points =
            listOf(
                LatLong(45.0000, 6.0000),
                LatLong(45.0010, 6.0010),
                LatLong(45.0000, 6.0020),
                LatLong(44.9990, 6.0010),
                LatLong(45.0000, 6.0000),
                LatLong(45.0010, 5.9990),
            )
        val cumulative = buildCumulativeDistances(points)
        val location = LatLong(45.0000, 6.0000)

        val projection =
            projectLocationToRoute(
                points = points,
                cumulativeDistancesMeters = cumulative,
                location = location,
                previousDistanceFromStartMeters = cumulative[3],
            )

        assertNotNull(projection)
        assertTrue((projection?.distanceFromStartMeters ?: 0.0) > cumulative[2])
    }

    @Test
    fun projectionRelocksWhenContinuityWindowIsClearlyWrong() {
        val points =
            listOf(
                LatLong(45.0000, 6.0000),
                LatLong(45.0000, 6.0010),
                LatLong(45.0100, 6.0100),
                LatLong(45.0100, 6.0110),
            )
        val cumulative = buildCumulativeDistances(points)

        val projection =
            projectLocationToRoute(
                points = points,
                cumulativeDistancesMeters = cumulative,
                location = LatLong(45.0100, 6.0105),
                previousDistanceFromStartMeters = 20.0,
            )

        assertNotNull(projection)
        assertTrue((projection?.distanceFromStartMeters ?: 0.0) > cumulative[1])
    }

    @Test
    fun offRouteRequiresTwoOutsideSamples() {
        var state = GuidanceOffRouteConfirmationState()

        state =
            updateGuidanceOffRouteConfirmation(
                previous = state,
                distanceToRouteMeters = 80.0,
                thresholdMeters = 60.0,
                allowOffRouteEntry = true,
            )
        assertTrue(!state.offRoute)

        state =
            updateGuidanceOffRouteConfirmation(
                previous = state,
                distanceToRouteMeters = 80.0,
                thresholdMeters = 60.0,
                allowOffRouteEntry = true,
            )
        assertTrue(state.offRoute)
    }

    @Test
    fun offRouteClearsOnStrongRecovery() {
        var state = GuidanceOffRouteConfirmationState()
        repeat(2) {
            state =
                updateGuidanceOffRouteConfirmation(
                    previous = state,
                    distanceToRouteMeters = 150.0,
                    thresholdMeters = 60.0,
                    allowOffRouteEntry = true,
                )
        }
        assertTrue(state.offRoute)

        state = GuidanceOffRouteConfirmationState(offRoute = true)
        state =
            updateGuidanceOffRouteConfirmation(
                previous = state,
                distanceToRouteMeters = 25.0,
                thresholdMeters = 60.0,
                allowOffRouteEntry = true,
            )
        assertTrue(!state.offRoute)
    }

    @Test
    fun offRouteUsesSelectedThreshold() {
        var state = GuidanceOffRouteConfirmationState()

        state =
            updateGuidanceOffRouteConfirmation(
                previous = state,
                distanceToRouteMeters = 32.0,
                thresholdMeters = 20.0,
                allowOffRouteEntry = true,
            )
        assertTrue(!state.offRoute)

        state =
            updateGuidanceOffRouteConfirmation(
                previous = state,
                distanceToRouteMeters = 32.0,
                thresholdMeters = 20.0,
                allowOffRouteEntry = true,
            )
        assertTrue(state.offRoute)
    }

    @Test
    fun offRouteBorderlineRecoveryStillNeedsConfirmation() {
        var state = GuidanceOffRouteConfirmationState(offRoute = true)

        state =
            updateGuidanceOffRouteConfirmation(
                previous = state,
                distanceToRouteMeters = 18.0,
                thresholdMeters = 20.0,
                allowOffRouteEntry = true,
            )
        assertTrue(state.offRoute)
        state =
            updateGuidanceOffRouteConfirmation(
                previous = state,
                distanceToRouteMeters = 18.0,
                thresholdMeters = 20.0,
                allowOffRouteEntry = true,
            )

        assertTrue(!state.offRoute)
    }

    private fun point(
        lat: Double,
        lon: Double,
        guidanceHint: GpxGuidanceHint? = null,
        elevation: Double? = null,
    ): TrackPoint =
        TrackPoint(
            latLong = LatLong(lat, lon),
            elevation = elevation,
            guidanceHint = guidanceHint,
        )
}
