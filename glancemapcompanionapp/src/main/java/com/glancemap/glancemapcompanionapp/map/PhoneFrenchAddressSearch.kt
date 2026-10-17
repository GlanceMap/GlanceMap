package com.glancemap.glancemapcompanionapp.map

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** IGN's BAN address index fills house-number gaps in the worldwide OSM geocoder. */
internal class PhoneFrenchAddressSearch(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(4, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
) {
    private val cache = linkedMapOf<String, List<PhoneMapSearchResult>>()

    suspend fun search(request: PhoneMapSearchRequest): List<PhoneMapSearchResult> {
        val address = frenchPostalAddressQuery(request.query) ?: return emptyList()
        val query = request.query.trim().take(PHONE_MAP_SEARCH_QUERY_LIMIT)
        val key = normalizePhoneMapSearch(query)
        return synchronized(cache) { cache[key] } ?: run {
            val response =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(phoneFrenchAddressSearchUrl(query, address.postcode))
                            .header("User-Agent", "GlanceMapCompanion/1 (+https://github.com/GlanceMap/GlanceMap)")
                            .build(),
                    ).readPhoneMapSearchResponse()
            val results = parsePhoneFrenchAddresses(response, address)
            synchronized(cache) {
                if (cache.size >= 32) cache.remove(cache.keys.first())
                cache[key] = results
            }
            results
        }
    }
}

internal data class FrenchPostalAddressQuery(
    val houseNumber: String,
    val postcode: String,
)

/** Explicit French street vocabulary avoids sending ordinary worldwide place searches to BAN. */
internal fun frenchPostalAddressQuery(query: String): FrenchPostalAddressQuery? {
    val value = normalizePhoneMapSearch(query.take(PHONE_MAP_SEARCH_QUERY_LIMIT))
    val number = FRENCH_HOUSE_NUMBER.find(value) ?: return null
    val postcode = FRENCH_POSTCODE.findAll(value).lastOrNull { it.range.first > number.range.last }
    return if (postcode != null && FRENCH_STREET.containsMatchIn(value.substring(number.range.last + 1))) {
        FrenchPostalAddressQuery(number.groupValues[1].replace(" ", ""), postcode.value)
    } else {
        null
    }
}

internal fun phoneFrenchAddressSearchUrl(
    query: String,
    postcode: String,
): HttpUrl =
    "https://data.geopf.fr/geocodage/search"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("q", query.trim().take(PHONE_MAP_SEARCH_QUERY_LIMIT))
        .addQueryParameter("index", "address")
        .addQueryParameter("type", "housenumber")
        .addQueryParameter("postcode", postcode)
        .addQueryParameter("limit", "5")
        .build()

internal fun parsePhoneFrenchAddresses(
    json: String,
    address: FrenchPostalAddressQuery,
): List<PhoneMapSearchResult> =
    runCatching {
        val features =
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("features")
                ?: throw IOException("Invalid address response")
        features
            .asSequence()
            .take(5)
            .mapNotNull { feature ->
                runCatching { feature.asJsonObject.frenchAddressResult(address) }.getOrNull()
            }.distinctBy(PhoneMapSearchResult::id)
            .toList()
    }.getOrElse { throw IOException("Invalid address response") }

private fun JsonObject.frenchAddressResult(address: FrenchPostalAddressQuery): PhoneMapSearchResult {
    val geometry = getAsJsonObject("geometry")
    require(geometry.get("type").asString == "Point")
    val coordinates = geometry.getAsJsonArray("coordinates")
    require(coordinates.size() == 2)
    val point = PhoneMapCoordinate(coordinates[1].asDouble, coordinates[0].asDouble)
    require(point.isValidSearchPoint())
    val properties = getAsJsonObject("properties")
    require(properties.get("type").asString == "housenumber")
    require(properties.get("postcode").asString == address.postcode)
    val houseNumber = properties.get("housenumber").asString.trim()
    val number = normalizePhoneMapSearch(houseNumber).replace(" ", "")
    require(number == address.houseNumber)
    val score = properties.get("score").asDouble
    require(score.isFinite() && score in 0.8..1.0)
    val name =
        properties
            .get("name")
            .asString
            .trim()
            .also { require(it.isNotEmpty()) }
    val nameNumber =
        FRENCH_HOUSE_NUMBER
            .find(normalizePhoneMapSearch(name))
            ?.groupValues
            ?.get(1)
            ?.replace(" ", "")
    val label = if (nameNumber == number) name else "$houseNumber $name"
    val city =
        properties
            .get("city")
            .asString
            .trim()
            .also { require(it.isNotEmpty()) }
    val id =
        properties
            .get("id")
            .asString
            .trim()
            .also { require(it.isNotEmpty()) }
    return PhoneMapSearchResult(
        id = "ban:${id.take(200)}",
        label = label.take(200),
        detail = "${address.postcode} ${city.take(100)}, France · IGN / Base Adresse Nationale",
        point = point,
    )
}

private val FRENCH_HOUSE_NUMBER = Regex("^(\\d{1,5}(?:\\s*(?:bis|ter|quater)|[a-z])?)\\s+")
private val FRENCH_POSTCODE = Regex("\\b\\d{5}\\b")
private val FRENCH_STREET =
    Regex("^(rue|avenue|boulevard|chemin|impasse|allee|quai|route|place|passage|cours|square)\\b")
