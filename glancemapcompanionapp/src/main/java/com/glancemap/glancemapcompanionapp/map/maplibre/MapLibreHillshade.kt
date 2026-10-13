package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.PHONE_ONLINE_DEM_ATTRIBUTION_URL
import com.glancemap.glancemapcompanionapp.map.PHONE_ONLINE_DEM_TILE_SIZE
import com.glancemap.glancemapcompanionapp.map.PHONE_ONLINE_DEM_TILE_URL
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.PropertyFactory.hillshadeAccentColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeExaggeration
import org.maplibre.android.style.layers.PropertyFactory.hillshadeHighlightColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeShadowColor
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet

private const val ONLINE_DEM_SOURCE_ID = "online-terrain-dem"
private const val ONLINE_HILLSHADE_LAYER_ID = "online-terrain-hillshade"

internal fun phoneMapHillshadeOpacity(
    enabled: Boolean,
    opacity: Float,
): Float = if (enabled) phoneMapLibreRasterOpacity(opacity) else 0f

internal fun phoneMapHillshadeColor(
    rgb: String,
    opacity: Float,
): String = "rgba($rgb, ${phoneMapLibreRasterOpacity(opacity)})"

/** Keep terrain immediately above imagery and below every semantic overlay. */
internal fun Style.setOnlineHillshade(
    enabled: Boolean,
    opacity: Float,
) {
    val alpha = phoneMapLibreRasterOpacity(opacity)
    if (!enabled || alpha == 0f) {
        removeLayer(ONLINE_HILLSHADE_LAYER_ID)
        removeSource(ONLINE_DEM_SOURCE_ID)
        return
    }
    if (getSource(ONLINE_DEM_SOURCE_ID) == null) {
        val tileSet =
            TileSet("2.2.0", PHONE_ONLINE_DEM_TILE_URL).apply {
                encoding = "terrarium"
                minZoom = 0f
                maxZoom = 15f
                attribution = "<a href='$PHONE_ONLINE_DEM_ATTRIBUTION_URL'>Mapzen terrain data sources</a>"
            }
        addSource(RasterDemSource(ONLINE_DEM_SOURCE_ID, tileSet, PHONE_ONLINE_DEM_TILE_SIZE))
    }
    val layer =
        getLayer(ONLINE_HILLSHADE_LAYER_ID) as? HillshadeLayer
            ?: HillshadeLayer(ONLINE_HILLSHADE_LAYER_ID, ONLINE_DEM_SOURCE_ID).also {
                addLayerAbove(it, PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID)
            }
    // Hillshade has no opacity property: all terrain colors must follow comparison alpha.
    layer.setProperties(
        hillshadeExaggeration(0.35f),
        hillshadeShadowColor(arrayOf(phoneMapHillshadeColor("0, 0, 0", alpha))),
        hillshadeHighlightColor(arrayOf(phoneMapHillshadeColor("255, 255, 255", alpha))),
        hillshadeAccentColor(phoneMapHillshadeColor("0, 0, 0", 0f)),
    )
}
