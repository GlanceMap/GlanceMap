package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.util.Log
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleLayer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.util.Locale

private const val PHONE_OFFLINE_THEME_MENU_TAG = "PhoneOfflineTheme"

internal data class PhoneOfflineThemeOverlay(
    val id: String,
    val label: String,
    val enabledByDefault: Boolean,
)

internal enum class PhoneOfflineThemeOverlayPreset {
    DEFAULT,
    MINIMAL,
    ALL_ON,
}

internal data class PhoneOfflineThemeMenuLayer(
    val id: String,
    val label: String,
    val isStyle: Boolean,
    val enabledByDefault: Boolean,
    val parentLayerId: String?,
    val overlayLayerIds: List<String>,
)

internal data class PhoneOfflineThemeStyleMenu(
    val defaultStyleId: String?,
    val layers: List<PhoneOfflineThemeMenuLayer>,
) {
    fun overlaysFor(styleId: String?): List<PhoneOfflineThemeOverlay> {
        val layersById = layers.associateBy(PhoneOfflineThemeMenuLayer::id)
        val selectedStyle =
            styleId?.let(layersById::get)
                ?: defaultStyleId?.let(layersById::get)
                ?: layers.firstOrNull(PhoneOfflineThemeMenuLayer::isStyle)
                ?: return emptyList()
        val ids = linkedSetOf<String>()

        fun collectOverlays(
            layer: PhoneOfflineThemeMenuLayer,
            visited: MutableSet<String>,
        ) {
            if (!visited.add(layer.id)) return
            layer.parentLayerId?.let { parentId -> layersById[parentId]?.let { collectOverlays(it, visited) } }
            ids.addAll(layer.overlayLayerIds)
            visited.remove(layer.id)
        }

        collectOverlays(selectedStyle, linkedSetOf())
        return ids.mapNotNull { id ->
            layersById[id]
                ?.takeUnless(PhoneOfflineThemeMenuLayer::isStyle)
                ?.let { layer -> PhoneOfflineThemeOverlay(layer.id, layer.label, layer.enabledByDefault) }
        }
    }
}

internal object PhoneOfflineThemeOverlayCatalog {
    private val parsedStyleMenus = mutableMapOf<String, PhoneOfflineThemeStyleMenu>()
    private val minimalLayerIdParts =
        listOf(
            "routes",
            "waymarks",
            "winter_reference",
            "winter_symbol",
            "skipiste",
            "skitour",
            "skiloipe",
            "schneeschuh",
            "rodeln",
            "hundeschlitten",
            "eislaufen",
            "schneepark",
        )
    private val overlayGroupParts =
        linkedMapOf(
            "winter" to
                listOf(
                    "skipiste", "ski", "skitour", "skiloipe", "schneeschuh", "rodeln", "hundeschlitten",
                    "eislaufen", "schneepark", "winter_",
                ),
            "routes" to
                listOf(
                    "routes", "hiking", "cycling", "mtb", "guidepost", "placenames", "roadnames", "roadnumbers",
                    "elevations", "topography", "tracks", "waymarks", "pt_network", "reference", "symbol", "namen",
                    "tms_hknw", "tms_cynw", "tms_mtbnw", "tms_paths", "tms_tracks",
                ),
            "poi" to
                listOf(
                    "amenities", "accommodation", "restaurants", "shops", "publictrans", "camping", "hotel", "hut",
                    "health", "education", "eating", "fun", "urban_equipment", "nature_equipment", "infrastructure",
                    "tourism", "sports", "emergency", "pubtrans", "car", "barriers", "buildings", "shop", "tms_huts",
                    "tms_acco", "tms_picnic", "tms_tourism", "tms_trans-priv", "tms_trans-pub", "tms_culture",
                    "tms_freetime", "tms_food", "tms_shops", "tms_religion", "tms_emergency",
                ),
        )

    fun overlaysFor(
        config: PhoneOfflineThemeConfig,
        context: Context,
    ): List<PhoneOfflineThemeOverlay> {
        val assetPath = styleMenuAssetPath(config) ?: return emptyList()
        val cacheKey = "$assetPath:${Locale.getDefault().toLanguageTag()}"
        return runCatching {
            val menu =
                synchronized(parsedStyleMenus) {
                    parsedStyleMenus[cacheKey]
                        ?: context.assets.open(assetPath).bufferedReader().use(::parsePhoneOfflineThemeStyleMenu)
                            .also { parsedStyleMenus[cacheKey] = it }
                }
            menu.overlaysFor(styleMenuStyleId(config, menu))
        }.onFailure {
            Log.w(PHONE_OFFLINE_THEME_MENU_TAG, "Unable to read overlay choices for ${config.themeId}.")
        }.getOrDefault(emptyList())
    }

    fun idsForPreset(
        overlays: List<PhoneOfflineThemeOverlay>,
        preset: PhoneOfflineThemeOverlayPreset,
    ): Set<String> =
        when (preset) {
            PhoneOfflineThemeOverlayPreset.DEFAULT -> overlays.filter(PhoneOfflineThemeOverlay::enabledByDefault)
            PhoneOfflineThemeOverlayPreset.MINIMAL -> overlays.filter { isMinimalOverlay(it.id) }
            PhoneOfflineThemeOverlayPreset.ALL_ON -> overlays
        }.mapTo(linkedSetOf(), PhoneOfflineThemeOverlay::id)

    fun groupIdForOverlay(layerId: String): String {
        val id = layerId.lowercase(Locale.ROOT)
        return overlayGroupParts.entries.firstOrNull { (_, parts) -> parts.any(id::contains) }?.key ?: "map"
    }

    private fun styleMenuAssetPath(config: PhoneOfflineThemeConfig): String? =
        when (config.themeId) {
            PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID -> null
            PhoneOfflineThemeCatalog.OS_MAP_THEME_ID -> {
                val night = config.styleId.startsWith(PhoneOfflineThemeCatalog.OS_MAP_NIGHT_STYLE_PREFIX)
                "os-map/OS Map V4 ${if (night) "Night" else "Day"}.xml"
            }
            else -> {
                val theme = PhoneOfflineThemeCatalog.themeFor(config.themeId)
                val root = themeAssetRoot(config, theme)
                root?.let { assetRoot -> theme.xmlFileName?.let { file -> "$assetRoot$file" } }
            }
        }

    private fun themeAssetRoot(
        config: PhoneOfflineThemeConfig,
        theme: PhoneOfflineTheme,
    ): String? =
        if (config.themeId != PhoneOfflineThemeCatalog.ELEVATE_WINTER_THEME_ID) {
            theme.assetRoot
        } else if (config.styleId == PhoneOfflineThemeCatalog.ELEVATE_WINTER_WHITE_STYLE_ID) {
            "elevate-winter-white/"
        } else {
            "elevate-winter/"
        }

    private fun styleMenuStyleId(
        config: PhoneOfflineThemeConfig,
        menu: PhoneOfflineThemeStyleMenu,
    ): String? =
        when {
            config.themeId == PhoneOfflineThemeCatalog.ELEVATE_WINTER_THEME_ID ->
                PhoneOfflineThemeCatalog.ELEVATE_WINTER_STYLE_ID
            config.themeId == PhoneOfflineThemeCatalog.OS_MAP_THEME_ID ->
                config.styleId
                    .removePrefix(PhoneOfflineThemeCatalog.OS_MAP_NIGHT_STYLE_PREFIX)
                    .removePrefix(PhoneOfflineThemeCatalog.OS_MAP_DAY_STYLE_PREFIX)
            config.styleId == PhoneOfflineThemeCatalog.DEFAULT_STYLE_ID -> menu.defaultStyleId
            else -> config.styleId
        }

    private fun isMinimalOverlay(layerId: String): Boolean {
        val id = layerId.lowercase(Locale.ROOT)
        return minimalLayerIdParts.any(id::contains)
    }
}

internal fun parsePhoneOfflineThemeStyleMenu(reader: Reader): PhoneOfflineThemeStyleMenu {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(reader)
    var styleMenuDepth = -1
    var defaultStyleId: String? = null
    val layers = mutableListOf<PhoneOfflineThemeMenuLayer>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG ->
                when (parser.name) {
                    "stylemenu" -> {
                        styleMenuDepth = parser.depth
                        defaultStyleId = parser.getAttributeValue(null, "defaultvalue")
                    }
                    "layer" -> {
                        if (parser.depth == styleMenuDepth + 1) {
                            layers += parsePhoneOfflineThemeMenuLayer(parser)
                        }
                    }
                }
            XmlPullParser.END_TAG -> {
                if (parser.name == "stylemenu" && parser.depth == styleMenuDepth) break
            }
            else -> Unit
        }
    }
    return PhoneOfflineThemeStyleMenu(defaultStyleId, layers)
}

private fun parsePhoneOfflineThemeMenuLayer(parser: XmlPullParser): PhoneOfflineThemeMenuLayer {
    val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
    val isStyle = parser.getAttributeValue(null, "visible") == "true"
    val enabledByDefault = parser.getAttributeValue(null, "enabled") == "true"
    val parentLayerId = parser.getAttributeValue(null, "parent")?.trim()?.takeIf(String::isNotEmpty)
    val content = readPhoneOfflineThemeMenuLayerContent(parser)
    val locale = Locale.getDefault()
    val label =
        content.names[locale.toLanguageTag().lowercase(Locale.ROOT)]
            ?: content.names[locale.language.lowercase(Locale.ROOT)]
            ?: content.names["en"]
            ?: content.names.values.firstOrNull()
            ?: id
    return PhoneOfflineThemeMenuLayer(id, label, isStyle, enabledByDefault, parentLayerId, content.overlayLayerIds)
}

private data class PhoneOfflineThemeMenuLayerContent(
    val names: Map<String, String>,
    val overlayLayerIds: List<String>,
)

private fun readPhoneOfflineThemeMenuLayerContent(parser: XmlPullParser): PhoneOfflineThemeMenuLayerContent {
    val startDepth = parser.depth
    val names = linkedMapOf<String, String>()
    val overlayIds = mutableListOf<String>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (
            parser.eventType == XmlPullParser.END_TAG &&
            parser.name == "layer" &&
            parser.depth == startDepth
        ) {
            break
        }
        if (parser.eventType == XmlPullParser.START_TAG) {
            when (parser.name) {
                "name" -> addPhoneOfflineThemeName(parser, names)
                "overlay" -> addPhoneOfflineThemeOverlay(parser, overlayIds)
            }
        }
    }
    return PhoneOfflineThemeMenuLayerContent(names, overlayIds.distinct())
}

private fun addPhoneOfflineThemeName(
    parser: XmlPullParser,
    names: MutableMap<String, String>,
) {
    val value = parser.getAttributeValue(null, "value")?.trim().orEmpty()
    if (value.isEmpty()) return
    val language = parser.getAttributeValue(null, "lang")?.lowercase(Locale.ROOT).orEmpty()
    names.putIfAbsent(language, value)
}

private fun addPhoneOfflineThemeOverlay(
    parser: XmlPullParser,
    overlayIds: MutableList<String>,
) {
    parser.getAttributeValue(null, "id")?.trim()?.takeIf(String::isNotEmpty)?.let(overlayIds::add)
}

internal fun enabledPhoneOfflineThemeCategories(
    style: XmlRenderThemeStyleLayer,
    enabledOverlayLayerIds: Set<String>?,
): Set<String> = buildSet {
    fun addLayer(layer: XmlRenderThemeStyleLayer) {
        addAll(layer.categories)
        layer.overlays
            .filter { overlay -> enabledOverlayLayerIds?.let { overlay.id in it } ?: overlay.isEnabled }
            .forEach(::addLayer)
    }
    addLayer(style)
}
