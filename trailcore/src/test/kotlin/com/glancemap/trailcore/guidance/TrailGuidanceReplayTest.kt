package com.glancemap.trailcore.guidance

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailGuidanceHint
import com.glancemap.trailcore.profile.TrailGuidanceHintSource
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed route/fix replays shared by the watch and phone guidance adapters. */
class TrailGuidanceReplayTest {
    @Test
    fun hintedGpxTurnWinsAndASeparatedSegmentDoesNotCreateAProjectionBridge() {
        val points =
            listOf(
                point(0.0, 0.0),
                point(
                    0.0,
                    0.001,
                    hint = TrailGuidanceHint("TR", "Turn right", TrailGuidanceHintSource.BROUTER),
                ),
                point(0.0, 0.002),
                point(1.0, 1.0, startsNewSegment = true),
                point(1.0, 1.001),
            )
        val session = buildTrailGuidanceSession("replay", "Replay", points, startReached = true)

        assertEquals(TrailGuidanceCommand.RIGHT, session.instructions.first().command)
        assertEquals("Turn right", session.instructions.first().message)
        assertEquals(TrailGuidanceInstructionSource.BROUTER_HINT, session.instructions.first().source)
        assertTrue(session.totalDistanceMeters in 330.0..340.0)

        val projection = projectTrailLocationToRoute(points, session.cumulativeDistancesMeters, GeoPoint(0.5, 0.5))
        assertNotNull(projection)
        assertTrue("projection must remain far from the segment gap", projection!!.distanceToRouteMeters > 70_000.0)
    }

    @Test
    fun delayedFixWaitsAndAClosedLoopUsesPreviousProgressToKeepTheCorrectBranch() {
        val points =
            listOf(
                point(0.0, 0.0),
                point(0.0, 0.001),
                point(0.001, 0.001),
                point(0.001, 0.0),
                point(0.0, 0.0),
            )
        val session = buildTrailGuidanceSession("loop", "Loop", points, startReached = true)

        assertEquals(TrailGuidanceMode.WAITING_FOR_LOCATION, computeTrailGuidanceState(session, null).mode)
        val lateFix = computeTrailGuidanceState(session, GeoPoint(0.0, 0.0), previousDistanceFromStartMeters = session.totalDistanceMeters - 3.0)

        assertEquals(TrailGuidanceMode.FINISHED, lateFix.mode)
        assertEquals(session.totalDistanceMeters, lateFix.distanceFromStartMeters ?: -1.0, 0.01)
    }

    @Test
    fun reverseProgressMapsBackToTheOriginalMissionRouteOffset() {
        val originalPoints = listOf(point(0.0, 0.0), point(0.0, 0.001), point(0.0, 0.002))
        val reversed = reverseTrailPoints(originalPoints)
        val session =
            buildTrailGuidanceSession(
                trackId = "day-2",
                trackTitle = "Day 2",
                trackPoints = reversed,
                reversed = true,
                startReached = true,
                routeOffsetMeters = 500.0,
            )
        val state = computeTrailGuidanceState(session, GeoPoint(0.0, 0.001), previousDistanceFromStartMeters = null)

        assertEquals(session.totalDistanceMeters / 2.0, state.distanceFromStartMeters ?: -1.0, 0.1)
        assertEquals(500.0 + session.totalDistanceMeters / 2.0, state.originalRouteDistanceFromStartMeters ?: -1.0, 0.1)
        assertTrue(reversed.first().startsNewSegment)
        assertFalse(reversed.drop(1).any(TrailPoint::startsNewSegment))
    }

    @Test
    fun startAndReversePromptsUseStableAccurateFixesAndTheSameRouteThresholds() {
        val points = (0..4).map { index -> point(0.0, index * 0.001) }
        val session = buildTrailGuidanceSession("long", "Long", points)
        val midRoute = GeoPoint(0.0, 0.002)

        val first = startDecision(session, midRoute, previous = 0)
        assertNull(first.decision)
        assertEquals(1, first.stableMidRouteSampleCount)
        val staleSecond =
            evaluateTrailGuidanceStartDecision(
                session,
                midRoute,
                5f,
                hasFreshLocationFix = false,
                previousStableMidRouteSampleCount = first.stableMidRouteSampleCount,
                routeStartBehavior = TRAIL_GUIDANCE_ROUTE_START_ASK,
                reverseSuggestionMode = TRAIL_GUIDANCE_REVERSE_SUGGESTION_ASK,
            )
        assertNull(staleSecond.decision)

        val stableSecond = startDecision(session, midRoute, previous = first.stableMidRouteSampleCount)
        assertEquals(TrailGuidanceStartDecision.START_HERE, stableSecond.decision)

        val nearReverseEnd = startDecision(session, GeoPoint(0.0, 0.0039), previous = 0)
        assertEquals(TrailGuidanceStartDecision.REVERSE_ROUTE, nearReverseEnd.decision)
    }

    @Test
    fun offRouteNeedsTwoOutsideFixesAndTwoRecoveryFixes() {
        var state = TrailGuidanceOffRouteConfirmationState()
        state = updateGuidanceOffRouteConfirmation(state, 50.0, 40.0, allowOffRouteEntry = true)
        assertFalse(state.offRoute)
        state = updateGuidanceOffRouteConfirmation(state, 50.0, 40.0, allowOffRouteEntry = true)
        assertTrue(state.offRoute)
        state = updateGuidanceOffRouteConfirmation(state, 35.0, 40.0, allowOffRouteEntry = true)
        assertTrue(state.offRoute)
        assertEquals(1, state.recoverySampleCount)
        state = updateGuidanceOffRouteConfirmation(state, 35.0, 40.0, allowOffRouteEntry = true)
        assertFalse(state.offRoute)
        assertEquals(0, state.recoverySampleCount)
    }

    @Test
    fun etaAndElevationUseTheSharedGuidanceProjection() {
        val points = listOf(point(0.0, 0.0, elevation = 100.0), point(0.0, 0.001, elevation = 120.0))
        val session =
            buildTrailGuidanceSession("eta", "ETA", points, startReached = true)
                .copy(cumulativeAscentMeters = listOf(0.0, 20.0), cumulativeDescentMeters = listOf(0.0, 0.0))
        val flatPoints = listOf(point(0.0, 0.0, elevation = 100.0), point(0.0, 0.001, elevation = 100.0))
        val etaDistances = listOf(0.0, 100.0)
        val projection = buildTrailGuidanceEtaProjection(flatPoints, etaDistances, TrailGuidanceEtaConfig(flatSpeedMps = 2.0))

        assertEquals(50.0, projection?.totalSeconds ?: -1.0, 0.001)
        assertEquals(25L, projection?.remainingSecondsAtDistance(etaDistances, 50.0))
        val midway = computeTrailGuidanceState(session, GeoPoint(0.0, 0.0005))
        assertEquals(10.0, midway.remainingAscentMeters ?: -1.0, 0.1)
        assertEquals(0.0, midway.remainingDescentMeters ?: -1.0, 0.1)
    }

    @Test
    fun crossingRecoveryFiresOnceWhenAFixSkipsPastTheHapticWindow() {
        val maneuver =
            TrailGuidanceInstruction(
                command = TrailGuidanceCommand.LEFT,
                message = "Left",
                location = GeoPoint(0.0, 0.001),
                trackPointIndex = 1,
                distanceFromStartMeters = 100.0,
                turnAngleDegrees = 90f,
            )
        val finish = maneuver.copy(command = TrailGuidanceCommand.FINISH, message = "Finish", trackPointIndex = 2, distanceFromStartMeters = 200.0)
        val tracker = TrailGuidanceAlertTracker()
        val before = alertSample(maneuver, distance = 20.0, progress = 80.0, timestamp = 1_000L)
        val after = alertSample(finish, distance = 100.0, progress = 110.0, timestamp = 6_000L)

        assertTrue(tracker.update(before).isEmpty())
        val crossing = tracker.update(after)
        assertEquals(1, crossing.size)
        assertEquals(TrailGuidanceAlertOutcome.FIRED, crossing.single().outcome)
        assertEquals(TrailGuidanceAlertTrigger.CROSSING, crossing.single().trigger)
        assertTrue(tracker.update(after.copy(fixTimestampMs = 9_000L)).isEmpty())
    }

    @Test
    fun hikeAndBikeCadenceKeepsTheWatchIntervals() {
        val instruction =
            TrailGuidanceInstruction(
                TrailGuidanceCommand.LEFT,
                "Left",
                GeoPoint(0.0, 0.0),
                10,
                1_000.0,
                null,
            )
        val far =
            TrailGuidanceState(
                active = true,
                mode = TrailGuidanceMode.FOLLOW_ROUTE,
                trackTitle = "Route",
                nextInstruction = instruction,
                distanceToInstructionMeters = 1_000.0,
                distanceToStartMeters = 0.0,
                bearingToStartDegrees = null,
                distanceToRouteMeters = 0.0,
                bearingToRouteDegrees = null,
                distanceRemainingMeters = 2_000.0,
                routeProgressFraction = 0.5f,
                offRoute = false,
            )

        assertEquals(ADAPTIVE_TBT_CRUISE_INTERVAL_MS, resolveAdaptiveTrailGuidanceGpsIntervalMs(far, 2.0f, "HIKE"))
        assertEquals(BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS, resolveAdaptiveTrailGuidanceGpsIntervalMs(far, 10.0f, "BIKE"))
        assertEquals(ADAPTIVE_TBT_NEAR_INTERVAL_MS, resolveAdaptiveTrailGuidanceGpsIntervalMs(far.copy(offRoute = true), 2.0f, "HIKE"))
    }

    private fun startDecision(
        session: TrailGuidanceSession,
        location: GeoPoint,
        previous: Int,
    ) = evaluateTrailGuidanceStartDecision(
        session = session,
        location = location,
        locationAccuracyMeters = 5f,
        hasFreshLocationFix = true,
        previousStableMidRouteSampleCount = previous,
        routeStartBehavior = TRAIL_GUIDANCE_ROUTE_START_ASK,
        reverseSuggestionMode = TRAIL_GUIDANCE_REVERSE_SUGGESTION_ASK,
    )

    private fun alertSample(
        instruction: TrailGuidanceInstruction,
        distance: Double,
        progress: Double,
        timestamp: Long,
    ) = TrailGuidanceAlertSample(
        routeKey = "gpx",
        active = true,
        mode = TrailGuidanceMode.FOLLOW_ROUTE,
        offRoute = false,
        instruction = instruction,
        distanceToInstructionMeters = distance,
        distanceFromStartMeters = progress,
        speedMps = 1.5f,
        activityProfile = "HIKE",
        gpsDeliveryIntervalMs = 3_000L,
        hapticsEnabled = true,
        turnAlertsMode = "ALL",
        fixTimestampMs = timestamp,
    )

    private fun point(
        latitude: Double,
        longitude: Double,
        elevation: Double? = null,
        startsNewSegment: Boolean = false,
        hint: TrailGuidanceHint? = null,
    ) = TrailPoint(GeoPoint(latitude, longitude), elevation, startsNewSegment, hint)
}
