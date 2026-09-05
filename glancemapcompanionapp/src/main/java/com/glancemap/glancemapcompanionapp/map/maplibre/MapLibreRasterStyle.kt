package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.RasterOnlineMapProvider
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer

private const val ONLINE_RASTER_LAYER_ID = "online-raster"

/** MapLibre-specific translation of a [RasterOnlineMapProvider] into a raster style document. */
internal fun RasterOnlineMapProvider.mapLibreRasterStyleJson(): String =
    """
    {
      "version": 8,
      "sources": {
        "online-raster": {
          "type": "raster",
          "tiles": ["${rasterTileUrlTemplate.jsonEscaped()}"],
          "tileSize": 256,
          "minzoom": $minimumZoom,
          "maxzoom": $maximumZoom,
          "attribution": "${attribution.jsonEscaped()}"
        }
      },
      "layers": [
        {
          "id": "$ONLINE_RASTER_LAYER_ID",
          "type": "raster",
          "source": "$ONLINE_RASTER_LAYER_ID"
        }
      ]
    }
    """.trimIndent()

/** Updates only raster imagery opacity; semantic style layers remain fully legible. */
internal fun Style.setOnlineRasterOpacity(opacity: Float) {
    (getLayer(ONLINE_RASTER_LAYER_ID) as? RasterLayer)?.setProperties(
        rasterOpacity(opacity.coerceIn(0f, 1f)),
    )
}

private fun String.jsonEscaped(): String =
    buildString(length) {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
