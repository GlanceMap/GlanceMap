package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.RasterOnlineMapProvider

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
          "id": "online-raster",
          "type": "raster",
          "source": "online-raster"
        }
      ]
    }
    """.trimIndent()

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
