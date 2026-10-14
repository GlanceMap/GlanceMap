package com.glancemap.trailcore.guidance

private const val GUIDANCE_ACTIVITY_PROFILE_BIKE = "BIKE"

/**
 * Selects the shared screen-off GPS cadence for turn-by-turn guidance.
 *
 * The route supplies the next manoeuvre distance. The current speed (with a conservative
 * profile fallback) turns that distance into time until the haptic alert window, allowing the
 * GPS request to stay slow while cruising and become responsive before a manoeuvre.
 */
fun resolveAdaptiveTrailGuidanceGpsIntervalMs(
    state: TrailGuidanceState,
    currentSpeedMps: Float?,
    activityProfile: String,
): Long {
    val cadence = adaptiveTurnByTurnCadence(activityProfile)
    if (!state.active || state.mode != TrailGuidanceMode.FOLLOW_ROUTE || state.offRoute) {
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

/** Uses the watch's normal foreground defaults while interactive and its adaptive policy asleep. */
fun resolveTrailGuidanceGpsIntervalMs(
    screenOn: Boolean,
    state: TrailGuidanceState,
    currentSpeedMps: Float?,
    activityProfile: String,
): Long =
    if (screenOn) {
        if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
            FOREGROUND_BIKE_GUIDANCE_GPS_INTERVAL_MS
        } else {
            FOREGROUND_HIKE_GUIDANCE_GPS_INTERVAL_MS
        }
    } else {
        resolveAdaptiveTrailGuidanceGpsIntervalMs(state, currentSpeedMps, activityProfile)
    }

const val FOREGROUND_HIKE_GUIDANCE_GPS_INTERVAL_MS = 3_000L
const val FOREGROUND_BIKE_GUIDANCE_GPS_INTERVAL_MS = 1_000L

private data class AdaptiveTurnByTurnCadence(
    val cruiseIntervalMs: Long,
    val approachIntervalMs: Long,
    val nearIntervalMs: Long,
    val approachWindowSeconds: Double,
    val nearWindowSeconds: Double,
)

private fun adaptiveTurnByTurnCadence(activityProfile: String): AdaptiveTurnByTurnCadence =
    if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
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

private fun TrailGuidanceState.nextActionableTurnDistanceMeters(): Double? =
    listOf(
        nextInstruction?.command to distanceToInstructionMeters,
        followingInstruction?.command to distanceToFollowingInstructionMeters,
    ).mapNotNull { (command, distanceMeters) ->
        distanceMeters
            ?.takeIf(Double::isFinite)
            ?.takeIf { it >= 0.0 && command.isActionableTurn() }
    }.minOrNull()

private fun TrailGuidanceCommand?.isActionableTurn(): Boolean =
    this != null &&
        this != TrailGuidanceCommand.CONTINUE &&
        this != TrailGuidanceCommand.FINISH

private fun conservativeGuidanceSpeedMps(
    currentSpeedMps: Float?,
    activityProfile: String,
): Double {
    val observedSpeedMps = currentSpeedMps?.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 0.0
    val conservativeProfileSpeedMps =
        if (activityProfile == GUIDANCE_ACTIVITY_PROFILE_BIKE) {
            ADAPTIVE_TBT_BIKE_CONSERVATIVE_SPEED_MPS
        } else {
            ADAPTIVE_TBT_HIKE_CONSERVATIVE_SPEED_MPS
        }
    return maxOf(observedSpeedMps, conservativeProfileSpeedMps)
}

const val ADAPTIVE_TBT_CRUISE_INTERVAL_MS = 10_000L
const val ADAPTIVE_TBT_APPROACH_INTERVAL_MS = 5_000L
const val ADAPTIVE_TBT_NEAR_INTERVAL_MS = 3_000L
const val BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS = 5_000L
const val BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS = 3_000L
const val BIKE_ADAPTIVE_TBT_NEAR_INTERVAL_MS = 1_000L

private const val ADAPTIVE_TBT_APPROACH_WINDOW_SECONDS = 60.0
private const val ADAPTIVE_TBT_NEAR_WINDOW_SECONDS = 20.0
private const val BIKE_ADAPTIVE_TBT_APPROACH_WINDOW_SECONDS = 30.0
private const val BIKE_ADAPTIVE_TBT_NEAR_WINDOW_SECONDS = 8.0
private const val ADAPTIVE_TBT_HIKE_CONSERVATIVE_SPEED_MPS = 2.5
private const val ADAPTIVE_TBT_BIKE_CONSERVATIVE_SPEED_MPS = 10.0
