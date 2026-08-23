package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapMode
import com.glancemap.trailcore.map.MapRendererCapabilities

/** Replaceable configuration for an online provider; renderer adapters own how it is consumed. */
internal data class OnlineMapProvider(
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
    val onlineProvider: OnlineMapProvider? = null,
) {
    init {
        require((mode == MapMode.ONLINE) == (onlineProvider != null)) {
            "Only an online renderer may define an online provider."
        }
    }
}

/**
 * The single source of renderer/provider choices for the companion.
 *
 * The existing picker remains online and continues to use OpenStreetMap. The full-screen online
 * map can select a topographic provider here without making that provider the global map mode.
 */
internal object PhoneMapRendererCatalog {
    val online =
        PhoneMapRenderer(
            mode = MapMode.ONLINE,
            isAvailable = true,
            capabilities = MapRendererCapabilities(),
            onlineProvider =
                OnlineMapProvider(
                    id = "open_street_map",
                    displayName = "OpenStreetMap",
                    attribution = "© OpenStreetMap contributors",
                    rasterTileUrlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                ),
        )

    val offline =
        PhoneMapRenderer(
            mode = MapMode.OFFLINE,
            isAvailable = false,
            capabilities =
                MapRendererCapabilities(
                    hillshade = true,
                    slopeOverlay = true,
                    contoursToggle = true,
                    themes = true,
                ),
        )

    fun rendererFor(mode: MapMode): PhoneMapRenderer =
        when (mode) {
            MapMode.ONLINE -> online
            MapMode.OFFLINE -> offline
        }

    val onlineProvider: OnlineMapProvider
        get() = checkNotNull(online.onlineProvider)
}
