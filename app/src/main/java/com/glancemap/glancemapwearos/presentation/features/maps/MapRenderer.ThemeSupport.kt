package com.glancemap.glancemapwearos.presentation.features.maps

import android.content.Context
import android.util.Log
import com.glancemap.glancemapwearos.core.maps.theme.BundledRenderThemeAssetLocator
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleLayer
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import java.util.Locale

private const val MAP_RENDERER_THEME_TAG = "MapRenderer"

internal fun buildMapRendererThemeOrNull(
    context: Context,
    themeFile: File?,
    mapsforgeThemeName: String?,
    bundledThemeId: String,
): XmlRenderTheme? =
    try {
        if (!mapsforgeThemeName.isNullOrBlank()) {
            val enumName = mapsforgeThemeName.uppercase(Locale.ROOT)
            runCatching { MapsforgeThemes.valueOf(enumName) }.getOrNull()
                ?: run {
                    Log.w(
                        MAP_RENDERER_THEME_TAG,
                        "Unknown Mapsforge theme: $mapsforgeThemeName. Falling back to Elevate.",
                    )
                    null
                }
        } else {
            if (themeFile != null && themeFile.exists()) {
                ExternalRenderTheme(themeFile, mapRendererStyleMenuCategoryCallback())
            } else {
                val bundledThemeXmlPath =
                    BundledRenderThemeAssetLocator.resolveThemeXmlPath(
                        context.assets,
                        bundledThemeId,
                    )
                val bundledThemeAssetDir =
                    bundledThemeXmlPath
                        .substringBeforeLast('/', "")
                        .takeIf { it.isNotBlank() }
                        ?.let { "$it/" }
                        .orEmpty()
                val bundledThemeXmlName = bundledThemeXmlPath.substringAfterLast('/')
                AssetsRenderTheme(
                    context.assets,
                    bundledThemeAssetDir,
                    bundledThemeXmlName,
                    mapRendererStyleMenuCategoryCallback(),
                )
            }
        }
    } catch (e: Exception) {
        Log.e(MAP_RENDERER_THEME_TAG, "Error loading theme", e)
        null
    }

internal fun computeMapRendererThemeSignature(
    file: File?,
    mapsforgeThemeName: String?,
    bundledThemeId: String,
    hillShadingEnabled: Boolean,
): String {
    if (!mapsforgeThemeName.isNullOrBlank()) {
        return "MAPSFORGE:${mapsforgeThemeName.uppercase(Locale.ROOT)}|HILLS:$hillShadingEnabled"
    }
    return if (file == null) {
        "ASSET:${bundledThemeId.uppercase(Locale.ROOT)}|HILLS:$hillShadingEnabled"
    } else {
        "FILE:${mapRendererFileSignature(file)}|THEME:${bundledThemeId.uppercase(Locale.ROOT)}" +
            "|HILLS:$hillShadingEnabled"
    }
}

private fun mapRendererStyleMenuCategoryCallback(): XmlRenderThemeMenuCallback {
    return XmlRenderThemeMenuCallback { menu ->
        val styleLayer =
            menu.getLayer(menu.defaultValue)
                ?: menu.layers.values.firstOrNull { it.isVisible }
                ?: return@XmlRenderThemeMenuCallback emptySet()

        val categories = linkedSetOf<String>()
        categories.addAll(styleLayer.categories)
        addEnabledMapRendererOverlayCategories(styleLayer, categories)
        categories
    }
}

private fun addEnabledMapRendererOverlayCategories(
    layer: XmlRenderThemeStyleLayer,
    categories: MutableSet<String>,
) {
    layer.overlays
        .asSequence()
        .filter { it.isEnabled }
        .forEach { overlay ->
            categories.addAll(overlay.categories)
            addEnabledMapRendererOverlayCategories(overlay, categories)
        }
}
