package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
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

internal fun guidanceInstructionPrimaryText(
    state: TurnByTurnGuidanceState,
    resources: Resources,
): String =
    if (guidanceShowsCurrentStraight(state)) {
        resources.getString(R.string.navigation_go_straight)
    } else {
        state.nextInstruction?.let { it.message ?: resources.getString(it.messageRes) }
            ?: resources.getString(R.string.common_continue)
    }

internal fun guidanceInstructionDistanceText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
    resources: Resources,
): String? =
    state.distanceToInstructionMeters?.let { distanceMeters ->
        if (distanceMeters < MANEUVER_NOW_DISTANCE_METERS) {
            resources.getString(R.string.navigation_now)
        } else {
            formatLiveDistanceLabel(distanceMeters, isMetric, resources = resources)
        }
    }

internal fun guidanceCompactInstructionText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
    resources: Resources,
): String {
    val distanceText = guidanceInstructionDistanceText(state, isMetric, resources = resources)
    val instruction = distanceText ?: guidanceInstructionPrimaryText(state, resources = resources)
    val terrainSymbol =
        when (state.nextSegmentTerrain?.direction) {
            GuidanceTerrainDirection.UPHILL -> "+▲"
            GuidanceTerrainDirection.DOWNHILL -> "−▼"
            GuidanceTerrainDirection.FLAT -> "—"
            null -> null
        }
    return terrainSymbol?.let {
        val compactInstruction = distanceText?.replace(" ", "") ?: instruction
        resources.getString(R.string.navigation_instruction_terrain, compactInstruction, it)
    } ?: instruction
}

internal data class GuidanceTerrainPopupPresentation(
    val direction: GuidanceTerrainDirection,
    val expandedText: String,
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
    resources: Resources,
): GuidanceTerrainPopupPresentation? {
    val terrain = state.recentManeuverTerrain?.terrain ?: state.nextSegmentTerrain ?: return null
    return GuidanceTerrainPopupPresentation(
        direction = terrain.direction,
        expandedText = guidanceTerrainPopupExpandedText(terrain, isMetric, resources = resources),
    )
}

private fun guidanceTerrainPopupExpandedText(
    terrain: GuidanceTerrainPreview,
    isMetric: Boolean,
    resources: Resources,
): String =
    when (terrain.direction) {
        GuidanceTerrainDirection.UPHILL ->
            resources.getString(
                R.string.navigation_uphill,
                guidanceTerrainElevationText(terrain, isMetric, resources = resources),
            )
        GuidanceTerrainDirection.DOWNHILL ->
            resources.getString(
                R.string.navigation_downhill,
                guidanceTerrainElevationText(terrain, isMetric, resources = resources),
            )
        GuidanceTerrainDirection.FLAT -> resources.getString(R.string.navigation_flat)
    }

private fun guidanceTerrainElevationText(
    terrain: GuidanceTerrainPreview,
    isMetric: Boolean,
    resources: Resources,
): String {
    val (value, unit) = UnitFormatter.formatElevation(abs(terrain.elevationChangeMeters), isMetric)
    val sign = if (terrain.direction == GuidanceTerrainDirection.UPHILL) "+" else "−"
    return resources.getString(R.string.navigation_signed_value_unit, sign, value, unit)
}

internal const val MANEUVER_PREPARATION_DISTANCE_METERS = 60.0
private const val MANEUVER_NOW_DISTANCE_METERS = 5.0
