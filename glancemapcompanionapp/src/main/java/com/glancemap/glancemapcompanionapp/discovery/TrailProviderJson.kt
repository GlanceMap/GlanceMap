package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

internal const val DISCOVERY_RESULT_LIMIT = 40
internal const val DISCOVERY_POINT_LIMIT = 40000

internal fun JsonObject.trailText(key: String): String = get(key)?.trailText().orEmpty()

internal fun JsonElement.trailText(): String =
    when {
        isJsonPrimitive -> asString.trim().take(12000)
        isJsonArray ->
            asJsonArray
                .asSequence()
                .map { it.trailText() }
                .firstOrNull(String::isNotBlank)
                .orEmpty()
        isJsonObject -> {
            val preferred = listOfNotNull(asJsonObject.get("en"), asJsonObject.get("fr"))
            (preferred + asJsonObject.entrySet().map { it.value })
                .asSequence()
                .map { it.trailText() }
                .firstOrNull(String::isNotBlank)
                .orEmpty()
        }
        else -> ""
    }

internal fun JsonObject.trailNumber(key: String): Double? =
    runCatching { get(key)?.asDouble }.getOrNull()?.takeIf {
        it.isFinite() &&
            it >= 0.0
    }

internal fun trailRecords(
    json: String,
    key: String,
): List<JsonObject> {
    val root = JsonParser.parseString(json).asJsonObject
    require(!root.has("remark")) { "Incomplete route response" }
    val records = root.getAsJsonArray(key) ?: throw IOException("Invalid route response")
    return records
        .take(DISCOVERY_RESULT_LIMIT)
        .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        .also { if (records.size() > 0 && it.isEmpty()) throw IOException("Invalid route records") }
}

internal fun normalizeTrailRecords(
    records: List<JsonObject>,
    normalize: (JsonObject) -> PublishedTrail?,
): List<PublishedTrail> {
    var invalidCount = 0
    val trails =
        records.mapNotNull { record ->
            runCatching { normalize(record) }.onFailure { invalidCount++ }.getOrNull()
        }
    if (trails.isEmpty() && invalidCount > 0) throw IOException("Invalid route records")
    return trails
}

/** Reject a whole line with bad points; dropping one point would fabricate a connection across the gap. */
internal fun parseTrailLine(coordinates: JsonArray): List<TrailPoint> {
    require(coordinates.size() in 2..DISCOVERY_POINT_LIMIT)
    return coordinates.map { coordinate ->
        val values = coordinate.asJsonArray
        require(values.size() in 2..3)
        TrailPoint(
            GeoPoint(values[1].asDouble, values[0].asDouble),
            values.elementAtOrNull(2)?.takeUnless(JsonElement::isJsonNull)?.asDouble,
        )
    }
}

internal fun parseTrailGeoJson(geometry: JsonObject): List<TrailPoint> {
    val coordinates = geometry.getAsJsonArray("coordinates")
    val lines =
        when (geometry.trailText("type")) {
            "LineString" -> listOf(parseTrailLine(coordinates))
            "MultiLineString" -> coordinates.map { parseTrailLine(it.asJsonArray) }
            else -> error("Unsupported route geometry")
        }
    require(lines.sumOf { it.size } <= DISCOVERY_POINT_LIMIT)
    return lines.flatMapIndexed { index, line ->
        line.mapIndexed { pointIndex, point -> point.copy(startsNewSegment = index > 0 && pointIndex == 0) }
    }
}
