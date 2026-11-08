package com.glancemap.glancemapwearos.data.repository.maps.theme

import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog.ELEVATE_THEME_ID
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog.HIKING_3D_THEME_ID
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog.OPENHIKING_THEME_ID
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog.TIRAMISU_THEME_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class Hiking3dThemeTransitionTest {
    private fun transition(
        currentThemeId: String,
        themeBeforeHiking3d: String?,
        enable: Boolean,
    ) = hiking3dThemeTransition(currentThemeId, themeBeforeHiking3d, enable, fallbackThemeId = ELEVATE_THEME_ID)

    @Test
    fun `turning on selects the 3D theme and remembers the current theme`() {
        assertEquals(
            Hiking3dThemeTransition(HIKING_3D_THEME_ID, OPENHIKING_THEME_ID),
            transition(OPENHIKING_THEME_ID, null, enable = true),
        )
    }

    @Test
    fun `turning off restores the remembered theme`() {
        assertEquals(
            Hiking3dThemeTransition(OPENHIKING_THEME_ID, null),
            transition(HIKING_3D_THEME_ID, OPENHIKING_THEME_ID, enable = false),
        )
    }

    @Test
    fun `turning off without a remembered theme falls back to the default`() {
        assertEquals(
            Hiking3dThemeTransition(ELEVATE_THEME_ID, null),
            transition(HIKING_3D_THEME_ID, null, enable = false),
        )
    }

    @Test
    fun `turning off keeps a theme the user picked meanwhile`() {
        assertEquals(
            Hiking3dThemeTransition(null, null),
            transition(TIRAMISU_THEME_ID, OPENHIKING_THEME_ID, enable = false),
        )
    }

    @Test
    fun `turning on again while selected keeps the remembered theme`() {
        assertEquals(
            Hiking3dThemeTransition(null, OPENHIKING_THEME_ID),
            transition(HIKING_3D_THEME_ID, OPENHIKING_THEME_ID, enable = true),
        )
    }
}
