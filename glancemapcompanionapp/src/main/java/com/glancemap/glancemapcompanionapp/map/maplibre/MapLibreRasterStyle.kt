package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.OnlineMapProvider

internal const val PHONE_MAPLIBRE_ONLINE_RASTER_LAYER_ID = "online-raster"

internal fun phoneMapLibreRasterOpacity(opacity: Float): Float = opacity.coerceIn(0f, 1f)

/** MapLibre-specific translation of a [OnlineMapProvider] into a raster style document. */
internal fun OnlineMapProvider.mapLibreRasterStyleJson(
    additionalAttribution: String? = null,
): String {
    require(rasterTileUrlTemplate.isNotBlank()) { "Vector providers must load their style URL." }
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
        bounds?.let { appendLine("      \"bounds\": [${it.joinToString(", ")}],") }
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
