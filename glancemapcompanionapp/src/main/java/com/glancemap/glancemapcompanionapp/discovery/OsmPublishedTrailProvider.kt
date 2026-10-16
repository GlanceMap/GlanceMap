package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.google.gson.JsonObject
import okhttp3.FormBody
import okhttp3.Request

/** OSM/Overpass is independent of the UI and can point at an operator-owned backend. */
internal class OsmPublishedTrailProvider(
    private val endpoint: String,
    private val http: TrailDiscoveryHttp,
) : PublishedTrailProvider {
    override val provider = TrailProvider.OPEN_STREET_MAP

    override suspend fun discover(query: TrailDiscoveryQuery): List<PublishedTrail> = parseOsmPublishedTrails(http.read(osmDiscoveryRequest(endpoint, query)))
}

internal fun osmDiscoveryRequest(
    endpoint: String,
    query: TrailDiscoveryQuery,
): Request {
    val center = query.center
    val ql =
        "[out:json][timeout:18][maxsize:16777216];" +
            "rel[\"type\"=\"route\"][\"route\"=\"hiking\"]" +
            "(around:${query.radiusMeters},${center.latitude},${center.longitude});" +
            "out body geom $DISCOVERY_RESULT_LIMIT;"
    return Request
        .Builder()
        .url(validatedDiscoveryUrl(endpoint))
        .post(FormBody.Builder().add("data", ql).build())
        .build()
}

internal fun parseOsmPublishedTrails(json: String): List<PublishedTrail> =
    normalizeTrailRecords(trailRecords(json, "elements")) { relation ->
        if (relation.trailText("type") != "relation") return@normalizeTrailRecords null
        val tags = relation.getAsJsonObject("tags")
        if (tags.trailText("route") != "hiking" || tags.trailText("type") != "route") return@normalizeTrailRecords null
        val id = relation.trailText("id").also { require(it.toLong() > 0) }
        val geometry = osmRelationGeometry(relation)
        PublishedTrail(
            id = "osm:$id",
            title =
                tags
                    .trailText("name")
                    .ifBlank { tags.trailText("ref") }
                    .ifBlank { "Hiking route $id" }
                    .take(200),
            description = tags.trailText("description"),
            sources =
                listOf(
                    TrailSource(
                        provider = TrailProvider.OPEN_STREET_MAP,
                        providerTrailId = id,
                        sourceUrl = "https://www.openstreetmap.org/relation/$id",
                        publisherName = tags.trailText("operator").ifBlank { null },
                        attribution = "© OpenStreetMap contributors",
                        license = "ODbL 1.0 — https://www.openstreetmap.org/copyright",
                    ),
                ),
            geometry = geometry.first,
            geometryComplete = geometry.second,
            difficulty = tags.trailText("sac_scale").ifBlank { null },
        )
    }

private fun osmRelationGeometry(relation: JsonObject): Pair<List<TrailPoint>, Boolean> {
    val points = mutableListOf<TrailPoint>()
    var complete = true
    val members = relation.getAsJsonArray("members") ?: return emptyList<TrailPoint>() to false
    members.forEach { member ->
        val item = member.asJsonObject
        when (item.trailText("type")) {
            "relation" -> complete = false // Nested relations require a separate recursive backend, never invent them.
            "way" -> {
                val line = runCatching { osmMemberLine(item) }.getOrNull()
                if (line == null || points.size + line.size > DISCOVERY_POINT_LIMIT) {
                    complete = false
                } else {
                    appendOsmMember(points, line)
                }
            }
        }
    }
    return points to (complete && points.size >= 2)
}

private fun osmMemberLine(member: JsonObject): List<TrailPoint> {
    val geometry = member.getAsJsonArray("geometry")
    require(geometry.size() in 2..DISCOVERY_POINT_LIMIT)
    val points =
        geometry.map { item ->
            val point = item.asJsonObject
            TrailPoint(GeoPoint(point.get("lat").asDouble, point.get("lon").asDouble))
        }
    return if (member.trailText("role") == "backward") points.reversed() else points
}

private fun appendOsmMember(
    points: MutableList<TrailPoint>,
    line: List<TrailPoint>,
) {
    val connected = points.lastOrNull()?.location == line.first().location
    val segment =
        if (connected) {
            line.drop(1)
        } else {
            line.mapIndexed { index, point ->
                point.copy(startsNewSegment = points.isNotEmpty() && index == 0)
            }
        }
    points += segment
}
