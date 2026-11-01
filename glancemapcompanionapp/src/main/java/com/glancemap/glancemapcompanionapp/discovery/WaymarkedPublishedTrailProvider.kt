package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

private const val WAYMARKED_API = "https://hiking.waymarkedtrails.org/api/v1/"
private const val MERCATOR_RADIUS = 6378137.0
private const val MERCATOR_LIMIT = 20037508.342789244
private const val MERCATOR_MAX_LAT = 85.0511287798066

internal class WaymarkedPublishedTrailProvider(
    private val http: TrailDiscoveryHttp,
) : PublishedTrailProvider {
    override val provider = TrailProvider.WAYMARKED_TRAILS

    override suspend fun discover(query: TrailDiscoveryQuery): List<PublishedTrail> =
        waymarkedDiscoveryUrls(query)
            .flatMap { url ->
                parseWaymarkedListing(http.read(Request.Builder().url(url).build()))
            }.distinctBy(PublishedTrail::id)
            .take(DISCOVERY_RESULT_LIMIT)

    override suspend fun detail(trail: PublishedTrail): PublishedTrail {
        val source = trail.sources.first { it.provider == provider }
        return parseWaymarkedDetail(http.read(waymarkedDetailRequest(source.providerTrailId)), trail)
    }
}

internal fun waymarkedDiscoveryUrls(query: TrailDiscoveryQuery): List<HttpUrl> {
    val latitudeDelta = query.radiusMeters / 111000.0
    val longitudeDelta =
        (latitudeDelta / cos(Math.toRadians(query.center.latitude)).coerceAtLeast(0.001)).coerceAtMost(180.0)
    val south = (query.center.latitude - latitudeDelta).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
    val north = (query.center.latitude + latitudeDelta).coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
    val west = query.center.longitude - longitudeDelta
    val east = query.center.longitude + longitudeDelta
    val longitudeRanges =
        when {
            west < -180.0 -> listOf(-180.0 to east, west + 360.0 to 180.0)
            east > 180.0 -> listOf(west to 180.0, -180.0 to east - 360.0)
            else -> listOf(west to east)
        }

    fun x(degrees: Double) = MERCATOR_RADIUS * Math.toRadians(degrees)

    fun y(degrees: Double) = MERCATOR_RADIUS * ln(tan(PI / 4 + Math.toRadians(degrees) / 2))
    return longitudeRanges.map { (left, right) ->
        WAYMARKED_API
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("list/by_area")
            .addQueryParameter("bbox", "${x(left)},${y(south)},${x(right)},${y(north)}")
            .addQueryParameter("limit", DISCOVERY_RESULT_LIMIT.toString())
            .build()
    }
}

internal fun waymarkedDetailRequest(id: String): Request {
    require(id.toLongOrNull()?.let { it > 0 } == true)
    return Request
        .Builder()
        .url(
            WAYMARKED_API
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("details/relation/$id")
                .build(),
        ).build()
}

internal fun parseWaymarkedListing(json: String): List<PublishedTrail> =
    normalizeTrailRecords(trailRecords(json, "results")) { record ->
        if (record.trailText("type") != "relation") return@normalizeTrailRecords null
        val id = record.trailText("id").also { require(it.toLong() > 0) }
        PublishedTrail(
            id = "osm:$id",
            title =
                record
                    .trailText("name")
                    .ifBlank { record.trailText("itinerary") }
                    .ifBlank { record.trailText("ref") }
                    .ifBlank { "Hiking route $id" }
                    .take(200),
            sources =
                listOf(
                    TrailSource(
                        provider = TrailProvider.WAYMARKED_TRAILS,
                        providerTrailId = id,
                        sourceUrl = "https://www.openstreetmap.org/relation/$id",
                        attribution = "© OpenStreetMap contributors",
                        license = "ODbL 1.0 — https://www.openstreetmap.org/copyright",
                    ),
                ),
        )
    }

internal fun parseWaymarkedDetail(
    json: String,
    trail: PublishedTrail,
): PublishedTrail {
    val root = JsonParser.parseString(json).asJsonObject
    val id = trail.sources.first { it.provider == TrailProvider.WAYMARKED_TRAILS }.providerTrailId
    require(root.trailText("type") == "relation" && root.trailText("id") == id)
    val points = runCatching { WaymarkedGeometry().parse(root.getAsJsonObject("route")) }.getOrDefault(emptyList())
    return trail.copy(
        description = root.trailText("description").ifBlank { trail.description },
        geometry = points,
        geometryComplete = points.size >= 2,
        startLocation = points.firstOrNull()?.location,
        sources =
            trail.sources.map { source ->
                if (source.provider == TrailProvider.WAYMARKED_TRAILS) {
                    source.copy(publisherName = root.trailText("operator").ifBlank { null })
                } else {
                    source
                }
            },
    )
}

private class WaymarkedGeometry {
    private val points = mutableListOf<TrailPoint>()
    private var breakBeforeNext = false
    private var inputCount = 0

    fun parse(route: JsonObject): List<TrailPoint> {
        append(route, 0)
        require(points.size >= 2)
        return points
    }

    private fun append(
        route: JsonObject,
        depth: Int,
    ) {
        require(depth < 32)
        when (route.trailText("route_type")) {
            "route" -> {
                val main = route.getAsJsonArray("main")
                require(main.size() > 0)
                main.forEach { append(it.asJsonObject, depth + 1) }
                route.getAsJsonArray("appendices").forEach { appendix ->
                    breakBeforeNext = true
                    val segments = appendix.asJsonObject.getAsJsonArray("main")
                    require(segments.size() > 0)
                    segments.forEach { append(it.asJsonObject, depth + 1) }
                    breakBeforeNext = true
                }
            }
            "split" -> {
                val forward = route.getAsJsonArray("forward")
                val backward = route.getAsJsonArray("backward")
                require(forward.size() + backward.size() > 0)
                forward.forEach { append(it.asJsonObject, depth + 1) }
                breakBeforeNext = true
                backward.forEach { append(it.asJsonObject, depth + 1) }
                breakBeforeNext = true
            }
            "linear" -> {
                val ways = route.getAsJsonArray("ways")
                require(ways.size() > 0)
                ways.forEach { appendWay(it.asJsonObject) }
            }
            else -> error("Unsupported Waymarked route geometry")
        }
    }

    private fun appendWay(way: JsonObject) {
        val geometry = way.getAsJsonObject("geometry")
        require(geometry.trailText("type") == "LineString")
        val coordinates = geometry.getAsJsonArray("coordinates")
        require(coordinates.size() >= 2)
        inputCount += coordinates.size()
        require(inputCount <= DISCOVERY_POINT_LIMIT)
        coordinates.forEachIndexed { index, value ->
            val coordinate = value.asJsonArray
            require(coordinate.size() >= 2)
            val x = coordinate[0].asDouble
            val y = coordinate[1].asDouble
            require(
                x.isFinite() &&
                    y.isFinite() &&
                    x in -MERCATOR_LIMIT..MERCATOR_LIMIT &&
                    y in -MERCATOR_LIMIT..MERCATOR_LIMIT,
            )
            val location =
                GeoPoint(
                    Math.toDegrees(2 * atan(exp(y / MERCATOR_RADIUS)) - PI / 2),
                    Math.toDegrees(x / MERCATOR_RADIUS),
                )
            val connected = index == 0 && !breakBeforeNext && points.lastOrNull()?.location == location
            if (!connected) points += TrailPoint(location, startsNewSegment = index == 0 && points.isNotEmpty())
            if (index == 0) breakBeforeNext = false
        }
    }
}
