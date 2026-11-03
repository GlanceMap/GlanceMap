package com.glancemap.glancemapcompanionapp.map

import org.mapsforge.map.reader.MapFile

/**
 * The credit an offline map's maker wrote in its Mapsforge header, for example
 * "(c) Map: FZK project (CC-BY-3.0); Map data: OpenStreetMap contributors". Maps without one get the
 * OpenStreetMap credit, since Mapsforge maps are built from OpenStreetMap data.
 */
internal fun phoneOfflineMapAttribution(headerComment: String?): String =
    headerComment
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::escapePhoneMapAttributionText)
        ?: OPEN_STREET_MAP_ATTRIBUTION

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
