package com.glancemap.glancemapwearos.presentation.features.maps

import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.domain.model.maps.theme.RenderThemeSource
import java.io.File

internal data class TerrainSource(
    val mapFile: File,
    val themeFile: File?,
    val externalTheme: RenderThemeSource.External?,
    val mapsforgeThemeName: String?,
    val bundledThemeId: String,
    val demSource: DemSource,
    val textScale: Float,
    val hillShadingEnabled: Boolean,
)
