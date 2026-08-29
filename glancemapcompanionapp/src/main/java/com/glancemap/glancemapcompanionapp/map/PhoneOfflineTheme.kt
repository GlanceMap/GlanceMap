package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.annotation.StringRes
import com.glancemap.glancemapcompanionapp.R
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleLayer
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val PHONE_OFFLINE_THEME_TAG = "PhoneOfflineTheme"
private const val ELEVATE_THEME_ASSET_ROOT = "theme/elevate/"
private const val FILE_RESOURCE_PREFIX = "file:"

/** Stable phone-side selection; renderer SDK objects stay outside this semantic state. */
internal data class PhoneOfflineThemeConfig(
    val themeId: String,
    val styleId: String,
)

internal data class PhoneOfflineThemeStyle(
    val id: String,
    @StringRes val labelRes: Int,
)

internal data class PhoneOfflineTheme(
    val id: String,
    @StringRes val labelRes: Int,
    val styles: List<PhoneOfflineThemeStyle>,
    val defaultStyleId: String,
)

internal data class PhoneOfflineRenderTheme(
    val theme: XmlRenderTheme,
    val fallbackUsed: Boolean,
)

/**
 * The small phone catalog intentionally mirrors only stable IDs needed by the first offline UI.
 * Elevate style IDs are the existing Mapsforge XML stylemenu layer IDs used on Wear.
 */
internal object PhoneOfflineThemeCatalog {
    const val ELEVATE_THEME_ID = "elevate"
    const val MAPSFORGE_THEME_ID = "mapsforge"

    const val ELEVATE_HIKING_STYLE_ID = "elv-hiking"
    const val ELEVATE_CYCLING_STYLE_ID = "elv-cycling"
    const val ELEVATE_MTB_STYLE_ID = "elv-mtb"

    const val MAPSFORGE_DEFAULT_STYLE_ID = "mapsforge:DEFAULT"
    const val MAPSFORGE_DARK_STYLE_ID = "mapsforge:DARK"

    private const val MAPSFORGE_STYLE_PREFIX = "mapsforge:"

    private val elevate =
        PhoneOfflineTheme(
            id = ELEVATE_THEME_ID,
            labelRes = R.string.map_theme_elevate,
            styles =
                listOf(
                    PhoneOfflineThemeStyle(ELEVATE_HIKING_STYLE_ID, R.string.map_theme_style_hiking),
                    PhoneOfflineThemeStyle(ELEVATE_CYCLING_STYLE_ID, R.string.map_theme_style_cycling),
                    PhoneOfflineThemeStyle(ELEVATE_MTB_STYLE_ID, R.string.map_theme_style_mountain_biking),
                ),
            defaultStyleId = ELEVATE_HIKING_STYLE_ID,
        )
    private val mapsforge =
        PhoneOfflineTheme(
            id = MAPSFORGE_THEME_ID,
            labelRes = R.string.map_theme_mapsforge,
            styles =
                listOf(
                    PhoneOfflineThemeStyle(MAPSFORGE_DEFAULT_STYLE_ID, R.string.map_theme_style_classic),
                    PhoneOfflineThemeStyle(MAPSFORGE_DARK_STYLE_ID, R.string.map_theme_style_dark),
                ),
            defaultStyleId = MAPSFORGE_DEFAULT_STYLE_ID,
        )

    val themes: List<PhoneOfflineTheme> = listOf(elevate, mapsforge)
    val defaultConfig = PhoneOfflineThemeConfig(ELEVATE_THEME_ID, ELEVATE_HIKING_STYLE_ID)

    fun resolve(
        themeId: String?,
        styleId: String?,
    ): PhoneOfflineThemeConfig {
        val theme = themes.firstOrNull { it.id == themeId } ?: elevate
        val style = theme.styles.firstOrNull { it.id == styleId }?.id ?: theme.defaultStyleId
        return PhoneOfflineThemeConfig(theme.id, style)
    }

    fun themeFor(themeId: String): PhoneOfflineTheme = themes.firstOrNull { it.id == themeId } ?: elevate

    fun renderTheme(
        config: PhoneOfflineThemeConfig,
        context: Context,
        onResourceProviderFailure: () -> Unit = {},
    ): PhoneOfflineRenderTheme {
        val resolved = resolve(config.themeId, config.styleId)
        return runCatching {
            PhoneOfflineRenderTheme(
                theme =
                    when (resolved.themeId) {
                        ELEVATE_THEME_ID ->
                            AssetsRenderTheme(
                                context.assets,
                                ELEVATE_THEME_ASSET_ROOT,
                                "Elevate.xml",
                                styleMenuCallback(resolved.styleId),
                            ).apply {
                                setResourceProvider(
                                    PhoneOfflineThemeAssetResourceProvider(
                                        assets = context.assets,
                                        onOpenFailure = onResourceProviderFailure,
                                    ),
                                )
                            }
                        MAPSFORGE_THEME_ID -> mapsforgeTheme(resolved.styleId)
                        else -> MapsforgeThemes.DEFAULT
                    },
                fallbackUsed = false,
            )
        }.onFailure { error ->
            Log.e(
                PHONE_OFFLINE_THEME_TAG,
                "Unable to construct offline theme ${resolved.themeId}/${resolved.styleId}.",
                error,
            )
        }.getOrElse { PhoneOfflineRenderTheme(MapsforgeThemes.DEFAULT, fallbackUsed = true) }
    }

    private fun mapsforgeTheme(styleId: String): MapsforgeThemes =
        runCatching {
            MapsforgeThemes.valueOf(styleId.removePrefix(MAPSFORGE_STYLE_PREFIX))
        }.getOrDefault(MapsforgeThemes.DEFAULT)

    private fun styleMenuCallback(styleId: String): XmlRenderThemeMenuCallback =
        XmlRenderThemeMenuCallback { menu ->
            val style =
                menu.getLayer(styleId)
                    ?: menu.getLayer(menu.defaultValue)
                    ?: menu.layers.values.firstOrNull { it.isVisible }
                    ?: return@XmlRenderThemeMenuCallback emptySet()
            buildSet {
                addEnabledCategories(style, this)
            }
        }

    private fun addEnabledCategories(
        layer: XmlRenderThemeStyleLayer,
        categories: MutableSet<String>,
    ) {
        categories.addAll(layer.categories)
        layer.overlays.filter { it.isEnabled }.forEach { overlay ->
            addEnabledCategories(overlay, categories)
        }
    }
}

/** Mapsforge resolves `file:` resources from disk unless this provider opens bundled assets. */
private class PhoneOfflineThemeAssetResourceProvider(
    private val assets: AssetManager,
    private val onOpenFailure: () -> Unit,
) : XmlThemeResourceProvider {
    private val loggedOpenFailure = AtomicBoolean(false)

    override fun createInputStream(
        relativePathPrefix: String,
        source: String,
    ): InputStream? {
        val assetPath = resolvePhoneOfflineThemeAssetPath(relativePathPrefix, source) ?: return null
        return runCatching { assets.open(assetPath) }
            .onFailure {
                if (loggedOpenFailure.compareAndSet(false, true)) {
                    onOpenFailure()
                    Log.w(
                        PHONE_OFFLINE_THEME_TAG,
                        "Unable to load bundled Elevate resource '$source' from '$assetPath'.",
                    )
                }
            }.getOrNull()
    }
}

internal fun resolvePhoneOfflineThemeAssetPath(
    relativePathPrefix: String,
    source: String,
): String? =
    source
        .takeIf { it.startsWith(FILE_RESOURCE_PREFIX) }
        ?.removePrefix(FILE_RESOURCE_PREFIX)
        ?.trimStart('/')
        ?.takeIf { it.isNotBlank() }
        ?.let { resourcePath -> "${relativePathPrefix.trimEnd('/')}/$resourcePath" }

/** Companion-only persistence for the temporary theme controls and future Maps panel. */
internal class PhoneOfflineThemePreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneOfflineThemeConfig {
        val requested =
            PhoneOfflineThemeConfig(
                themeId = preferences.getString(KEY_THEME_ID, null).orEmpty(),
                styleId = preferences.getString(KEY_STYLE_ID, null).orEmpty(),
            )
        val resolved = PhoneOfflineThemeCatalog.resolve(requested.themeId, requested.styleId)
        if (resolved != requested) save(resolved)
        return resolved
    }

    fun save(config: PhoneOfflineThemeConfig): PhoneOfflineThemeConfig {
        val resolved = PhoneOfflineThemeCatalog.resolve(config.themeId, config.styleId)
        preferences
            .edit()
            .putString(KEY_THEME_ID, resolved.themeId)
            .putString(KEY_STYLE_ID, resolved.styleId)
            .apply()
        return resolved
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_offline_theme"
        const val KEY_THEME_ID = "theme_id"
        const val KEY_STYLE_ID = "style_id"
    }
}
