package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.BuildConfig
import com.glancemap.trailcore.map.MapMode
import com.glancemap.trailcore.map.MapRendererCapabilities

/** The supported internet map datasets, separate from the online/offline renderer choice. */
internal enum class PhoneOnlineMapSource {
    OPEN_TOPO,
    PLAN_IGN_V2,
    OPEN_STREET_MAP,
    SATELLITE,

    ;

    companion object {
        @Suppress("MaxLineLength") // The fallback parser is a small, self-contained preference lookup.
        fun fromStorageValue(value: String?): PhoneOnlineMapSource = entries.firstOrNull { source -> source.name == value } ?: OPEN_TOPO
    }
}

/** Configuration for a raster-tile online provider; renderer adapters own how it is consumed. */
internal data class RasterOnlineMapProvider(
    val id: String,
    val displayName: String,
    val attribution: String,
    val rasterTileUrlTemplate: String,
    val minimumZoom: Int = 0,
    val maximumZoom: Int = 19,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(attribution.isNotBlank())
        require(rasterTileUrlTemplate.isNotBlank())
        require(minimumZoom >= 0 && maximumZoom >= minimumZoom)
    }
}

/** Describes a phone renderer without leaking its SDK details into map state or UI. */
internal data class PhoneMapRenderer(
    val mode: MapMode,
    val isAvailable: Boolean,
    val capabilities: MapRendererCapabilities,
    val rasterOnlineProvider: RasterOnlineMapProvider? = null,
) {
    init {
        require((mode == MapMode.ONLINE) == (rasterOnlineProvider != null)) {
            "Only an online renderer may define an online provider."
        }
    }
}

/** Pure provider factory used by both the catalog and tests without reading BuildConfig. */
internal fun mapTilerSatelliteProvider(apiKey: String): RasterOnlineMapProvider? =
    apiKey
        .takeIf(String::isNotBlank)
        ?.let { configuredKey ->
            RasterOnlineMapProvider(
                id = "maptiler_satellite",
                displayName = "Satellite",
                attribution = "© MapTiler © OpenStreetMap contributors",
                rasterTileUrlTemplate =
                    "https://api.maptiler.com/tiles/satellite-v4/{z}/{x}/{y}.jpg?key=$configuredKey",
                maximumZoom = 22,
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
        RasterOnlineMapProvider(
            id = "open_street_map",
            displayName = "OpenStreetMap",
            attribution = "© OpenStreetMap contributors",
            rasterTileUrlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        )

    val utilityPickerRasterProvider: RasterOnlineMapProvider
        get() = openStreetMapPickerProvider

    val mainOnlineRasterProvider =
        RasterOnlineMapProvider(
            id = "open_topo_map",
            displayName = "OpenTopoMap",
            attribution =
                "© OpenStreetMap contributors, SRTM | Map style: © OpenTopoMap (CC-BY-SA)",
            rasterTileUrlTemplate = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
            maximumZoom = 17,
        )

    private val planIgnV2Provider =
        RasterOnlineMapProvider(
            id = "plan_ign_v2",
            displayName = "Plan IGN V2",
            attribution = "© IGN - Géoplateforme",
            rasterTileUrlTemplate =
                "https://data.geopf.fr/wmts?SERVICE=WMTS&VERSION=1.0.0&REQUEST=GetTile&" +
                    "LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&STYLE=normal&TILEMATRIXSET=PM_0_19&" +
                    "TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/png",
            maximumZoom = 19,
        )

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

    /** Configured providers that can currently render tiles in this build. */
    @Suppress("MaxLineLength") // The availability query remains a single pure catalog operation.
    fun availableOnlineSources(): List<PhoneOnlineMapSource> = supportedOnlineSources().filter(::isOnlineSourceAvailable)

    /** Layer uses the same configured provider catalog as the normal online map. */
    fun availableComparisonOnlineSources(): List<PhoneOnlineMapSource> = availableOnlineSources()

    fun isOnlineSourceAvailable(source: PhoneOnlineMapSource): Boolean = providerForOnlineSource(source) != null

    fun providerForOnlineSource(source: PhoneOnlineMapSource): RasterOnlineMapProvider? =
        when (source) {
            PhoneOnlineMapSource.PLAN_IGN_V2 -> planIgnV2Provider
            PhoneOnlineMapSource.OPEN_TOPO -> mainOnlineRasterProvider
            PhoneOnlineMapSource.OPEN_STREET_MAP -> openStreetMapPickerProvider
            PhoneOnlineMapSource.SATELLITE -> mapTilerSatelliteProvider
        }

    fun onlineSourceLabel(source: PhoneOnlineMapSource): String =
        when (source) {
            PhoneOnlineMapSource.PLAN_IGN_V2 -> planIgnV2Provider.displayName
            PhoneOnlineMapSource.OPEN_TOPO -> mainOnlineRasterProvider.displayName
            PhoneOnlineMapSource.OPEN_STREET_MAP -> openStreetMapPickerProvider.displayName
            PhoneOnlineMapSource.SATELLITE -> "Satellite"
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
