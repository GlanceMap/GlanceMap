package com.glancemap.glancemapwearos.presentation.features.navigate.activehike

import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot

internal fun TurnByTurnGuidanceState.toActiveHikeSnapshot(
    routeId: String?,
    paused: Boolean,
    pausedRouteTitle: String?,
    recordedAtEpochMillis: Long = System.currentTimeMillis(),
): ActiveHikeSnapshot =
    ActiveHikeSnapshot(
        phase = resolvedActiveHikePhase(paused),
        routeId = routeId?.takeIf(String::isNotBlank),
        routeTitle =
            trackTitle?.takeIf(String::isNotBlank)
                ?: pausedRouteTitle?.takeIf(String::isNotBlank),
        distanceFromStartMeters = distanceFromStartMeters,
        distanceRemainingMeters = distanceRemainingMeters,
        progressFraction = routeProgressFraction?.toDouble(),
        estimatedRemainingSeconds = estimatedRemainingSeconds,
        remainingAscentMeters = remainingAscentMeters,
        remainingDescentMeters = remainingDescentMeters,
        offRoute = offRoute,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )

private fun TurnByTurnGuidanceState.resolvedActiveHikePhase(paused: Boolean): ActiveHikePhase =
    when {
        paused -> ActiveHikePhase.PAUSED
        !active -> ActiveHikePhase.IDLE
        else ->
            when (mode) {
                GuidanceMode.WAITING_FOR_LOCATION -> ActiveHikePhase.WAITING_FOR_LOCATION
                GuidanceMode.TO_START -> ActiveHikePhase.TO_START
                GuidanceMode.FOLLOW_ROUTE -> ActiveHikePhase.FOLLOWING_ROUTE
                GuidanceMode.FINISHED -> ActiveHikePhase.FINISHED
            }
    }
