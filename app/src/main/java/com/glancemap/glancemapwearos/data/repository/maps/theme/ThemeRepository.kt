package com.glancemap.glancemapwearos.data.repository.maps.theme

import com.glancemap.glancemapwearos.domain.model.maps.theme.RenderThemeSource
import com.glancemap.glancemapwearos.domain.model.maps.theme.ThemeListItem
import kotlinx.coroutines.flow.Flow

data class ThemeSelection(
    val themeId: String,
    val mapsforgeThemeName: String?,
    val styleId: String,
    val enabledOverlayLayerIds: List<String>,
    // Global map-layer toggle used by renderer DEM hillshading layer.
    val hillShadingEnabled: Boolean,
    // Global map-layer toggle used by renderer DEM color relief overlay.
    val reliefOverlayEnabled: Boolean,
    // Global map appearance override that renders with a dark Mapsforge style.
    val nightModeEnabled: Boolean,
    val source: RenderThemeSource =
        mapsforgeThemeName?.let { RenderThemeSource.MapsforgeBuiltIn(it) } ?: RenderThemeSource.Bundled(themeId),
)

// Selection, terrain toggles, and imported-package management share the existing theme state.
@Suppress("TooManyFunctions")
interface ThemeRepository {
    fun getThemeItems(): Flow<List<ThemeListItem>>

    /**
     * For the renderer:
     * - themeId: selected top-level theme family id.
     * - mapsforgeThemeName: Mapsforge internal enum name when the Mapsforge family is selected.
     * - styleId / overlays: bundled asset-theme controls.
     * - hillShadingEnabled: global DEM hillshading toggle.
     * - reliefOverlayEnabled: global DEM color relief overlay toggle.
     */
    fun getThemeSelection(): Flow<ThemeSelection>

    suspend fun deleteExternalPackage(packageId: String)

    suspend fun onExternalRenderFailure(themeId: String)

    suspend fun setTheme(themeId: String)

    suspend fun setMapStyle(styleId: String)

    suspend fun toggleOverlay(
        styleId: String,
        overlayId: String,
    )

    suspend fun setOverlaysForStyle(
        styleId: String,
        enabledOverlayLayerIds: Set<String>,
    )

    suspend fun setHillShadingEnabled(enabled: Boolean)

    suspend fun setReliefOverlayEnabled(enabled: Boolean)

    suspend fun setNightModeEnabled(enabled: Boolean)

    suspend fun resetToDefaults()
}
