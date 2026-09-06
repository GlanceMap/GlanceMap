package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.RasterOnlineMapProvider
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer

internal const val PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID = "online-raster"

internal fun phoneMapLibreRasterOpacity(opacity: Float): Float = opacity.coerceIn(0f, 1f)

/** MapLibre-specific translation of a [RasterOnlineMapProvider] into a raster style document. */
internal fun RasterOnlineMapProvider.mapLibreRasterStyleJson(
    additionalAttribution: String? = null,
): String {
    val styleAttribution =
        listOf(attribution, additionalAttribution)
            .filterNotNull()
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" | ")
    return buildString {
        appendLine("{")
        appendLine("  \"version\": 8,")
        appendLine("  \"sources\": {")
        appendLine("    \"$PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID\": {")
        appendLine("      \"type\": \"raster\",")
        appendLine("      \"tiles\": [\"${rasterTileUrlTemplate.jsonEscaped()}\"],")
        appendLine("      \"tileSize\": 256,")
        appendLine("      \"minzoom\": $minimumZoom,")
        appendLine("      \"maxzoom\": $maximumZoom,")
        appendLine("      \"attribution\": \"${styleAttribution.jsonEscaped()}\"")
        appendLine("    }")
        appendLine("  },")
        appendLine("  \"layers\": [")
        appendLine("    {")
        appendLine("      \"id\": \"$PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID\",")
        appendLine("      \"type\": \"raster\",")
        appendLine("      \"source\": \"$PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID\"")
        appendLine("    }")
        appendLine("  ]")
        appendLine("}")
    }
}

/** Updates only raster imagery opacity; semantic style layers remain fully legible. */
internal fun Style.setOnlineRasterOpacity(opacity: Float) {
    (getLayer(PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID) as? RasterLayer)?.setProperties(
        rasterOpacity(phoneMapLibreRasterOpacity(opacity)),
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
