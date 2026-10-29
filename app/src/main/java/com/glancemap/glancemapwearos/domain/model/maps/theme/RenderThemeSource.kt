package com.glancemap.glancemapwearos.domain.model.maps.theme

import java.io.File

sealed interface RenderThemeSource {
    data class Bundled(
        val themeId: String,
    ) : RenderThemeSource

    data class MapsforgeBuiltIn(
        val name: String,
    ) : RenderThemeSource

    data class External(
        val id: String,
        val packageId: String,
        val displayName: String,
        val xmlPath: String,
        val resourceRoot: File,
        val revision: String,
    ) : RenderThemeSource {
        val xmlFile: File get() = File(resourceRoot, xmlPath)
    }
}
