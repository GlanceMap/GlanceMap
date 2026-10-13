package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The public demo permits reasonable traffic, with no published quota or availability guarantee. */
internal class PhonePhotonSearch {
    private val client =
        OkHttpClient
            .Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val cache = linkedMapOf<String, List<PhoneMapSearchResult>>()

    suspend fun search(request: PhoneMapSearchRequest): List<PhoneMapSearchResult> {
        val url = phonePhotonSearchUrl(request)
        val language = Locale.getDefault().toLanguageTag()
        val cacheKey = "$language|$url"
        synchronized(cache) { cache[cacheKey] }?.let { return it }
        val response =
            client
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "GlanceMapCompanion/1 (+https://github.com/GlanceMap/GlanceMap)")
                        .header("Accept-Language", language)
                        .build(),
                ).readPhoneMapSearchResponse()
        val results = parsePhonePhotonSearch(response)
        synchronized(cache) {
            if (cache.size >= 32) cache.remove(cache.keys.first())
            cache[cacheKey] = results
        }
        return results
    }
}

internal fun phonePhotonSearchUrl(request: PhoneMapSearchRequest): HttpUrl =
    "https://photon.komoot.io/api/"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("q", request.query.trim().take(PHONE_MAP_SEARCH_QUERY_LIMIT))
        .addQueryParameter("limit", PHONE_MAP_SEARCH_LIMIT.toString())
        .addQueryParameter("lat", request.bias.latitude.toString())
        .addQueryParameter("lon", request.bias.longitude.toString())
        .build()

internal suspend fun Call.readPhoneMapSearchResponse(): String =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(IOException("Search unavailable"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    PhoneDebugCapture.log("PhoneMapSearch", "event=online_response status=${response.code}")
                    val result = response.use { runCatching { it.boundedPhoneSearchResponse() } }
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            },
        )
    }

internal fun Response.boundedPhoneSearchResponse(): String {
    if (!isSuccessful || body.contentLength() > PHONE_SEARCH_RESPONSE_LIMIT) throw IOException("Search unavailable")
    val output = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(8192)
        var count = input.read(buffer)
        while (count != -1) {
            if (output.size() + count > PHONE_SEARCH_RESPONSE_LIMIT) throw IOException("Search response too large")
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

internal fun parsePhonePhotonSearch(json: String): List<PhoneMapSearchResult> {
    val parsed = runCatching { readPhotonFeatures(json) }
    return parsed.getOrElse { throw IOException("Invalid search response") }
}

private fun readPhotonFeatures(json: String): List<PhoneMapSearchResult> {
    val root = JsonParser.parseString(json).asJsonObject
    val features = root.getAsJsonArray("features") ?: throw IOException("Invalid search response")
    val results =
        features
            .asSequence()
            .take(PHONE_MAP_SEARCH_LIMIT)
            .mapNotNull { feature -> runCatching { feature.asJsonObject.toSearchResult() }.getOrNull() }
            .distinctBy(PhoneMapSearchResult::id)
            .toList()
    if (features.size() > 0 && results.isEmpty()) throw IOException("Invalid search results")
    return results
}

private fun JsonObject.toSearchResult(): PhoneMapSearchResult {
    val geometry = getAsJsonObject("geometry")
    require(geometry.searchText("type") == "Point")
    val coordinates = geometry.getAsJsonArray("coordinates")
    require(coordinates.size() == 2)
    val point = PhoneMapCoordinate(coordinates[1].asDouble, coordinates[0].asDouble)
    require(point.isValidSearchPoint())
    val properties = getAsJsonObject("properties")
    val street = properties.searchText("street")
    val address = listOf(properties.searchText("housenumber"), street).filter(String::isNotBlank).joinToString(" ")
    val label = properties.searchText("name").ifBlank { address }.ifBlank { properties.searchText("city") }
    require(label.isNotBlank())
    val detail =
        listOf(
            address,
            properties.searchText("postcode"),
            properties.searchText("city"),
            properties.searchText("country"),
        ).filter { it.isNotBlank() && it != label }
            .distinct()
            .joinToString(", ")
    return PhoneMapSearchResult(
        id =
            "photon:${properties.searchText("osm_type")}:${properties.searchText("osm_id")}:" +
                "${point.latitude}:${point.longitude}",
        label = label.take(200),
        detail = detail.take(300),
        point = point,
    )
}

private fun JsonObject.searchText(key: String): String =
    get(key)
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.trim()
        .orEmpty()

private const val PHONE_SEARCH_RESPONSE_LIMIT = 256 * 1024
