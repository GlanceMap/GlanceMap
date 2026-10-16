package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceCommand
import com.glancemap.trailcore.guidance.TrailGuidanceEtaConfig
import com.glancemap.trailcore.guidance.TrailGuidanceInstruction
import com.glancemap.trailcore.guidance.TrailGuidanceMode
import com.glancemap.trailcore.guidance.TrailGuidanceState
import com.glancemap.trailcore.profile.TrailGuidanceHint
import com.glancemap.trailcore.profile.TrailGuidanceHintSource
import com.glancemap.trailcore.profile.TrailPacingConfig
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import com.glancemap.trailcore.recording.RecordingElevationProfileConfig
import com.glancemap.trailcore.recording.RecordingElevationProfilePoint
import com.glancemap.trailcore.recording.buildRecordingElevationProfileCumulative
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGuidanceTest {
    @Test
    fun locationFailureKeepsGuidanceProgressButDisablesTheStaleManeuver() {
        val instruction =
            TrailGuidanceInstruction(
                command = TrailGuidanceCommand.RIGHT,
                message = "Turn right",
                location = GeoPoint(46.0, 11.001),
                trackPointIndex = 1,
                distanceFromStartMeters = 50.0,
                turnAngleDegrees = 90f,
            )
        val route =
            PhoneGuidanceRouteDraft(
                trackId = "phone-route",
                trackTitle = "Phone route",
                points =
                    listOf(
                        TrailPoint(GeoPoint(46.0, 11.0), null),
                        TrailPoint(GeoPoint(46.0, 11.001), null),
                    ),
                routeOffsetMeters = 250.0,
                activityProfile = "HIKE",
                etaConfig = TrailGuidanceEtaConfig(flatSpeedMps = 1.2),
            )
        val session = buildPhoneGuidanceSession(route)
        val activeGuidance =
            guidanceState(TrailGuidanceMode.FOLLOW_ROUTE, offRoute = true).copy(
                nextInstruction = instruction,
                followingInstruction = instruction,
                distanceToInstructionMeters = 18.0,
                distanceToFollowingInstructionMeters = 50.0,
                distanceFromStartMeters = 42.0,
                originalRouteDistanceFromStartMeters = 292.0,
                distanceRemainingMeters = 108.0,
                routeProgressFraction = 0.28f,
                estimatedRemainingSeconds = 90L,
                currentAltitudeMeters = 1_600.0,
            )
        val active =
            PhoneGuidanceUiState(
                session = session,
                guidance = activeGuidance,
                pendingDecision = PhoneGuidanceDecision.START_HERE,
                message = PhoneGuidanceMessage.ACTIVE,
            )

        val unavailable = active.withLocationUnavailable(PhoneGuidanceMessage.GPS_UNAVAILABLE)
        val waitingGuidance = requireNotNull(unavailable.guidance)

        assertEquals(session, unavailable.session)
        assertEquals(PhoneGuidanceMessage.GPS_UNAVAILABLE, unavailable.message)
        assertNull(unavailable.pendingDecision)
        assertEquals(TrailGuidanceMode.WAITING_FOR_LOCATION, waitingGuidance.mode)
        assertNull(waitingGuidance.nextInstruction)
        assertNull(waitingGuidance.followingInstruction)
        assertNull(waitingGuidance.distanceToInstructionMeters)
        assertFalse(waitingGuidance.offRoute)
        assertNull(waitingGuidance.currentAltitudeMeters)
        assertEquals(activeGuidance.distanceFromStartMeters, waitingGuidance.distanceFromStartMeters)
        assertEquals(
            activeGuidance.originalRouteDistanceFromStartMeters,
            waitingGuidance.originalRouteDistanceFromStartMeters,
        )
        assertEquals(activeGuidance.distanceRemainingMeters, waitingGuidance.distanceRemainingMeters)
        assertEquals(activeGuidance.estimatedRemainingSeconds, waitingGuidance.estimatedRemainingSeconds)
    }

    @Test
    fun voiceTurnUsesSharedEarlyThresholdAndDoesNotRetryAfterPassingTurn() {
        val instruction =
            TrailGuidanceInstruction(
                command = TrailGuidanceCommand.LEFT,
                message = "Turn left",
                location = GeoPoint(46.0, 11.0),
                trackPointIndex = 3,
                distanceFromStartMeters = 100.0,
                turnAngleDegrees = 90f,
            )
        val due =
            guidanceState(TrailGuidanceMode.FOLLOW_ROUTE, offRoute = false).copy(
                nextInstruction = instruction,
                distanceToInstructionMeters = 25.0,
                distanceFromStartMeters = 75.0,
                alertSessionKey = "route:false",
            )

        fun voiceKey(
            guidance: TrailGuidanceState = due,
            paused: Boolean = false,
            voiceEnabled: Boolean = true,
        ) = phoneGuidanceVoiceInstructionKey(
            guidance = guidance,
            paused = paused,
            voiceEnabled = voiceEnabled,
            speedMps = 2f,
            activityProfile = "HIKE",
            turnAlertsMode = "ALL",
        )

        assertEquals("route:false:3:LEFT", voiceKey())
        assertNull(voiceKey(due.copy(distanceToInstructionMeters = 36.0)))
        assertNull(voiceKey(due.copy(distanceFromStartMeters = 101.0, distanceToInstructionMeters = 0.0)))
        assertNull(voiceKey(due.copy(offRoute = true)))
        assertNull(voiceKey(paused = true))
        assertNull(voiceKey(voiceEnabled = false))
    }

    @Test
    fun startGateRejectsDuplicateStartsAndIgnoresAStaleCompletion() {
        val gate = PhoneGuidanceStartGate()
        val first = gate.begin(alreadyActive = false)

        assertTrue(first != null)
        assertNull(gate.begin(alreadyActive = false))
        assertNull(gate.begin(alreadyActive = true))

        gate.invalidate()
        assertFalse(gate.complete(first!!))
        val next = gate.begin(alreadyActive = false)
        assertTrue(next != null && next > first)
        assertTrue(gate.complete(next!!))
        assertFalse(gate.complete(next))
    }

    @Test
    fun preparedSessionKeepsHintsSegmentsOffsetAndReverseDirection() {
        val route =
            PhoneGuidanceRouteDraft(
                trackId = "mission-day-2",
                trackTitle = "Alpine traverse — Day 2",
                points =
                    listOf(
                        TrailPoint(GeoPoint(46.0, 11.0), elevationMeters = 1_000.0),
                        TrailPoint(
                            GeoPoint(46.0, 11.001),
                            elevationMeters = 1_010.0,
                            guidanceHint = TrailGuidanceHint("TR", "Turn right", TrailGuidanceHintSource.BROUTER),
                        ),
                        TrailPoint(GeoPoint(46.0, 11.002), elevationMeters = 1_020.0),
                        TrailPoint(GeoPoint(46.1, 11.1), elevationMeters = 1_200.0, startsNewSegment = true),
                        TrailPoint(GeoPoint(46.1, 11.101), elevationMeters = 1_180.0),
                    ),
                routeOffsetMeters = 4_200.0,
                activityProfile = "HIKE",
                etaConfig = TrailGuidanceEtaConfig(flatSpeedMps = 1.25),
            )

        val session = buildPhoneGuidanceSession(route, PhoneGuidanceProgressDraft(reversed = true, startReached = true))

        assertFalse(route.sessionActive)
        assertEquals("mission-day-2", session.trackId)
        assertEquals(4_200.0, session.routeOffsetMeters, 0.0)
        assertTrue(session.reversed)
        assertEquals(route.points.last().location, session.trackPoints.first().location)
        assertTrue(session.trackPoints[2].startsNewSegment)
        assertEquals(TrailGuidanceHintSource.BROUTER, session.trackPoints[3].guidanceHint?.source)
        assertEquals(session.trackPoints.size, session.cumulativeAscentMeters.size)
        assertEquals(session.trackPoints.size, session.cumulativeDescentMeters.size)
    }

    @Test
    fun etaSettingsMapToTheSharedWatchCalculationInputs() {
        val gpxSettings =
            PhoneMapGpxSettings(
                flatSpeedMetersPerSecond = 2.4f,
                advancedEtaEnabled = true,
                staminaAdjustmentEnabled = true,
                uphillVerticalMetersPerHour = 420f,
                downhillVerticalMetersPerHour = 650f,
            )
        val general = PhoneGeneralSettings(userWeightKg = 81f, backpackWeightKg = 9f, bikeWeightKg = 14f)

        val config = phoneGuidanceEtaConfig(gpxSettings, general, PhoneActivityProfile.BIKE)

        assertEquals(2.4, config.flatSpeedMps, 0.0001)
        assertTrue(config.advancedVerticalRateEnabled)
        assertTrue(config.staminaAdjustmentEnabled)
        assertEquals("BIKE", config.activityProfile)
        assertEquals(81.0, config.userWeightKg, 0.0)
        assertEquals(9.0, config.backpackWeightKg, 0.0)
        assertEquals(14.0, config.bikeWeightKg, 0.0)
        assertEquals(420.0, config.uphillVerticalMetersPerHour, 0.0)
        assertEquals(650.0, config.downhillVerticalMetersPerHour, 0.0)
    }

    @Test
    fun pauseAndResumePersistenceKeepsProgressAndAlertDeduplication() {
        val beforePause =
            PhoneGuidanceProgressDraft(
                reversed = true,
                startReached = true,
                distanceFromStartMeters = 820.0,
                stableMidRouteSampleCount = 2,
                offRoute = true,
                outsideSampleCount = 2,
                lastAlertInstructionKey = "route:reverse:turn-4",
            )
        val paused = beforePause.copy(paused = true)
        val restored = Gson().fromJson(Gson().toJson(paused), PhoneGuidanceProgressDraft::class.java)
        val resumed = restored.copy(paused = false)

        assertTrue(restored.paused)
        assertFalse(resumed.paused)
        assertEquals(beforePause.copy(paused = false), resumed)
    }

    @Test
    fun guidanceDraftWriteFailureDoesNotInterruptTheSharedRecordingFixPath() {
        var recordingFixProcessed = false

        val persisted = persistGuidanceProgressSafely(write = { error("disk full") })
        recordingFixProcessed = true

        assertFalse(persisted)
        assertTrue(recordingFixProcessed)
    }

    @Test
    fun guidanceFeedbackTracksOffRouteRecoveryAndArrivalTransitions() {
        val following = guidanceState(TrailGuidanceMode.FOLLOW_ROUTE, offRoute = false)
        val offRoute = following.copy(offRoute = true)
        val finished = guidanceState(TrailGuidanceMode.FINISHED, offRoute = false)

        assertEquals(PhoneGuidanceFeedbackEvent.OFF_ROUTE, phoneGuidanceFeedbackEvent(following, offRoute))
        assertEquals(PhoneGuidanceFeedbackEvent.BACK_ON_ROUTE, phoneGuidanceFeedbackEvent(offRoute, following))
        assertEquals(PhoneGuidanceFeedbackEvent.ARRIVAL, phoneGuidanceFeedbackEvent(following, finished))
        assertNull(phoneGuidanceFeedbackEvent(finished, finished))
        assertFalse(PhoneGuidanceFeedbackSettings().voiceEnabled)
        assertTrue(PhoneGuidanceFeedbackSettings().hapticsEnabled)
    }

    @Test
    fun phoneElevationUsesTheWatchCanonicalFilterAndKeepsMissingDataUnavailable() {
        val points =
            listOf(
                TrailPoint(GeoPoint(46.0, 11.0), 100.0, hasTimestamp = true),
                TrailPoint(GeoPoint(46.0, 11.001), 110.0, hasTimestamp = true),
                TrailPoint(GeoPoint(46.0, 11.002), 125.0, hasTimestamp = true),
                TrailPoint(GeoPoint(46.0, 11.003), 140.0, hasTimestamp = true),
            )
        val routeProfile = buildTrailRouteProfile(points, TrailPacingConfig())
        val cumulativeDistances = routeProfile.cumulativeDistanceMeters.toDoubleArray()
        val expected =
            buildRecordingElevationProfileCumulative(
                points.map { RecordingElevationProfilePoint(it.elevationMeters, it.startsNewSegment, it.hasTimestamp) },
                DoubleArray(points.lastIndex) { cumulativeDistances[it + 1] - cumulativeDistances[it] },
                cumulativeDistances,
                RecordingElevationProfileConfig(),
            )
        val route =
            PhoneGuidanceRouteDraft(
                trackId = "elevation",
                trackTitle = "Elevation",
                points = points,
                routeOffsetMeters = 0.0,
                activityProfile = "HIKE",
                etaConfig = TrailGuidanceEtaConfig(flatSpeedMps = 1.2),
            )
        val session = buildPhoneGuidanceSession(route)
        val noElevation = buildPhoneGuidanceSession(route.copy(points = points.map { it.copy(elevationMeters = null) }))

        assertEquals(expected.first.toList(), session.cumulativeAscentMeters)
        assertEquals(expected.second.toList(), session.cumulativeDescentMeters)
        assertTrue(session.cumulativeAscentMeters.last() > 0.0)
        assertTrue(noElevation.cumulativeAscentMeters.isEmpty())
        assertTrue(noElevation.cumulativeDescentMeters.isEmpty())
    }

    private fun guidanceState(
        mode: TrailGuidanceMode,
        offRoute: Boolean,
    ) = TrailGuidanceState(
        active = true,
        mode = mode,
        trackTitle = "Route",
        nextInstruction = null,
        distanceToInstructionMeters = null,
        distanceToStartMeters = null,
        bearingToStartDegrees = null,
        distanceToRouteMeters = null,
        bearingToRouteDegrees = null,
        distanceRemainingMeters = null,
        routeProgressFraction = null,
        offRoute = offRoute,
    )
}
