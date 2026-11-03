package com.glancemap.glancemapcompanionapp.map

import org.mapsforge.map.reader.MapFile

private const val OPEN_ANDRO_MAPS_ATTRIBUTION = "<a href=\"https://www.openandromaps.org\">© OpenAndroMaps</a>"
private const val FREIZEITKARTE_ATTRIBUTION =
    "<a href=\"https://www.freizeitkarte-osm.de\">© Freizeitkarte (CC BY 3.0)</a>"

/**
 * The credit for an offline map, from the text its maker wrote in the Mapsforge header. Known makers
 * get a short linked credit, since their headers are long mixed-language lines; any other text is
 * shown as written. Maps without one show no credit.
 */
internal fun phoneOfflineMapAttribution(headerComment: String?): String? {
    val comment = headerComment?.trim().orEmpty()
    return when {
        comment.contains("openandromaps", ignoreCase = true) ->
            "$OPEN_ANDRO_MAPS_ATTRIBUTION, $OPEN_STREET_MAP_ATTRIBUTION"
        comment.contains("FZK project", ignoreCase = true) || comment.contains("freizeitkarte", ignoreCase = true) ->
            "$FREIZEITKARTE_ATTRIBUTION, $OPEN_STREET_MAP_ATTRIBUTION"
        comment.isNotEmpty() -> escapePhoneMapAttributionText(comment)
        else -> null
    }
}

/** Header comments are plain text, but the credit line renders HTML links. */
internal fun escapePhoneMapAttributionText(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

internal fun readPhoneOfflineMapHeaderComment(map: PhoneOfflineMap): String? =
    runCatching {
        val reader = MapFile(map.file)
        try {
            reader.mapFileInfo.comment
        } finally {
            reader.close()
        }
    }.getOrNull()
