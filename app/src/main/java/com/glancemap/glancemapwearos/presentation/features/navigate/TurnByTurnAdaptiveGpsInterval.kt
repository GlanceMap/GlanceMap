package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState

/**
 * Selects the shared screen-off GPS cadence for turn-by-turn guidance.
 *
 * The route supplies the next manoeuvre distance. The current speed (with a conservative
 * profile fallback) turns that distance into time until the haptic alert window, allowing the
 * GPS request to stay slow while cruising and become responsive before a manoeuvre.
 */
internal fun resolveAdaptiveTurnByTurnScreenOffIntervalMs(
    state: TurnByTurnGuidanceState,
    currentSpeedMps: Float?,
    activityProfile: String,
): Long {
    val cadence = adaptiveTurnByTurnCadence(activityProfile)
    if (!state.active || state.mode != GuidanceMode.FOLLOW_ROUTE || state.offRoute) {
        return cadence.nearIntervalMs
    }
    val nextTurnDistanceMeters = state.nextActionableTurnDistanceMeters() ?: return cadence.cruiseIntervalMs
    val speedMps = conservativeGuidanceSpeedMps(currentSpeedMps, activityProfile)
    val alertDistanceMeters =
        turnHapticDistanceMeters(
            speedMps = speedMps.toFloat(),
            activityProfile = activityProfile,
            gpsDeliveryIntervalMs = cadence.nearIntervalMs,
        )
    val secondsUntilAlert = ((nextTurnDistanceMeters - alertDistanceMeters).coerceAtLeast(0.0) / speedMps)
    return when {
        secondsUntilAlert <= cadence.nearWindowSeconds -> cadence.nearIntervalMs
        secondsUntilAlert <= cadence.approachWindowSeconds -> cadence.approachIntervalMs
        else -> cadence.cruiseIntervalMs
    }
}

private data class AdaptiveTurnByTurnCadence(
    val cruiseIntervalMs: Long,
    val approachIntervalMs: Long,
    val nearIntervalMs: Long,
    val approachWindowSeconds: Double,
    val nearWindowSeconds: Double,
)

private fun adaptiveTurnByTurnCadence(activityProfile: String): AdaptiveTurnByTurnCadence =
    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        AdaptiveTurnByTurnCadence(
            cruiseIntervalMs = BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS,
            approachIntervalMs = BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS,
            nearIntervalMs = BIKE_ADAPTIVE_TBT_NEAR_INTERVAL_MS,
            approachWindowSeconds = BIKE_ADAPTIVE_TBT_APPROACH_WINDOW_SECONDS,
            nearWindowSeconds = BIKE_ADAPTIVE_TBT_NEAR_WINDOW_SECONDS,
        )
    } else {
        AdaptiveTurnByTurnCadence(
            cruiseIntervalMs = ADAPTIVE_TBT_CRUISE_INTERVAL_MS,
            approachIntervalMs = ADAPTIVE_TBT_APPROACH_INTERVAL_MS,
            nearIntervalMs = ADAPTIVE_TBT_NEAR_INTERVAL_MS,
            approachWindowSeconds = ADAPTIVE_TBT_APPROACH_WINDOW_SECONDS,
            nearWindowSeconds = ADAPTIVE_TBT_NEAR_WINDOW_SECONDS,
        )
    }

private fun TurnByTurnGuidanceState.nextActionableTurnDistanceMeters(): Double? =
    listOf(
        nextInstruction?.command to distanceToInstructionMeters,
        followingInstruction?.command to distanceToFollowingInstructionMeters,
    ).mapNotNull { (command, distanceMeters) ->
        distanceMeters
            ?.takeIf(Double::isFinite)
            ?.takeIf { it >= 0.0 && command.isActionableTurn() }
    }.minOrNull()

private fun RouteInstructionCommand?.isActionableTurn(): Boolean =
    this != null &&
        this != RouteInstructionCommand.CONTINUE &&
        this != RouteInstructionCommand.FINISH

private fun conservativeGuidanceSpeedMps(
    currentSpeedMps: Float?,
    activityProfile: String,
): Double {
    val observedSpeedMps = currentSpeedMps?.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 0.0
    val conservativeProfileSpeedMps =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            ADAPTIVE_TBT_BIKE_CONSERVATIVE_SPEED_MPS
        } else {
            ADAPTIVE_TBT_HIKE_CONSERVATIVE_SPEED_MPS
        }
    return maxOf(observedSpeedMps, conservativeProfileSpeedMps)
}

internal const val ADAPTIVE_TBT_CRUISE_INTERVAL_MS = 10_000L
internal const val ADAPTIVE_TBT_APPROACH_INTERVAL_MS = 5_000L
internal const val ADAPTIVE_TBT_NEAR_INTERVAL_MS = 3_000L
internal const val BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS = 5_000L
internal const val BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS = 3_000L
internal const val BIKE_ADAPTIVE_TBT_NEAR_INTERVAL_MS = 1_000L

private const val ADAPTIVE_TBT_APPROACH_WINDOW_SECONDS = 60.0
private const val ADAPTIVE_TBT_NEAR_WINDOW_SECONDS = 20.0
private const val BIKE_ADAPTIVE_TBT_APPROACH_WINDOW_SECONDS = 30.0
private const val BIKE_ADAPTIVE_TBT_NEAR_WINDOW_SECONDS = 8.0
private const val ADAPTIVE_TBT_HIKE_CONSERVATIVE_SPEED_MPS = 2.5
private const val ADAPTIVE_TBT_BIKE_CONSERVATIVE_SPEED_MPS = 10.0
