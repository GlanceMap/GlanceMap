package com.glancemap.glancemapwearos.presentation.features.maps

import android.content.Context
import android.util.Log
import com.glancemap.glancemapwearos.core.maps.theme.BundledRenderThemeAssetLocator
import com.glancemap.glancemapwearos.core.maps.theme.defaultRenderThemeMenuCallback
import com.glancemap.glancemapwearos.core.maps.theme.externalRenderTheme
import com.glancemap.glancemapwearos.domain.model.maps.theme.RenderThemeSource
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import java.util.Locale

private const val MAP_RENDERER_THEME_TAG = "MapRenderer"

// Preserve the existing fallback branches for bundled and native themes.
@Suppress("NestedBlockDepth")
internal fun buildMapRendererThemeOrNull(
    context: Context,
    themeFile: File?,
    mapsforgeThemeName: String?,
    bundledThemeId: String,
    externalTheme: RenderThemeSource.External? = null,
): XmlRenderTheme? =
    try {
        if (externalTheme != null) {
            externalRenderTheme(externalTheme)
        } else if (!mapsforgeThemeName.isNullOrBlank()) {
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
                ExternalRenderTheme(themeFile, defaultRenderThemeMenuCallback())
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
                    defaultRenderThemeMenuCallback(),
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
