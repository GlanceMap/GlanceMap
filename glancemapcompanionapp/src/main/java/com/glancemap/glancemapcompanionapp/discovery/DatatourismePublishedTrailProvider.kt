package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.trailcore.geo.GeoPoint
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** The documented tour API supplies POI locations, not guaranteed navigable track geometry. */
internal class DatatourismePublishedTrailProvider(
    private val apiKey: String,
    private val http: TrailDiscoveryHttp,
) : PublishedTrailProvider {
    override val provider = TrailProvider.DATATOURISME
    override val unavailableReason: String?
        get() = if (apiKey.isBlank()) "A DATATOURISME_API_KEY must be configured privately." else null

    override suspend fun discover(query: TrailDiscoveryQuery): List<PublishedTrail> {
        check(unavailableReason == null)
        return parseDatatourismePublishedTrails(http.read(datatourismeDiscoveryRequest(query, apiKey)))
    }
}

internal fun datatourismeDiscoveryRequest(
    query: TrailDiscoveryQuery,
    apiKey: String,
): Request {
    val url =
        "https://api.datatourisme.fr/v1/tour"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter(
                "geo_distance",
                "${query.center.latitude},${query.center.longitude},${query.radiusMeters}m",
            ).addQueryParameter("filters", "type=WalkingTour")
            .addQueryParameter("page_size", DISCOVERY_RESULT_LIMIT.toString())
            .addQueryParameter(
                "fields",
                "uuid,label,isLocatedAt.geo,hasDescription,hasBeenCreatedBy.legalName,lastUpdate,tourDistance",
            ).build()
    return Request
        .Builder()
        .url(url)
        .header("X-API-Key", apiKey)
        .build()
}

internal fun parseDatatourismePublishedTrails(json: String): List<PublishedTrail> =
    normalizeTrailRecords(trailRecords(json, "objects")) { record ->
        val id = record.trailText("uuid").also { require(it.matches(Regex("[a-zA-Z0-9-]{1,100}"))) }
        val title = record.trailText("label").also { require(it.isNotBlank()) }
        val publisher = record.getAsJsonObject("hasBeenCreatedBy").trailText("legalName")
        val updated = record.trailText("lastUpdate")
        require(publisher.isNotBlank() && updated.isNotBlank()) // Required by DATAtourisme's reuse conditions.
        val location =
            runCatching {
                val geo = record.getAsJsonArray("isLocatedAt")[0].asJsonObject.getAsJsonObject("geo")
                GeoPoint(geo.get("latitude").asDouble, geo.get("longitude").asDouble)
            }.getOrNull()
        val description =
            runCatching {
                record.getAsJsonArray("hasDescription")[0].asJsonObject.trailText("shortDescription")
            }.getOrDefault("")
        PublishedTrail(
            id = "datatourisme:$id",
            title = title.take(200),
            description = description,
            startLocation = location,
            providerDistanceMeters = record.trailNumber("tourDistance"),
            sources =
                listOf(
                    TrailSource(
                        provider = TrailProvider.DATATOURISME,
                        providerTrailId = id,
                        sourceUrl = "https://data.datatourisme.fr/$id",
                        publisherName = publisher,
                        updatedAt = updated,
                        attribution = "$publisher — DATAtourisme — $updated",
                        license = "Licence Ouverte 2.0 — https://www.datatourisme.fr/ressources-juridiques/",
                    ),
                ),
        )
    }
