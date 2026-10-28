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
