package com.glancemap.glancemapwearos.presentation.features.navigate.guidance

import com.glancemap.glancemapwearos.presentation.features.gpx.FileSig
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxGuidanceHint
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxGuidanceHintSource
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.buildProfile
import com.glancemap.glancemapwearos.presentation.features.gpx.buildTurnByTurnGuidanceSessionFromProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

@Suppress("LargeClass") // Keeps the existing guidance regression cases together with profile coverage.
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
    fun waitingForLocationReportsFullRouteElevation() {
        val session =
            sessionFromProfile(
                trackId = "waiting-elevation.gpx",
                points =
                    listOf(
                        point(45.0, 6.0, elevation = 100.0),
                        point(45.0, 6.001, elevation = 200.0),
                        point(45.0, 6.002, elevation = 150.0),
                    ),
            )

        val state = computeTurnByTurnGuidanceState(session = session, currentLocation = null)

        assertEquals(session.cumulativeAscentMeters.last(), state.remainingAscentMeters ?: -1.0, 0.01)
        assertEquals(session.cumulativeDescentMeters.last(), state.remainingDescentMeters ?: -1.0, 0.01)
    }

    @Test
    fun toStartReportsFullRouteElevation() {
        val session =
            sessionFromProfile(
                trackId = "to-start-elevation.gpx",
                startReached = false,
                points =
                    listOf(
                        point(45.0, 6.0, elevation = 100.0),
                        point(45.0, 6.001, elevation = 200.0),
                        point(45.0, 6.002, elevation = 150.0),
                    ),
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.01),
            )

        assertEquals(GuidanceMode.TO_START, state.mode)
        assertEquals(session.cumulativeAscentMeters.last(), state.remainingAscentMeters ?: -1.0, 0.01)
        assertEquals(session.cumulativeDescentMeters.last(), state.remainingDescentMeters ?: -1.0, 0.01)
    }

    @Test
    fun followRouteWithInsufficientElevationKeepsElevationUnavailable() {
        val session =
            buildGpxGuidanceSession(
                trackId = "partial-elevation.gpx",
                trackTitle = "Partial elevation route",
                trackPoints =
                    listOf(
                        point(45.0, 6.0, elevation = 100.0),
                        point(45.0, 6.001),
                        point(45.0, 6.002),
                    ),
                startReached = true,
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.0002),
            )

        assertNull(state.remainingAscentMeters)
        assertNull(state.remainingDescentMeters)
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
            sessionFromProfile(
                trackId = "elevation.gpx",
                points =
                    listOf(
                        point(45.0, 6.0, elevation = 100.0),
                        point(45.0, 6.001, elevation = 200.0),
                        point(45.0, 6.002, elevation = 150.0),
                    ),
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(45.0, 6.0001),
            )

        assertNotNull(state.remainingAscentMeters)
        assertNotNull(state.remainingDescentMeters)
        assertTrue(state.remainingAscentMeters!! <= session.cumulativeAscentMeters.last())
        assertTrue(state.remainingDescentMeters!! <= session.cumulativeDescentMeters.last())
    }

    @Test
    fun tbtUsesCanonicalFilteredElevationSnapshot() {
        val points =
            listOf(
                point(0.0, 0.0, elevation = 100.0),
                point(0.0, 0.001, elevation = 100.2),
                point(0.0, 0.002, elevation = 99.9),
                point(0.0, 0.003, elevation = 100.1),
                point(0.0, 0.004, elevation = 130.0),
            )
        val profile = buildProfile(FileSig(0L, points.size.toLong()), points)
        val session = sessionFromProfile(trackId = "noisy.gpx", points = points)

        assertEquals(profile.cumAscent.toList(), session.cumulativeAscentMeters)
        assertEquals(profile.cumDescent.toList(), session.cumulativeDescentMeters)
        assertTrue(session.cumulativeAscentMeters.last() < 30.3)
    }

    @Test
    fun remainingElevationInterpolatesCanonicalCumulativeSnapshot() {
        val session =
            sessionFromProfile(
                trackId = "interpolated.gpx",
                points =
                    listOf(
                        point(0.0, 0.0, elevation = 100.0),
                        point(0.0, 0.02, elevation = 220.0),
                        point(0.0, 0.04, elevation = 100.0),
                    ),
            )
        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(0.0, 0.01),
            )
        val expectedAscentAtHalfFirstSegment =
            (session.cumulativeAscentMeters[0] + session.cumulativeAscentMeters[1]) / 2.0
        val expectedDescentAtHalfFirstSegment =
            (session.cumulativeDescentMeters[0] + session.cumulativeDescentMeters[1]) / 2.0

        assertEquals(
            session.cumulativeAscentMeters.last() - expectedAscentAtHalfFirstSegment,
            state.remainingAscentMeters ?: -1.0,
            0.5,
        )
        assertEquals(
            session.cumulativeDescentMeters.last() - expectedDescentAtHalfFirstSegment,
            state.remainingDescentMeters ?: -1.0,
            0.5,
        )
    }

    @Test
    fun reverseSessionRebuildsProfileInTravelDirection() {
        val points =
            listOf(
                point(0.0, 0.0, elevation = 100.0, startsNewSegment = true),
                point(0.0, 0.001, elevation = 110.0),
                point(0.0, 0.002, elevation = 200.0, startsNewSegment = true),
                point(0.0, 0.003, elevation = 210.0),
            )
        val session = sessionFromProfile(trackId = "reverse.gpx", points = points, reversed = true)

        assertEquals(listOf(true, false, true, false), session.trackPoints.map { it.startsNewSegment })
        assertEquals(0.0, session.cumulativeAscentMeters.last(), 0.5)
        assertTrue(session.cumulativeDescentMeters.last() > 15.0)
    }

    @Test
    fun segmentGapsDoNotCreateElevationChanges() {
        val points =
            listOf(
                point(0.0, 0.0, elevation = 100.0, startsNewSegment = true),
                point(0.0, 0.001, elevation = 200.0),
                point(0.0, 0.002, elevation = 0.0, startsNewSegment = true),
                point(0.0, 0.003, elevation = 100.0),
            )
        val session = sessionFromProfile(trackId = "gaps.gpx", points = points)

        assertEquals(session.cumulativeDistancesMeters[1], session.cumulativeDistancesMeters[2], 0.0)
        assertEquals(session.cumulativeAscentMeters[1], session.cumulativeAscentMeters[2], 0.0)
        assertTrue(session.cumulativeAscentMeters.last() > session.cumulativeAscentMeters[2])
        assertEquals(0.0, session.cumulativeDescentMeters.last(), 0.5)
    }

    @Test
    fun missingElevationsKeepTbtElevationUnavailable() {
        val session =
            sessionFromProfile(
                trackId = "missing-elevation.gpx",
                points =
                    listOf(
                        point(0.0, 0.0),
                        point(0.0, 0.001),
                        point(0.0, 0.002),
                    ),
            )

        val state =
            computeTurnByTurnGuidanceState(
                session = session,
                currentLocation = LatLong(0.0, 0.001),
            )

        assertNull(state.remainingAscentMeters)
        assertNull(state.remainingDescentMeters)
    }

    @Test
    fun restoredSessionRetainsCanonicalElevationSnapshot() {
        val points =
            listOf(
                point(0.0, 0.0, elevation = 100.0),
                point(0.0, 0.001, elevation = 150.0),
                point(0.0, 0.002, elevation = 120.0),
            )
        val profile = buildProfile(FileSig(0L, points.size.toLong()), points)
        val restored = sessionFromProfile(trackId = "restored.gpx", points = points)

        assertEquals(profile.cumAscent.toList(), restored.cumulativeAscentMeters)
        assertEquals(profile.cumDescent.toList(), restored.cumulativeDescentMeters)
        assertEquals(profile.cumDist.toList(), restored.cumulativeDistancesMeters)
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
        startsNewSegment: Boolean = false,
    ): TrackPoint =
        TrackPoint(
            latLong = LatLong(lat, lon),
            elevation = elevation,
            startsNewSegment = startsNewSegment,
            guidanceHint = guidanceHint,
        )

    private fun sessionFromProfile(
        trackId: String,
        points: List<TrackPoint>,
        startReached: Boolean = true,
        reversed: Boolean = false,
    ): GpxGuidanceSession =
        buildTurnByTurnGuidanceSessionFromProfile(
            trackId = trackId,
            trackTitle = trackId,
            profile = buildProfile(FileSig(0L, points.size.toLong()), points),
            startReached = startReached,
            reversed = reversed,
        )
}
