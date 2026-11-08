package com.glancemap.glancemapwearos.core.maps.theme

import android.content.res.AssetManager
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog

object BundledRenderThemeAssetLocator {
    private data class ThemeSpec(
        val generatedRoot: String,
        val xmlFileName: String,
        val legacyXmlPath: String? = null,
        val legacyResourceRoot: String? = null,
    )

    private val specs: Map<String, ThemeSpec> =
        mapOf(
            MapsforgeThemeCatalog.ELEVATE_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/elevate",
                    xmlFileName = "Elevate.xml",
                    legacyXmlPath = "Elevate.xml",
                    legacyResourceRoot = "ele-res",
                ),
            MapsforgeThemeCatalog.ELEVATE_WINTER_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/elevate-winter",
                    xmlFileName = "Elevate.xml",
                ),
            MapsforgeThemeCatalog.ELEVATE_WINTER_WHITE_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/elevate-winter-white",
                    xmlFileName = "Elevate.xml",
                ),
            MapsforgeThemeCatalog.OPENHIKING_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/openhiking",
                    xmlFileName = "OpenHiking.xml",
                ),
            MapsforgeThemeCatalog.FRENCH_KISS_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/frenchkiss",
                    xmlFileName = "frenchkiss.xml",
                ),
            MapsforgeThemeCatalog.TIRAMISU_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/tiramisu",
                    xmlFileName = "Tiramisu.xml",
                ),
            MapsforgeThemeCatalog.HIKE_RIDE_SIGHT_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/hike-ride-sight",
                    xmlFileName = "HikeRideSight.xml",
                ),
            MapsforgeThemeCatalog.VOLUNTARY_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/voluntary",
                    xmlFileName = "Voluntary V5.xml",
                ),
            MapsforgeThemeCatalog.OS_MAP_DAY_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/os-map",
                    xmlFileName = "OS Map V4 Day.xml",
                ),
            MapsforgeThemeCatalog.OS_MAP_NIGHT_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/os-map",
                    xmlFileName = "OS Map V4 Night.xml",
                ),
            MapsforgeThemeCatalog.HIKING_3D_THEME_ID to
                ThemeSpec(
                    generatedRoot = "theme/hiking-3d",
                    xmlFileName = "hiking-3d.xml",
                ),
        )

    fun isThemeAvailable(
        assets: AssetManager,
        themeId: String,
    ): Boolean {
        val normalizedThemeId = resolveKnownThemeIdOrNull(themeId) ?: return false
        val spec = specs[normalizedThemeId] ?: return false
        val xmlPath = resolveThemeXmlPath(assets, normalizedThemeId)
        if (!assetExists(assets, xmlPath)) return false

        val generatedRoot = spec.generatedRoot
        if (assetExists(assets, "$generatedRoot/${spec.xmlFileName}")) {
            return directoryLooksPresent(assets, generatedRoot)
        }

        val legacyResourceRoot = spec.legacyResourceRoot
        return legacyResourceRoot == null || directoryLooksPresent(assets, legacyResourceRoot)
    }

    fun resolveThemeXmlPath(
        assets: AssetManager,
        themeId: String = MapsforgeThemeCatalog.ELEVATE_THEME_ID,
    ): String {
        val normalizedThemeId = requireKnownThemeId(themeId)
        val spec = specs.getValue(normalizedThemeId)
        val generatedPath = "${spec.generatedRoot}/${spec.xmlFileName}"
        if (assetExists(assets, generatedPath)) return generatedPath
        return spec.legacyXmlPath ?: generatedPath
    }

    fun resolveThemeRootPath(
        assets: AssetManager,
        themeId: String = MapsforgeThemeCatalog.ELEVATE_THEME_ID,
    ): String? {
        val normalizedThemeId = requireKnownThemeId(themeId)
        val spec = specs.getValue(normalizedThemeId)
        if (directoryLooksPresent(assets, spec.generatedRoot)) return spec.generatedRoot

        val legacyXmlPath = spec.legacyXmlPath ?: return null
        return legacyXmlPath
            .substringBeforeLast('/', "")
            .takeIf { it.isNotEmpty() }
    }

    internal fun resolveKnownThemeIdOrNull(themeId: String?): String? =
        when (themeId?.trim()) {
            null,
            "",
            MapsforgeThemeCatalog.ELEVATE_THEME_ID,
            -> MapsforgeThemeCatalog.ELEVATE_THEME_ID
            MapsforgeThemeCatalog.ELEVATE_WINTER_THEME_ID -> MapsforgeThemeCatalog.ELEVATE_WINTER_THEME_ID
            MapsforgeThemeCatalog.ELEVATE_WINTER_WHITE_THEME_ID -> MapsforgeThemeCatalog.ELEVATE_WINTER_WHITE_THEME_ID
            MapsforgeThemeCatalog.OPENHIKING_THEME_ID -> MapsforgeThemeCatalog.OPENHIKING_THEME_ID
            MapsforgeThemeCatalog.FRENCH_KISS_THEME_ID -> MapsforgeThemeCatalog.FRENCH_KISS_THEME_ID
            MapsforgeThemeCatalog.TIRAMISU_THEME_ID -> MapsforgeThemeCatalog.TIRAMISU_THEME_ID
            MapsforgeThemeCatalog.HIKE_RIDE_SIGHT_THEME_ID -> MapsforgeThemeCatalog.HIKE_RIDE_SIGHT_THEME_ID
            MapsforgeThemeCatalog.VOLUNTARY_THEME_ID -> MapsforgeThemeCatalog.VOLUNTARY_THEME_ID
            MapsforgeThemeCatalog.OS_MAP_DAY_THEME_ID -> MapsforgeThemeCatalog.OS_MAP_DAY_THEME_ID
            MapsforgeThemeCatalog.OS_MAP_NIGHT_THEME_ID -> MapsforgeThemeCatalog.OS_MAP_NIGHT_THEME_ID
            MapsforgeThemeCatalog.HIKING_3D_THEME_ID -> MapsforgeThemeCatalog.HIKING_3D_THEME_ID
            else -> null
        }

    private fun requireKnownThemeId(themeId: String?): String =
        requireNotNull(resolveKnownThemeIdOrNull(themeId)) {
            "Unknown bundled theme id: $themeId"
        }

    private fun assetExists(
        assets: AssetManager,
        path: String,
    ): Boolean =
        runCatching {
            assets.open(path).use { /* existence check only */ }
        }.isSuccess

    private fun directoryLooksPresent(
        assets: AssetManager,
        path: String,
    ): Boolean =
        runCatching { assets.list(path)?.isNotEmpty() == true }
            .getOrDefault(false)
}
