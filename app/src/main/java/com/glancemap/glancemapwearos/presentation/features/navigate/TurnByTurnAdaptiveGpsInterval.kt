package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstruction
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceInstruction
import com.glancemap.trailcore.guidance.TrailGuidanceState
import com.glancemap.trailcore.guidance.resolveAdaptiveTrailGuidanceGpsIntervalMs
import com.glancemap.trailcore.guidance.ADAPTIVE_TBT_APPROACH_INTERVAL_MS as CORE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS
import com.glancemap.trailcore.guidance.ADAPTIVE_TBT_CRUISE_INTERVAL_MS as CORE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS
import com.glancemap.trailcore.guidance.ADAPTIVE_TBT_NEAR_INTERVAL_MS as CORE_ADAPTIVE_TBT_NEAR_INTERVAL_MS
import com.glancemap.trailcore.guidance.BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS as CORE_BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS
import com.glancemap.trailcore.guidance.BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS as CORE_BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS
import com.glancemap.trailcore.guidance.BIKE_ADAPTIVE_TBT_NEAR_INTERVAL_MS as CORE_BIKE_ADAPTIVE_TBT_NEAR_INTERVAL_MS

internal fun resolveAdaptiveTurnByTurnScreenOffIntervalMs(
    state: TurnByTurnGuidanceState,
    currentSpeedMps: Float?,
    activityProfile: String,
): Long = resolveAdaptiveTrailGuidanceGpsIntervalMs(state.toTrailState(), currentSpeedMps, activityProfile)

private fun TurnByTurnGuidanceState.toTrailState(): TrailGuidanceState =
    TrailGuidanceState(
        active = active,
        mode = mode,
        trackTitle = trackTitle,
        nextInstruction = nextInstruction?.toTrailInstruction(),
        distanceToInstructionMeters = distanceToInstructionMeters,
        distanceToStartMeters = distanceToStartMeters,
        bearingToStartDegrees = bearingToStartDegrees,
        distanceToRouteMeters = distanceToRouteMeters,
        bearingToRouteDegrees = bearingToRouteDegrees,
        distanceRemainingMeters = distanceRemainingMeters,
        routeProgressFraction = routeProgressFraction,
        offRoute = offRoute,
        distanceFromStartMeters = distanceFromStartMeters,
        followingInstruction = followingInstruction?.toTrailInstruction(),
        distanceToFollowingInstructionMeters = distanceToFollowingInstructionMeters,
    )

private fun RouteInstruction.toTrailInstruction(): TrailGuidanceInstruction =
    TrailGuidanceInstruction(
        command = command,
        message = message,
        location = GeoPoint(latLong.latitude, latLong.longitude),
        trackPointIndex = trackPointIndex,
        distanceFromStartMeters = distanceFromStartMeters,
        turnAngleDegrees = turnAngleDegrees,
        source = source,
    )

internal const val ADAPTIVE_TBT_CRUISE_INTERVAL_MS = CORE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS
internal const val ADAPTIVE_TBT_APPROACH_INTERVAL_MS = CORE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS
internal const val ADAPTIVE_TBT_NEAR_INTERVAL_MS = CORE_ADAPTIVE_TBT_NEAR_INTERVAL_MS
internal const val BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS = CORE_BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS
internal const val BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS = CORE_BIKE_ADAPTIVE_TBT_APPROACH_INTERVAL_MS
internal const val BIKE_ADAPTIVE_TBT_NEAR_INTERVAL_MS = CORE_BIKE_ADAPTIVE_TBT_NEAR_INTERVAL_MS
