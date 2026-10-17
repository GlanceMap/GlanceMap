package com.glancemap.glancemapcompanionapp.map

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneMapSearchTest {
    private val bias = PhoneMapCoordinate(48.85, 2.35)
    private val result = PhoneMapSearchResult("fixture", "Museum", "Paris", bias)

    @Test
    fun photonCoordinatesUseLongitudeFirstAndUnnamedAddressesGetUsefulLabels() {
        val results = parsePhonePhotonSearch(PHOTON_FIXTURE)
        assertEquals(2, results.size)
        assertEquals("Museum", results[0].label)
        assertEquals(PhoneMapCoordinate(48.85, 2.35), results[0].point)
        assertEquals("12 Main Street", results[1].label)
        assertEquals("Paris, France", results[1].detail)
    }

    @Test
    fun namedStreetAddressesIncludeTheProviderHouseNumberInTheTitle() {
        val root = JsonParser.parseString(PHOTON_FIXTURE).asJsonObject
        root
            .getAsJsonArray("features")[1]
            .asJsonObject
            .getAsJsonObject("properties")
            .addProperty("name", "MAIN STREET")
        val result = parsePhonePhotonSearch(root.toString())[1]
        assertEquals("12 Main Street", result.label)
        assertEquals("Paris, France", result.detail)
    }

    @Test
    fun placeNamesKeepTheirAddressDetailsAndStreetOnlyMatchesDoNotInventNumbers() {
        val root = JsonParser.parseString(PHOTON_FIXTURE).asJsonObject
        val features = root.getAsJsonArray("features")
        val place = features[0].asJsonObject.getAsJsonObject("properties")
        place.addProperty("housenumber", "12")
        place.addProperty("street", "Main Street")
        val street = features[1].asJsonObject.getAsJsonObject("properties")
        street.addProperty("name", "Main Street")
        street.remove("housenumber")
        val results = parsePhonePhotonSearch(root.toString())
        assertEquals("Museum", results[0].label)
        assertEquals("12 Main Street, Paris, France", results[0].detail)
        assertEquals("Main Street", results[1].label)
        assertEquals("Paris, France", results[1].detail)
    }

    @Test
    fun invalidEntriesAreIgnoredAndValidDuplicatesAreRemoved() {
        val root = JsonParser.parseString(PHOTON_FIXTURE).asJsonObject
        val features = root.getAsJsonArray("features")
        features.add(features[0].deepCopy())
        val invalid = features[0].deepCopy().asJsonObject
        invalid.getAsJsonObject("geometry").getAsJsonArray("coordinates").set(1, JsonParser.parseString("91"))
        features.add(invalid)
        features.add(JsonParser.parseString("{\"geometry\":null}"))
        assertEquals(2, parsePhonePhotonSearch(root.toString()).size)
    }

    @Test(expected = Exception::class)
    fun missingFeatureCollectionIsAFailureRatherThanAnEmptySuccess() {
        parsePhonePhotonSearch("{\"error\":\"unavailable\"}")
    }

    @Test
    fun genuineEmptyResultsAreAcceptedButMalformedResultsAreAFailure() {
        assertTrue(parsePhonePhotonSearch("{\"features\":[]}").isEmpty())
        assertTrue(runCatching { parsePhonePhotonSearch("{\"features\":[null]}") }.exceptionOrNull() is IOException)
    }

    @Test
    fun searchUrlEncodesQueryAsOneValueAndDoesNotAddCredentials() {
        val query = "Café & limit=999"
        val url = phonePhotonSearchUrl(PhoneMapSearchRequest(query, true, bias, emptySet()))
        assertEquals(query, url.queryParameter("q"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals("48.85", url.queryParameter("lat"))
        assertEquals("2.35", url.queryParameter("lon"))
        assertEquals(setOf("q", "limit", "lat", "lon"), url.queryParameterNames)
        assertEquals("https", url.scheme)
    }

    @Test
    fun poiQueriesNormalizeAccentsAndTreatFtsOperatorsAsLiteralWords() {
        assertEquals("\"wurzburg*\" \"dom*\"", phoneMapPoiSearchExpression("WÜRZBURG Dom"))
        assertEquals("\"cafe*\" \"or*\" \"museum*\"", phoneMapPoiSearchExpression("Café\" OR -museum*"))
        assertEquals("", phoneMapPoiSearchExpression("%_\"*"))
    }

    @Test
    fun abundantLocalResultsDoNotHideOnlineAddressesAndDuplicatesRemainUnique() {
        val local = (1..20).map { result.copy(id = "local-$it", label = "Local $it") }
        val online = (1..20).map { result.copy(id = "online-$it", label = "Online $it") }
        val merged = mergePhoneMapSearchResults(local, online)
        assertEquals(20, merged.size)
        assertEquals(10, merged.count { it.id.startsWith("online-") })
        assertEquals(10, merged.count { it.id.startsWith("local-") })
        assertEquals(1, mergePhoneMapSearchResults(listOf(result), listOf(result.copy(id = "another"))).size)
    }

    @Test
    fun httpFailuresAndOversizedBodiesAreRejected() {
        for (response in listOf(response(429, "busy"), response(200, "x".repeat(256 * 1024 + 1)))) {
            response.use {
                assertTrue(runCatching { it.boundedPhoneSearchResponse() }.exceptionOrNull() is IOException)
            }
        }
        response(200, "{} ").use { assertEquals("{} ", it.boundedPhoneSearchResponse()) }
    }

    @Test
    fun cancellationStopsTheHttpCall() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        response(200, PHOTON_FIXTURE)
                    }.build()
            val call = client.newCall(Request.Builder().url("https://example.invalid/search").build())
            val pending = async { call.readPhoneMapSearchResponse() }
            try {
                withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
                pending.cancelAndJoin()
                assertTrue(call.isCanceled())
            } finally {
                release.countDown()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

    @Test
    fun editingAndChangingSourceRejectLateRepliesEvenWhenWorkIgnoresCancellation() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val requests = mutableListOf<PhoneMapSearchRequest>()
            val replies = mutableListOf<CompletableDeferred<PhoneMapSearchReply>>()
            val partials = mutableListOf<(PhoneMapSearchReply) -> Unit>()
            val controller =
                PhoneMapSearchController(scope) { request, partial ->
                    requests += request
                    partials += partial
                    val reply = CompletableDeferred<PhoneMapSearchReply>()
                    replies += reply
                    withContext(NonCancellable) { reply.await() }
                }
            try {
                controller.edit("old place")
                controller.submit(bias)
                controller.edit("new place")
                controller.submit(bias)
                partials[1](PhoneMapSearchReply(listOf(result)))
                assertTrue(controller.state.value.loading)
                assertEquals(listOf(result), controller.state.value.reply.results)
                partials[0](PhoneMapSearchReply(listOf(result.copy(label = "Old"))))
                assertEquals(listOf(result), controller.state.value.reply.results)
                replies[1].complete(PhoneMapSearchReply(listOf(result)))
                assertEquals(listOf(result), controller.state.value.reply.results)
                replies[0].complete(PhoneMapSearchReply(listOf(result.copy(label = "Old"))))
                assertEquals(listOf(result), controller.state.value.reply.results)

                controller.submit(bias)
                controller.setContext(online = false, poiFiles = setOf("local.poi"))
                replies[2].complete(PhoneMapSearchReply(listOf(result)))
                assertTrue(
                    controller.state.value.reply.results
                        .isEmpty(),
                )
                assertFalse(controller.state.value.online)
                controller.submit(bias)
                assertFalse(requests.last().online)
                assertEquals(setOf("local.poi"), requests.last().poiFiles)
                controller.cancel()
                partials[3](PhoneMapSearchReply(listOf(result)))
                replies[3].complete(PhoneMapSearchReply(listOf(result)))
                assertTrue(
                    controller.state.value.reply.results
                        .isEmpty(),
                )
                assertFalse(controller.state.value.loading)
            } finally {
                replies.forEach { it.complete(PhoneMapSearchReply()) }
                scope.cancel()
            }
        }

    @Test
    fun onlineSearchCanBeEnabledWithoutChangingLocalSourcesOrLosingTheQuery() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val requests = mutableListOf<PhoneMapSearchRequest>()
            val controller =
                PhoneMapSearchController(scope) { request, _ ->
                    requests += request
                    PhoneMapSearchReply(listOf(result))
                }
            try {
                controller.setContext(online = false, poiFiles = setOf("local.poi"))
                controller.edit("Grenoble")
                controller.submit(bias)
                assertFalse(requests.single().online)

                controller.setContext(online = true, poiFiles = setOf("local.poi"))
                assertEquals("Grenoble", controller.state.value.query)
                assertFalse(controller.state.value.searched)
                assertTrue(
                    controller.state.value.reply.results
                        .isEmpty(),
                )
                controller.submit(bias)
                assertTrue(requests.last().online)
                assertEquals(requests.first().copy(online = true), requests.last())
                assertEquals(listOf(result), controller.state.value.reply.results)
            } finally {
                scope.cancel()
            }
        }

    private fun response(
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(Request.Builder().url("https://example.invalid/search").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody())
            .build()
}

private const val PHOTON_FIXTURE = """
{"features":[
  {"geometry":{"type":"Point","coordinates":[2.35,48.85]},
   "properties":{"osm_type":"N","osm_id":1,"name":"Museum","city":"Paris","country":"France"}},
  {"geometry":{"type":"Point","coordinates":[2.36,48.86]},
   "properties":{"osm_type":"N","osm_id":2,"housenumber":"12","street":"Main Street","city":"Paris","country":"France"}}
]}
"""
