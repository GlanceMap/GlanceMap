package com.glancemap.glancemapcompanionapp.discovery

import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.cos

internal class GeotrekPublishedTrailProvider(
    private val instance: GeotrekInstance,
    private val http: TrailDiscoveryHttp,
) : PublishedTrailProvider {
    override val provider = TrailProvider.GEOTREK
    override val instanceLabel = "Geotrek — ${instance.name} (${instance.baseUrl})"

    override suspend fun discover(query: TrailDiscoveryQuery): List<PublishedTrail> =
        geotrekDiscoveryUrls(instance, query)
            .flatMap { url ->
                parseGeotrekPublishedTrails(http.read(Request.Builder().url(url).build()), instance)
            }.distinctBy(PublishedTrail::id)
}

internal fun geotrekDiscoveryUrls(
    instance: GeotrekInstance,
    query: TrailDiscoveryQuery,
): List<HttpUrl> {
    val latitudeDelta = query.radiusMeters / 111000.0
    val longitudeDelta =
        (
            latitudeDelta /
                cos(
                    Math.toRadians(query.center.latitude),
                ).coerceAtLeast(0.001)
        ).coerceAtMost(180.0)
    val south = (query.center.latitude - latitudeDelta).coerceAtLeast(-90.0)
    val north = (query.center.latitude + latitudeDelta).coerceAtMost(90.0)
    val west = query.center.longitude - longitudeDelta
    val east = query.center.longitude + longitudeDelta
    val longitudes =
        when {
            west < -180.0 -> listOf(-180.0 to east, west + 360.0 to 180.0)
            east > 180.0 -> listOf(west to 180.0, -180.0 to east - 360.0)
            else -> listOf(west to east)
        }
    return longitudes.map { (left, right) ->
        instance.baseUrl
            .trimEnd('/')
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("api/v2/trek/")
            .addQueryParameter("in_bbox", "$left,$south,$right,$north")
            .addQueryParameter("published", "true")
            .addQueryParameter("practices", instance.hikingPracticeId.toString())
            .addQueryParameter("language", "all")
            .addQueryParameter("page_size", DISCOVERY_RESULT_LIMIT.toString())
            .addQueryParameter("fields", "id,name,description,geometry,length_2d,published,update_datetime")
            .build()
    }
}

internal fun parseGeotrekPublishedTrails(
    json: String,
    instance: GeotrekInstance,
): List<PublishedTrail> =
    normalizeTrailRecords(trailRecords(json, "results")) { record ->
        if (!record.geotrekPublished()) return@normalizeTrailRecords null
        val id = record.trailText("id").also { require(it.toLong() > 0) }
        val points = runCatching { parseTrailGeoJson(record.getAsJsonObject("geometry")) }.getOrDefault(emptyList())
        val title = record.trailText("name").also { require(it.isNotBlank()) }
        PublishedTrail(
            id = "geotrek:${instance.baseUrl}:$id",
            title = title.take(200),
            description = record.trailText("description"),
            geometry = points,
            geometryComplete = points.size >= 2,
            providerDistanceMeters = record.trailNumber("length_2d"),
            sources =
                listOf(
                    TrailSource(
                        provider = TrailProvider.GEOTREK,
                        providerTrailId = id,
                        sourceUrl = "${instance.baseUrl.trimEnd('/')}/api/v2/trek/$id/",
                        publisherName = instance.name,
                        attribution = instance.attribution,
                        license = instance.license,
                        updatedAt = record.trailText("update_datetime").ifBlank { null },
                    ),
                ),
        )
    }

private fun JsonObject.geotrekPublished(): Boolean =
    get("published")?.let { value ->
        if (value.isJsonObject) value.asJsonObject.entrySet().any { it.value.asBoolean } else value.asBoolean
    } == true
