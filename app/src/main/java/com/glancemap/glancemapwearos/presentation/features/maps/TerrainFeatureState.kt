package com.glancemap.glancemapwearos.presentation.features.maps

import com.glancemap.glancemapwearos.core.maps.DemCoverageSummary

internal data class TerrainFeatureState(
    val terrain3d: Boolean,
    val hill: Boolean,
    val slope: Boolean,
)

internal suspend fun disableTerrainFeatures(
    state: TerrainFeatureState,
    disable3d: suspend () -> Unit,
    disableHill: suspend () -> Unit,
    disableSlope: suspend () -> Unit,
): Boolean {
    if (state.terrain3d) disable3d()
    if (state.hill) disableHill()
    if (state.slope) disableSlope()
    return state.terrain3d || state.hill || state.slope
}

internal suspend fun selectMapWithTerrainGuard(
    path: String?,
    coverage: suspend (String) -> DemCoverageSummary,
    disableFeatures: suspend () -> Boolean,
    select: suspend (String?) -> Unit,
    notifyMissing: (String) -> Unit,
) {
    val summary = path?.let { coverage(it) }
    val noTerrain = summary != null && summary.isCoverageKnown && summary.availableTiles == 0
    val disabled = noTerrain && disableFeatures()
    select(path)
    if (disabled) notifyMissing(path)
}
