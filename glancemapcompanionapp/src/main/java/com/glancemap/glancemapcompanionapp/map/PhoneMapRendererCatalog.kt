package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.BuildConfig
import com.glancemap.trailcore.map.MapMode
import com.glancemap.trailcore.map.MapRendererCapabilities

private const val OPEN_STREET_MAP_ATTRIBUTION =
    "<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"

/** The supported internet map datasets, separate from the online/offline renderer choice. */
internal enum class PhoneOnlineMapSource {
    OPEN_TOPO,
    PLAN_IGN_V2,
    OPEN_STREET_MAP,
    CYCLOSM,
    TRACESTRACK_TOPO,
    SATELLITE,
    OPEN_FREE_MAP,
    IGN_ORTHOPHOTO,
    SWISS_TOPO,
    SWISS_IMAGE,
    USGS_TOPO,
    USGS_IMAGERY,
    ESRI_WORLD_IMAGERY,
    MAPTOOLKIT_HIKING,
    MAPTOOLKIT_CYCLING,

    ;

    companion object {
        @Suppress("MaxLineLength") // The fallback parser is a small, self-contained preference lookup.
        fun fromStorageValue(value: String?): PhoneOnlineMapSource = entries.firstOrNull { source -> source.name == value } ?: OPEN_TOPO
    }
}

/** An online dataset supplied as raster tiles or a complete vector style. */
internal data class OnlineMapProvider(
    val id: String,
    val displayName: String,
    val attribution: String,
    val rasterTileUrlTemplate: String = "",
    val styleUrl: String? = null,
    val bounds: List<Double>? = null,
    val minimumZoom: Int = 0,
    val maximumZoom: Int = 19,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(attribution.isNotBlank())
        require(rasterTileUrlTemplate.isNotBlank() xor !styleUrl.isNullOrBlank()) {
            "An online provider must define either raster tiles or a style URL."
        }
        require(bounds == null || (bounds.size == 4 && bounds.all(Double::isFinite)))
        bounds?.let {
            val west = it[0]
            val south = it[1]
            val east = it[2]
            val north = it[3]
            require(west in -180.0..180.0 && east in west..180.0)
            require(south in -90.0..90.0 && north in south..90.0)
        }
        require(minimumZoom >= 0 && maximumZoom >= minimumZoom)
    }
}

/** Describes a phone renderer without leaking its SDK details into map state or UI. */
internal data class PhoneMapRenderer(
    val mode: MapMode,
    val isAvailable: Boolean,
    val capabilities: MapRendererCapabilities,
    val rasterOnlineProvider: OnlineMapProvider? = null,
) {
    init {
        require((mode == MapMode.ONLINE) == (rasterOnlineProvider != null)) {
            "Only an online renderer may define an online provider."
        }
    }
}

/** Pure provider factory used by both the catalog and tests without reading BuildConfig. */
internal fun mapTilerSatelliteProvider(apiKey: String): OnlineMapProvider? =
    apiKey
        .takeIf(String::isNotBlank)
        ?.let { configuredKey ->
            OnlineMapProvider(
                id = "maptiler_satellite",
                displayName = "Satellite",
                attribution =
                    "<a href=\"https://www.maptiler.com/copyright/\">© MapTiler</a> " +
                        OPEN_STREET_MAP_ATTRIBUTION,
                rasterTileUrlTemplate =
                    "https://api.maptiler.com/tiles/satellite-v4/{z}/{x}/{y}.jpg?key=$configuredKey",
                maximumZoom = 22,
            )
        }

internal fun tracestrackTopoProvider(apiKey: String): OnlineMapProvider? =
    apiKey
        .takeIf(String::isNotBlank)
        ?.let { configuredKey ->
            OnlineMapProvider(
                id = "tracestrack_topo",
                displayName = "Tracestrack Topo",
                attribution =
                    "Data: $OPEN_STREET_MAP_ATTRIBUTION, SRTM, GEBCO, SONNY's LiDAR DTM, " +
                        "NASADEM, ESA WorldCover; " +
                        "<a href=\"https://tracestrack.com/\">Maps © Tracestrack</a>",
                rasterTileUrlTemplate =
                    "https://tile.tracestrack.com/topo__/{z}/{x}/{y}.webp?key=$configuredKey",
                maximumZoom = 19,
            )
        }

internal fun effectiveOnlineMapSource(
    preferred: PhoneOnlineMapSource,
    isAvailable: (PhoneOnlineMapSource) -> Boolean,
): PhoneOnlineMapSource = preferred.takeIf(isAvailable) ?: PhoneOnlineMapSource.OPEN_TOPO

/**
 * The single source of renderer/provider choices for the companion.
 *
 * Utility pickers intentionally remain on OpenStreetMap. The future full-screen online map uses
 * its own topographic provider, without making either provider the global map mode.
 */
internal object PhoneMapRendererCatalog {
    private val openStreetMapPickerProvider =
        OnlineMapProvider(
            id = "open_street_map",
            displayName = "OpenStreetMap",
            attribution = OPEN_STREET_MAP_ATTRIBUTION,
            rasterTileUrlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        )

    val utilityPickerRasterProvider: OnlineMapProvider
        get() = openStreetMapPickerProvider

    val mainOnlineRasterProvider =
        OnlineMapProvider(
            id = "open_topo_map",
            displayName = "OpenTopoMap",
            attribution =
                "$OPEN_STREET_MAP_ATTRIBUTION, SRTM | Map style: " +
                    "<a href=\"https://opentopomap.org/about\">© OpenTopoMap</a> (CC-BY-SA)",
            rasterTileUrlTemplate = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
            maximumZoom = 17,
        )

    private val planIgnV2Provider =
        OnlineMapProvider(
            id = "plan_ign_v2",
            displayName = "Plan IGN V2",
            attribution =
                "<a href=\"https://geoservices.ign.fr/\">© IGN - Géoplateforme</a>",
            rasterTileUrlTemplate =
                "https://data.geopf.fr/wmts?SERVICE=WMTS&VERSION=1.0.0&REQUEST=GetTile&" +
                    "LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&STYLE=normal&TILEMATRIXSET=PM_0_19&" +
                    "TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/png",
            maximumZoom = 19,
        )

    private val cyclOsmProvider =
        OnlineMapProvider(
            id = "cyclosm",
            displayName = "CyclOSM",
            attribution =
                "<a href=\"https://www.cyclosm.org/\">CyclOSM</a> | Map data: " +
                    OPEN_STREET_MAP_ATTRIBUTION,
            rasterTileUrlTemplate =
                "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            maximumZoom = 20,
        )

    private val openFreeMapProvider =
        OnlineMapProvider(
            id = "open_free_map",
            displayName = "OpenFreeMap",
            attribution =
                "<a href=\"https://openfreemap.org/\">OpenFreeMap</a> | " +
                    "<a href=\"https://openmaptiles.org/\">© OpenMapTiles</a> | " +
                    OPEN_STREET_MAP_ATTRIBUTION,
            styleUrl = "https://tiles.openfreemap.org/styles/liberty",
        )

    private val maptoolkitHikingProvider =
        OnlineMapProvider(
            id = "maptoolkit_hiking",
            displayName = "Maptoolkit Hiking",
            attribution =
                "<a href=\"https://www.maptoolkit.com/copyright/\">© Maptoolkit</a> " +
                    "<a href=\"https://www.openstreetmap.org/copyright\">© Openstreetmap</a>",
            styleUrl = "https://styles.maptoolkit.org/hiking.json",
        )

    private val maptoolkitCyclingProvider =
        maptoolkitHikingProvider.copy(
            id = "maptoolkit_cycling",
            displayName = "Maptoolkit Cycling",
            styleUrl = "https://styles.maptoolkit.org/cycling.json",
        )

    private val ignOrthophotoProvider =
        OnlineMapProvider(
            id = "ign_orthophoto",
            displayName = "IGN aerial (France)",
            attribution = planIgnV2Provider.attribution,
            rasterTileUrlTemplate =
                "https://data.geopf.fr/wmts?SERVICE=WMTS&VERSION=1.0.0&REQUEST=GetTile&" +
                    "LAYER=ORTHOIMAGERY.ORTHOPHOTOS&STYLE=normal&TILEMATRIXSET=PM_0_19&" +
                    "TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/jpeg",
        )

    private val swissTopoProvider =
        OnlineMapProvider(
            id = "swiss_topo",
            displayName = "swisstopo (Switzerland)",
            attribution = "<a href=\"https://www.swisstopo.admin.ch/\">© swisstopo</a>",
            rasterTileUrlTemplate =
                "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/" +
                    "default/current/3857/{z}/{x}/{y}.jpeg",
            bounds = listOf(5.140242, 45.398181, 11.47757, 48.230651),
            maximumZoom = 18,
        )

    private val swissImageProvider =
        swissTopoProvider.copy(
            id = "swiss_image",
            displayName = "SWISSIMAGE (Switzerland)",
            rasterTileUrlTemplate =
                "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.swissimage/" +
                    "default/current/3857/{z}/{x}/{y}.jpeg",
            maximumZoom = 20,
        )

    private val usgsTopoProvider =
        OnlineMapProvider(
            id = "usgs_topo",
            displayName = "USGS Topo (USA)",
            attribution = "<a href=\"https://www.usgs.gov/the-national-map-data-delivery\">USGS The National Map</a>",
            rasterTileUrlTemplate =
                "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}",
            maximumZoom = 16,
        )

    private val usgsImageryProvider =
        usgsTopoProvider.copy(
            id = "usgs_imagery",
            displayName = "USGS imagery (USA)",
            attribution =
                "<a href=\"https://www.usgs.gov/the-national-map-data-delivery\">USDA / USGS The National Map</a>",
            rasterTileUrlTemplate =
                "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}",
        )

    private val esriWorldImageryProvider =
        OnlineMapProvider(
            id = "esri_world_imagery",
            displayName = "Esri World Imagery",
            attribution =
                "<a href=\"https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9\">" +
                    "Esri, Vantor, Earthstar Geographics, and the GIS User Community</a>",
            rasterTileUrlTemplate =
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        )

    private val tracestrackTopoProvider = tracestrackTopoProvider(BuildConfig.TRACESTRACK_API_KEY)
    private val mapTilerSatelliteProvider = mapTilerSatelliteProvider(BuildConfig.MAPTILER_API_KEY)

    val online =
        PhoneMapRenderer(
            mode = MapMode.ONLINE,
            isAvailable = true,
            capabilities = MapRendererCapabilities(),
            rasterOnlineProvider = mainOnlineRasterProvider,
        )

    /** All online datasets GlanceMap supports, including providers missing local configuration. */
    fun supportedOnlineSources(): List<PhoneOnlineMapSource> = PhoneOnlineMapSource.entries

    /** Configured providers, excluding any the user has hidden from the dropdowns. */
    fun availableOnlineSources(
        hiddenSources: Set<PhoneOnlineMapSource> = emptySet(),
        sourceOrder: List<PhoneOnlineMapSource> = PhoneOnlineMapSource.entries,
    ): List<PhoneOnlineMapSource> =
        normalizePhoneOnlineSourceOrder(sourceOrder)
            .filter { it !in hiddenSources && isOnlineSourceAvailable(it) }

    /** Layer uses the same configured provider catalog and visibility as the normal online map. */
    fun availableComparisonOnlineSources(
        hiddenSources: Set<PhoneOnlineMapSource> = emptySet(),
        sourceOrder: List<PhoneOnlineMapSource> = PhoneOnlineMapSource.entries,
    ): List<PhoneOnlineMapSource> = availableOnlineSources(hiddenSources, sourceOrder)

    fun isOnlineSourceAvailable(source: PhoneOnlineMapSource): Boolean = providerForOnlineSource(source) != null

    private val onlineProviders =
        mapOf(
            PhoneOnlineMapSource.PLAN_IGN_V2 to planIgnV2Provider,
            PhoneOnlineMapSource.OPEN_TOPO to mainOnlineRasterProvider,
            PhoneOnlineMapSource.OPEN_STREET_MAP to openStreetMapPickerProvider,
            PhoneOnlineMapSource.CYCLOSM to cyclOsmProvider,
            PhoneOnlineMapSource.TRACESTRACK_TOPO to tracestrackTopoProvider,
            PhoneOnlineMapSource.SATELLITE to mapTilerSatelliteProvider,
            PhoneOnlineMapSource.OPEN_FREE_MAP to openFreeMapProvider,
            PhoneOnlineMapSource.IGN_ORTHOPHOTO to ignOrthophotoProvider,
            PhoneOnlineMapSource.SWISS_TOPO to swissTopoProvider,
            PhoneOnlineMapSource.SWISS_IMAGE to swissImageProvider,
            PhoneOnlineMapSource.USGS_TOPO to usgsTopoProvider,
            PhoneOnlineMapSource.USGS_IMAGERY to usgsImageryProvider,
            PhoneOnlineMapSource.ESRI_WORLD_IMAGERY to esriWorldImageryProvider,
            PhoneOnlineMapSource.MAPTOOLKIT_HIKING to maptoolkitHikingProvider,
            PhoneOnlineMapSource.MAPTOOLKIT_CYCLING to maptoolkitCyclingProvider,
        )

    fun providerForOnlineSource(source: PhoneOnlineMapSource): OnlineMapProvider? = onlineProviders[source]

    fun onlineSourceLabel(source: PhoneOnlineMapSource): String =
        when (source) {
            PhoneOnlineMapSource.TRACESTRACK_TOPO -> "Tracestrack Topo"
            PhoneOnlineMapSource.SATELLITE -> "Satellite"
            else -> requireNotNull(providerForOnlineSource(source)).displayName
        }

    val offline =
        PhoneMapRenderer(
            mode = MapMode.OFFLINE,
            isAvailable = true,
            capabilities = MapRendererCapabilities(themes = true),
        )

    fun rendererFor(mode: MapMode): PhoneMapRenderer =
        when (mode) {
            MapMode.ONLINE -> online
            MapMode.OFFLINE -> offline
        }
}
