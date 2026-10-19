package com.glancemap.glancemapwearos.data.repository.maps.theme

import com.glancemap.glancemapwearos.domain.model.maps.theme.RenderThemeSource
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderThemeSourceTest {
    @Test
    fun `legacy bundled selections resolve to the same bundled theme without external metadata`() {
        listOf("elevate", "openhiking", "tiramisu").forEach { id ->
            assertEquals(RenderThemeSource.Bundled(id), selection(id, null).source)
        }
    }

    @Test
    fun `every native Mapsforge variant retains its exact enum name`() {
        MapsforgeThemeCatalog.options.forEach { option ->
            assertEquals(
                RenderThemeSource.MapsforgeBuiltIn(option.themeName),
                selection(MapsforgeThemeCatalog.MAPSFORGE_THEME_ID, option.themeName).source,
            )
        }
    }

    private fun selection(
        id: String,
        nativeName: String?,
    ) = ThemeSelection(
        themeId = id,
        mapsforgeThemeName = nativeName,
        styleId = "__DEFAULT__",
        enabledOverlayLayerIds = emptyList(),
        hillShadingEnabled = true,
        reliefOverlayEnabled = true,
        nightModeEnabled = false,
    )
}
