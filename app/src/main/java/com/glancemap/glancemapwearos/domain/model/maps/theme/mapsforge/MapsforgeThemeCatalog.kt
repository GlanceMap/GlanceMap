package com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge

import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

data class MapsforgeThemeOption(
    val id: String,
    val themeName: String,
    val label: String,
)

object MapsforgeThemeCatalog {
    const val MAPSFORGE_THEME_ID = "mapsforge"
    const val ELEVATE_THEME_ID = "elevate"
    const val ELEVATE_WINTER_THEME_ID = "elevate_winter"
    const val ELEVATE_WINTER_WHITE_THEME_ID = "elevate_winter_white"
    const val OPENHIKING_THEME_ID = "openhiking"
    const val FRENCH_KISS_THEME_ID = "frenchkiss"
    const val TIRAMISU_THEME_ID = "tiramisu"
    const val HIKE_RIDE_SIGHT_THEME_ID = "hike_ride_sight"
    const val VOLUNTARY_THEME_ID = "voluntary"
    const val OS_MAP_THEME_ID = "os_map"
    const val OS_MAP_DAY_THEME_ID = "os_map_day"
    const val OS_MAP_NIGHT_THEME_ID = "os_map_night"

    /** Minimal bundled theme tuned for draped 3D terrain; also selectable for the 2D map. */
    const val HIKING_3D_THEME_ID = "hiking_3d"
    private const val MAPSFORGE_THEME_PREFIX = "mapsforge:"
    val LEGACY_HILLSHADING_THEME_ID = "$MAPSFORGE_THEME_PREFIX${MapsforgeThemes.HILLSHADING.name}"
    val DEFAULT_MAPSFORGE_STYLE_ID = "$MAPSFORGE_THEME_PREFIX${MapsforgeThemes.DEFAULT.name}"
    val DEFAULT_MAPSFORGE_THEME_ID = DEFAULT_MAPSFORGE_STYLE_ID
    val DARK_MAPSFORGE_STYLE_ID = "$MAPSFORGE_THEME_PREFIX${MapsforgeThemes.DARK.name}"

    val options: List<MapsforgeThemeOption> =
        listOf(
            MapsforgeThemeOption(
                id = DEFAULT_MAPSFORGE_STYLE_ID,
                themeName = MapsforgeThemes.DEFAULT.name,
                label = "Classic",
            ),
            MapsforgeThemeOption(
                id = "$MAPSFORGE_THEME_PREFIX${MapsforgeThemes.OSMARENDER.name}",
                themeName = MapsforgeThemes.OSMARENDER.name,
                label = "OSMARender",
            ),
            MapsforgeThemeOption(
                id = "$MAPSFORGE_THEME_PREFIX${MapsforgeThemes.MOTORIDER.name}",
                themeName = MapsforgeThemes.MOTORIDER.name,
                label = "Motorider",
            ),
            MapsforgeThemeOption(
                id = "$MAPSFORGE_THEME_PREFIX${MapsforgeThemes.BIKER.name}",
                themeName = MapsforgeThemes.BIKER.name,
                label = "Biker",
            ),
            MapsforgeThemeOption(
                id = DARK_MAPSFORGE_STYLE_ID,
                themeName = MapsforgeThemes.DARK.name,
                label = "Dark",
            ),
            MapsforgeThemeOption(
                id = "$MAPSFORGE_THEME_PREFIX${MapsforgeThemes.INDIGO.name}",
                themeName = MapsforgeThemes.INDIGO.name,
                label = "Indigo",
            ),
        )

    private val byId: Map<String, MapsforgeThemeOption> = options.associateBy { it.id }
    private val validThemeNames = MapsforgeThemes.values().map { it.name }.toSet()

    fun optionById(themeId: String?): MapsforgeThemeOption? = themeId?.let(byId::get)

    fun defaultOption(): MapsforgeThemeOption = byId.getValue(DEFAULT_MAPSFORGE_STYLE_ID)

    fun isValidThemeName(name: String): Boolean = name in validThemeNames

    fun isMapsforgeFamilyTheme(themeId: String?): Boolean = themeId == MAPSFORGE_THEME_ID

    fun isMapsforgeStyleId(themeId: String?): Boolean = optionById(themeId) != null

    fun isElevateFamilyTheme(themeId: String?): Boolean =
        themeId == ELEVATE_THEME_ID ||
            themeId == ELEVATE_WINTER_THEME_ID ||
            themeId == ELEVATE_WINTER_WHITE_THEME_ID

    fun isBundledAssetTheme(themeId: String?): Boolean =
        isElevateFamilyTheme(themeId) ||
            themeId == OPENHIKING_THEME_ID ||
            themeId == FRENCH_KISS_THEME_ID ||
            themeId == TIRAMISU_THEME_ID ||
            themeId == HIKE_RIDE_SIGHT_THEME_ID ||
            themeId == VOLUNTARY_THEME_ID ||
            themeId == OS_MAP_THEME_ID ||
            themeId == OS_MAP_DAY_THEME_ID ||
            themeId == OS_MAP_NIGHT_THEME_ID ||
            themeId == HIKING_3D_THEME_ID
}
