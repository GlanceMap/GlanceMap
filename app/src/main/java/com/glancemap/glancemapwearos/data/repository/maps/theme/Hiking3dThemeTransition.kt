package com.glancemap.glancemapwearos.data.repository.maps.theme

import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog

/** What switching the 3D hiking theme shortcut does: a theme to select, and a theme to remember. */
internal data class Hiking3dThemeTransition(
    val themeToSelect: String?,
    val themeToRemember: String?,
)

/**
 * Turning the shortcut on selects the 3D hiking theme and remembers the current theme, whose
 * per-theme style and overlay presets stay stored. Turning it off restores the remembered theme
 * (or [fallbackThemeId]) only while the 3D hiking theme is still selected; if the user has since
 * picked another theme, that choice is kept.
 */
internal fun hiking3dThemeTransition(
    currentThemeId: String,
    themeBeforeHiking3d: String?,
    enable: Boolean,
    fallbackThemeId: String,
): Hiking3dThemeTransition {
    val hiking3dSelected = currentThemeId == MapsforgeThemeCatalog.HIKING_3D_THEME_ID
    val remembered = themeBeforeHiking3d?.takeIf { it.isNotBlank() && it != MapsforgeThemeCatalog.HIKING_3D_THEME_ID }
    return when {
        enable && hiking3dSelected -> Hiking3dThemeTransition(themeToSelect = null, themeToRemember = remembered)
        enable -> Hiking3dThemeTransition(MapsforgeThemeCatalog.HIKING_3D_THEME_ID, themeToRemember = currentThemeId)
        hiking3dSelected -> Hiking3dThemeTransition(remembered ?: fallbackThemeId, themeToRemember = null)
        else -> Hiking3dThemeTransition(themeToSelect = null, themeToRemember = null)
    }
}
