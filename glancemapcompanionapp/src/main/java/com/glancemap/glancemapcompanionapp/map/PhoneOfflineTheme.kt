package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
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
    val label: String,
)

internal data class PhoneOfflineTheme(
    val id: String,
    val label: String,
    val styles: List<PhoneOfflineThemeStyle>,
    val defaultStyleId: String,
    val assetRoot: String? = null,
    val xmlFileName: String? = null,
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
    const val DEFAULT_STYLE_ID = "__DEFAULT__"
    const val ELEVATE_THEME_ID = "elevate"
    const val ELEVATE_WINTER_THEME_ID = "elevate_winter"
    const val MAPSFORGE_THEME_ID = "mapsforge"
    const val OPENHIKING_THEME_ID = "openhiking"
    const val FRENCH_KISS_THEME_ID = "frenchkiss"
    const val TIRAMISU_THEME_ID = "tiramisu"
    const val HIKE_RIDE_SIGHT_THEME_ID = "hike_ride_sight"
    const val VOLUNTARY_THEME_ID = "voluntary"
    const val OS_MAP_THEME_ID = "os_map"

    const val ELEVATE_HIKING_STYLE_ID = "elv-hiking"
    const val ELEVATE_CITY_STYLE_ID = "elv-city"
    const val ELEVATE_CYCLING_STYLE_ID = "elv-cycling"
    const val ELEVATE_MTB_STYLE_ID = "elv-mtb"
    const val ELEVATE_WINTER_STYLE_ID = "elv-winter"
    const val ELEVATE_WINTER_WHITE_STYLE_ID = "__WINTER_WHITE__"

    const val MAPSFORGE_DEFAULT_STYLE_ID = "mapsforge:DEFAULT"
    const val MAPSFORGE_OSMARENDER_STYLE_ID = "mapsforge:OSMARENDER"
    const val MAPSFORGE_MOTORIDER_STYLE_ID = "mapsforge:MOTORIDER"
    const val MAPSFORGE_BIKER_STYLE_ID = "mapsforge:BIKER"
    const val MAPSFORGE_DARK_STYLE_ID = "mapsforge:DARK"
    const val MAPSFORGE_INDIGO_STYLE_ID = "mapsforge:INDIGO"

    const val OS_MAP_DAY_STYLE_PREFIX = "__OS_MAP_DAY__:"
    const val OS_MAP_NIGHT_STYLE_PREFIX = "__OS_MAP_NIGHT__:"

    private const val MAPSFORGE_STYLE_PREFIX = "mapsforge:"

    private val elevate =
        PhoneOfflineTheme(
            id = ELEVATE_THEME_ID,
            label = "Elevate",
            styles =
                listOf(
                    PhoneOfflineThemeStyle(ELEVATE_HIKING_STYLE_ID, "Hiking"),
                    PhoneOfflineThemeStyle(ELEVATE_CITY_STYLE_ID, "City"),
                    PhoneOfflineThemeStyle(ELEVATE_CYCLING_STYLE_ID, "Cycling"),
                    PhoneOfflineThemeStyle(ELEVATE_MTB_STYLE_ID, "Mountain bike"),
                ),
            defaultStyleId = ELEVATE_HIKING_STYLE_ID,
            assetRoot = "theme/elevate/",
            xmlFileName = "Elevate.xml",
        )
    private val elevateWinter =
        PhoneOfflineTheme(
            id = ELEVATE_WINTER_THEME_ID,
            label = "Elevate Winter",
            styles =
                listOf(
                    PhoneOfflineThemeStyle(ELEVATE_WINTER_STYLE_ID, "Default"),
                    PhoneOfflineThemeStyle(ELEVATE_WINTER_WHITE_STYLE_ID, "White ski"),
                ),
            defaultStyleId = ELEVATE_WINTER_STYLE_ID,
            assetRoot = "elevate-winter/",
            xmlFileName = "Elevate.xml",
        )
    private val mapsforge =
        PhoneOfflineTheme(
            id = MAPSFORGE_THEME_ID,
            label = "Mapsforge",
            styles =
                listOf(
                    PhoneOfflineThemeStyle(MAPSFORGE_DEFAULT_STYLE_ID, "Classic"),
                    PhoneOfflineThemeStyle(MAPSFORGE_OSMARENDER_STYLE_ID, "OSMARender"),
                    PhoneOfflineThemeStyle(MAPSFORGE_MOTORIDER_STYLE_ID, "Motorider"),
                    PhoneOfflineThemeStyle(MAPSFORGE_BIKER_STYLE_ID, "Biker"),
                    PhoneOfflineThemeStyle(MAPSFORGE_DARK_STYLE_ID, "Dark"),
                    PhoneOfflineThemeStyle(MAPSFORGE_INDIGO_STYLE_ID, "Indigo"),
                ),
            defaultStyleId = MAPSFORGE_DEFAULT_STYLE_ID,
        )

    private val openHiking =
        PhoneOfflineTheme(
            id = OPENHIKING_THEME_ID,
            label = "OpenHiking",
            styles = listOf(PhoneOfflineThemeStyle("topo", "OpenHiking layers")),
            defaultStyleId = "topo",
            assetRoot = "openhiking/",
            xmlFileName = "OpenHiking.xml",
        )
    private val frenchKiss =
        PhoneOfflineTheme(
            id = FRENCH_KISS_THEME_ID,
            label = "French Kiss",
            styles = listOf(PhoneOfflineThemeStyle(DEFAULT_STYLE_ID, "Default")),
            defaultStyleId = DEFAULT_STYLE_ID,
            assetRoot = "frenchkiss/",
            xmlFileName = "frenchkiss.xml",
        )
    private val tiramisu =
        PhoneOfflineTheme(
            id = TIRAMISU_THEME_ID,
            label = "Tiramisu",
            styles =
                listOf(
                    PhoneOfflineThemeStyle("tms_hiking", "Hiking"),
                    PhoneOfflineThemeStyle("tms_mtb", "Mountain biking"),
                    PhoneOfflineThemeStyle("tms_velo", "Velo"),
                ),
            defaultStyleId = "tms_hiking",
            assetRoot = "tiramisu/",
            xmlFileName = "Tiramisu.xml",
        )
    private val hikeRideSight =
        PhoneOfflineTheme(
            id = HIKE_RIDE_SIGHT_THEME_ID,
            label = "Hike, Ride & Sight",
            styles = listOf(PhoneOfflineThemeStyle("Hike, Ride & Sight!", "Hike, Ride & Sight")),
            defaultStyleId = "Hike, Ride & Sight!",
            assetRoot = "hike-ride-sight/",
            xmlFileName = "HikeRideSight.xml",
        )
    private val voluntary =
        PhoneOfflineTheme(
            id = VOLUNTARY_THEME_ID,
            label = "Voluntary",
            styles =
                listOf(
                    PhoneOfflineThemeStyle("vol-hiking", "Hiking & Wintersport"),
                    PhoneOfflineThemeStyle("vol-cycling", "Cycling"),
                    PhoneOfflineThemeStyle("vol-city", "City"),
                    PhoneOfflineThemeStyle("vol-road", "Road"),
                    PhoneOfflineThemeStyle("vol-multi", "Multi"),
                    PhoneOfflineThemeStyle("vol-transparent", "Transparent"),
                    PhoneOfflineThemeStyle("vol-yellow", "Yellow"),
                ),
            defaultStyleId = "vol-multi",
            assetRoot = "voluntary/",
            xmlFileName = "Voluntary V5.xml",
        )
    private val osMap =
        PhoneOfflineTheme(
            id = OS_MAP_THEME_ID,
            label = "OS Map",
            styles =
                listOf(
                    PhoneOfflineThemeStyle("${OS_MAP_DAY_STYLE_PREFIX}os-explorer", "Day · Explorer 1:25k"),
                    PhoneOfflineThemeStyle("${OS_MAP_DAY_STYLE_PREFIX}os-landranger", "Day · Landranger 1:50k"),
                    PhoneOfflineThemeStyle("${OS_MAP_DAY_STYLE_PREFIX}os-dynamic", "Day · Dynamic scale"),
                    PhoneOfflineThemeStyle("${OS_MAP_NIGHT_STYLE_PREFIX}os-explorer", "Night · Explorer 1:25k"),
                    PhoneOfflineThemeStyle("${OS_MAP_NIGHT_STYLE_PREFIX}os-landranger", "Night · Landranger 1:50k"),
                    PhoneOfflineThemeStyle("${OS_MAP_NIGHT_STYLE_PREFIX}os-dynamic", "Night · Dynamic scale"),
                ),
            defaultStyleId = "${OS_MAP_DAY_STYLE_PREFIX}os-landranger",
        )

    val themes: List<PhoneOfflineTheme> =
        listOf(elevate, elevateWinter, hikeRideSight, voluntary, osMap, openHiking, frenchKiss, tiramisu, mapsforge)
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
                theme = buildTheme(resolved, context, onResourceProviderFailure),
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

    private fun buildTheme(
        config: PhoneOfflineThemeConfig,
        context: Context,
        onResourceProviderFailure: () -> Unit,
    ): XmlRenderTheme =
        when (config.themeId) {
            ELEVATE_THEME_ID ->
                assetRenderTheme(
                    context = context,
                    assetRoot = ELEVATE_THEME_ASSET_ROOT,
                    xmlFileName = "Elevate.xml",
                    styleId = config.styleId,
                    onResourceProviderFailure = onResourceProviderFailure,
                )
            ELEVATE_WINTER_THEME_ID ->
                assetRenderTheme(
                    context = context,
                    assetRoot =
                        if (config.styleId == ELEVATE_WINTER_WHITE_STYLE_ID) {
                            "elevate-winter-white/"
                        } else {
                            "elevate-winter/"
                        },
                    xmlFileName = "Elevate.xml",
                    styleId = ELEVATE_WINTER_STYLE_ID,
                    onResourceProviderFailure = onResourceProviderFailure,
                )
            HIKE_RIDE_SIGHT_THEME_ID,
            VOLUNTARY_THEME_ID,
            OPENHIKING_THEME_ID,
            FRENCH_KISS_THEME_ID,
            TIRAMISU_THEME_ID,
            -> {
                val theme = themes.first { it.id == config.themeId }
                assetRenderTheme(
                    context = context,
                    assetRoot = checkNotNull(theme.assetRoot),
                    xmlFileName = checkNotNull(theme.xmlFileName),
                    styleId = config.styleId,
                    onResourceProviderFailure = onResourceProviderFailure,
                )
            }
            OS_MAP_THEME_ID -> {
                val selection = parseOsMapStyleId(config.styleId)
                assetRenderTheme(
                    context = context,
                    assetRoot = "os-map/",
                    xmlFileName =
                        if (selection?.isNight == true) {
                            "OS Map V4 Night.xml"
                        } else {
                            "OS Map V4 Day.xml"
                        },
                    styleId = selection?.realStyleId ?: "os-landranger",
                    onResourceProviderFailure = onResourceProviderFailure,
                )
            }
            MAPSFORGE_THEME_ID -> mapsforgeTheme(config.styleId)
            else -> MapsforgeThemes.DEFAULT
        }

    private fun assetRenderTheme(
        context: Context,
        assetRoot: String,
        xmlFileName: String,
        styleId: String,
        onResourceProviderFailure: () -> Unit,
    ): AssetsRenderTheme =
        AssetsRenderTheme(
            context.assets,
            assetRoot,
            xmlFileName,
            styleMenuCallback(styleId),
        ).apply {
            setResourceProvider(
                PhoneOfflineThemeAssetResourceProvider(
                    assets = context.assets,
                    onOpenFailure = onResourceProviderFailure,
                ),
            )
        }

    private fun mapsforgeTheme(styleId: String): MapsforgeThemes =
        runCatching {
            MapsforgeThemes.valueOf(styleId.removePrefix(MAPSFORGE_STYLE_PREFIX))
        }.getOrDefault(MapsforgeThemes.DEFAULT)

    private data class OsMapStyleSelection(
        val isNight: Boolean,
        val realStyleId: String,
    )

    private fun parseOsMapStyleId(styleId: String): OsMapStyleSelection? =
        when {
            styleId.startsWith(OS_MAP_DAY_STYLE_PREFIX) ->
                OsMapStyleSelection(
                    isNight = false,
                    realStyleId = styleId.removePrefix(OS_MAP_DAY_STYLE_PREFIX),
                )
            styleId.startsWith(OS_MAP_NIGHT_STYLE_PREFIX) ->
                OsMapStyleSelection(
                    isNight = true,
                    realStyleId = styleId.removePrefix(OS_MAP_NIGHT_STYLE_PREFIX),
                )
            else -> null
        }?.takeIf { it.realStyleId.isNotBlank() }

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
