package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

internal enum class TerrainRendererSelection { MAPSFORGE, TERRAIN }

internal fun selectTerrainRenderer(
    enabled: Boolean,
    ready: Boolean,
    failed: Boolean,
): TerrainRendererSelection =
    if (enabled && ready && !failed) {
        TerrainRendererSelection.TERRAIN
    } else {
        TerrainRendererSelection.MAPSFORGE
    }

internal fun useWorldWind(
    eligible: Boolean,
    renderer: String,
): Boolean = eligible && renderer == "worldwind"

internal fun customTerrainMayStart(
    eligible: Boolean,
    renderer: String,
    worldWindRunning: Boolean,
): Boolean = eligible && !useWorldWind(eligible, renderer) && !worldWindRunning

internal fun worldWindMayStart(
    eligible: Boolean,
    renderer: String,
    customViewPresent: Boolean,
    customLoading: Boolean,
): Boolean = useWorldWind(eligible, renderer) && !customViewPresent && !customLoading
