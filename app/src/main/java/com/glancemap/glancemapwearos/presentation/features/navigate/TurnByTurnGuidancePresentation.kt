package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceTerrainDirection
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceTerrainPreview
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import kotlin.math.abs

internal fun guidanceShowsCurrentStraight(state: TurnByTurnGuidanceState): Boolean {
    if (state.mode != GuidanceMode.FOLLOW_ROUTE) return false
    val command = state.nextInstruction?.command ?: return false
    val distanceMeters = state.distanceToInstructionMeters ?: return false
    return command != RouteInstructionCommand.CONTINUE &&
        command != RouteInstructionCommand.FINISH &&
        distanceMeters > MANEUVER_PREPARATION_DISTANCE_METERS
}

internal fun guidanceInstructionPrimaryText(state: TurnByTurnGuidanceState): String =
    if (guidanceShowsCurrentStraight(state)) {
        "Go straight"
    } else {
        state.nextInstruction?.message ?: "Continue"
    }

internal fun guidanceInstructionDistanceText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
): String? =
    state.distanceToInstructionMeters?.let { distanceMeters ->
        if (distanceMeters < MANEUVER_NOW_DISTANCE_METERS) {
            "Now"
        } else {
            formatLiveDistanceLabel(distanceMeters, isMetric)
        }
    }

internal fun guidanceCompactInstructionText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
): String {
    val distanceText = guidanceInstructionDistanceText(state, isMetric)
    val instruction = distanceText ?: guidanceInstructionPrimaryText(state)
    val terrainSymbol =
        when (state.nextSegmentTerrain?.direction) {
            GuidanceTerrainDirection.UPHILL -> "+▲"
            GuidanceTerrainDirection.DOWNHILL -> "−▼"
            GuidanceTerrainDirection.FLAT -> "—"
            null -> null
        }
    return terrainSymbol?.let {
        val compactInstruction = distanceText?.replace(" ", "") ?: instruction
        "$compactInstruction $it"
    } ?: instruction
}

internal data class GuidanceTerrainPopupPresentation(
    val label: String,
    val detail: String,
)

/**
 * While guidance is paused, GPS delivery and route progression are intentionally stopped. Keep
 * rendering the most recent complete state instead of replacing dashboard values with placeholders.
 */
internal fun pausedGuidanceDisplayState(
    currentState: TurnByTurnGuidanceState,
    latestActiveState: TurnByTurnGuidanceState?,
    paused: Boolean,
): TurnByTurnGuidanceState =
    if (paused) {
        latestActiveState ?: currentState
    } else {
        currentState
    }

internal fun guidanceTerrainPopupPresentation(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
): GuidanceTerrainPopupPresentation? {
    val confirmation = state.recentManeuverTerrain
    val upcomingManeuver = state.nextInstruction?.command
    val upcomingWasJustTaken =
        state.distanceFromStartMeters != null &&
            state.nextInstruction != null &&
            state.distanceFromStartMeters > state.nextInstruction.distanceFromStartMeters
    val terrain = confirmation?.terrain ?: state.nextSegmentTerrain ?: return null
    val maneuver = confirmation?.maneuver ?: upcomingManeuver ?: return null
    val labelPrefix =
        if (confirmation != null || upcomingWasJustTaken) {
            "${guidanceManeuverLabel(maneuver)} TAKEN"
        } else {
            "AFTER ${guidanceManeuverLabel(maneuver)}"
        }
    return GuidanceTerrainPopupPresentation(
        label = "$labelPrefix ${guidanceTerrainBadge(terrain)}",
        detail = guidanceTerrainPopupDetail(terrain, isMetric),
    )
}

private fun guidanceManeuverLabel(command: RouteInstructionCommand): String =
    when (command) {
        RouteInstructionCommand.SLIGHT_LEFT,
        RouteInstructionCommand.LEFT,
        RouteInstructionCommand.SHARP_LEFT,
        -> "LEFT"

        RouteInstructionCommand.SLIGHT_RIGHT,
        RouteInstructionCommand.RIGHT,
        RouteInstructionCommand.SHARP_RIGHT,
        -> "RIGHT"

        RouteInstructionCommand.CONTINUE -> "STRAIGHT"
        RouteInstructionCommand.FINISH -> "FINISH"
    }

private fun guidanceTerrainBadge(terrain: GuidanceTerrainPreview): String =
    when (terrain.direction) {
        GuidanceTerrainDirection.UPHILL -> "+▲"
        GuidanceTerrainDirection.DOWNHILL -> "−▼"
        GuidanceTerrainDirection.FLAT -> "—"
    }

private fun guidanceTerrainPopupDetail(
    terrain: GuidanceTerrainPreview,
    isMetric: Boolean,
): String {
    val distance = formatLiveDistanceLabel(terrain.distanceMeters, isMetric)
    return when (terrain.direction) {
        GuidanceTerrainDirection.UPHILL,
        GuidanceTerrainDirection.DOWNHILL,
        -> {
            val (value, unit) = UnitFormatter.formatElevation(abs(terrain.elevationChangeMeters), isMetric)
            val sign = if (terrain.direction == GuidanceTerrainDirection.UPHILL) "+" else "−"
            "$sign$value $unit · $distance"
        }

        GuidanceTerrainDirection.FLAT -> "Flat · $distance"
    }
}

internal const val MANEUVER_PREPARATION_DISTANCE_METERS = 60.0
private const val MANEUVER_NOW_DISTANCE_METERS = 5.0
