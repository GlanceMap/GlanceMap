package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails

internal data class TerrainGpxOverlay(
    val tracks: List<GpxTrackDetails>,
    val color: Int,
    val colorMode: String,
    val width: Float,
    val opacityPercent: Int,
)
