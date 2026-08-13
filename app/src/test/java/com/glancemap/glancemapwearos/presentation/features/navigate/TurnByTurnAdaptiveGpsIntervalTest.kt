package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstruction
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong

class TurnByTurnAdaptiveGpsIntervalTest {
    @Test
    fun usesTenSecondsWhileATurnIsSafelyFarAway() {
        assertEquals(
            ADAPTIVE_TBT_CRUISE_INTERVAL_MS,
            resolveAdaptiveTurnByTurnScreenOffIntervalMs(
                state = state(distanceToNextTurnMeters = 500.0),
                currentSpeedMps = 1.2f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
            ),
        )
    }

    @Test
    fun bikeUsesFiveSecondsWhileCruising() {
        assertEquals(
            BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS,
            resolveAdaptiveTurnByTurnScreenOffIntervalMs(
                state = state(distanceToNextTurnMeters = 500.0),
                currentSpeedMps = 10f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            ),
        )
    }

    @Test
    fun bikeUsesThreeSecondsWhenTheNextTurnIsApproaching() {
        assertEquals(
            BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS,
            resolveAdaptiveTurnByTurnScreenOffIntervalMs(
                state = state(distanceToNextTurnMeters = 250.0),
                currentSpeedMps = 10f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            ),
        )
    }

    @Test
    fun bikeUsesOneSecondInTheFinalEightSecondsBeforeTheAlertWindow() {
        assertEquals(
            BIKE_ADAPTIVE_TBT_NEAR_INTERVAL_MS,
            resolveAdaptiveTurnByTurnScreenOffIntervalMs(
                state = state(distanceToNextTurnMeters = 100.0),
                currentSpeedMps = 10f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            ),
        )
    }

    @Test
    fun usesFollowingActionableTurnWhenTheCurrentInstructionIsContinue() {
        assertEquals(
            BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS,
            resolveAdaptiveTurnByTurnScreenOffIntervalMs(
                state =
                    state(
                        distanceToNextTurnMeters = 1_000.0,
                        nextCommand = RouteInstructionCommand.CONTINUE,
                        followingDistanceMeters = 250.0,
                    ),
                currentSpeedMps = 10f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            ),
        )
    }

    @Test
    fun staysResponsiveWhileRecoveringFromAnOffRouteState() {
        assertEquals(
            ADAPTIVE_TBT_NEAR_INTERVAL_MS,
            resolveAdaptiveTurnByTurnScreenOffIntervalMs(
                state = state(distanceToNextTurnMeters = 800.0, offRoute = true),
                currentSpeedMps = 2f,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
            ),
        )
    }

    private fun state(
        distanceToNextTurnMeters: Double,
        nextCommand: RouteInstructionCommand = RouteInstructionCommand.LEFT,
        followingDistanceMeters: Double? = null,
        offRoute: Boolean = false,
    ): TurnByTurnGuidanceState =
        TurnByTurnGuidanceState(
            active = true,
            mode = GuidanceMode.FOLLOW_ROUTE,
            trackTitle = "Route",
            nextInstruction = instruction(nextCommand, trackPointIndex = 10),
            distanceToInstructionMeters = distanceToNextTurnMeters,
            distanceToStartMeters = 0.0,
            bearingToStartDegrees = null,
            distanceToRouteMeters = 0.0,
            bearingToRouteDegrees = null,
            distanceRemainingMeters = 5_000.0,
            routeProgressFraction = 0.2f,
            offRoute = offRoute,
            followingInstruction =
                followingDistanceMeters?.let {
                    instruction(RouteInstructionCommand.RIGHT, trackPointIndex = 20)
                },
            distanceToFollowingInstructionMeters = followingDistanceMeters,
        )

    private fun instruction(
        command: RouteInstructionCommand,
        trackPointIndex: Int,
    ): RouteInstruction =
        RouteInstruction(
            command = command,
            message = command.name,
            latLong = LatLong(0.0, 0.0),
            trackPointIndex = trackPointIndex,
            distanceFromStartMeters = trackPointIndex.toDouble() * 10.0,
            turnAngleDegrees = null,
        )
}
