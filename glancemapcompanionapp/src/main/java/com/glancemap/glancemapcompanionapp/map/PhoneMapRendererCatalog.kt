package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapMode
import com.glancemap.trailcore.map.MapRendererCapabilities

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

    val online =
        PhoneMapRenderer(
            mode = MapMode.ONLINE,
            isAvailable = true,
            capabilities = MapRendererCapabilities(),
            rasterOnlineProvider = mainOnlineRasterProvider,
        )

    val offline =
        PhoneMapRenderer(
            mode = MapMode.OFFLINE,
            isAvailable = true,
            capabilities = MapRendererCapabilities(),
        )

    fun rendererFor(mode: MapMode): PhoneMapRenderer =
        when (mode) {
            MapMode.ONLINE -> online
            MapMode.OFFLINE -> offline
        }
}
