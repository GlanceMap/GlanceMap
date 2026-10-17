package com.glancemap.glancemapcompanionapp.map

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PhoneFrenchAddressSearchTest {
    private val request =
        PhoneMapSearchRequest("12 rue des Tests 75001", true, PhoneMapCoordinate(50.0, 6.0), emptySet())
    private val address = FrenchPostalAddressQuery("12", "75001")
    private val expected =
        PhoneMapSearchResult(
            "ban:fixture-12",
            "12 Rue des Tests",
            "75001 Paris, France · IGN / Base Adresse Nationale",
            PhoneMapCoordinate(48.86, 2.34),
        )

    @Test
    fun frenchPostalQueriesRequireHouseNumberStreetAndPostcode() {
        assertEquals(address, frenchPostalAddressQuery("12 RUE DES TESTS, 75001 Paris"))
        assertEquals(
            FrenchPostalAddressQuery("12bis", "75001"),
            frenchPostalAddressQuery("12 bis allée des Tests 75001"),
        )
        val otherQueries =
            listOf(
                "Berlin",
                "12 Hauptstrasse 95430",
                "12 rue des Tests",
                "75001 Paris",
                "12345 rue des Tests",
                "12 Main Street 75001 Place",
            )
        for (query in otherQueries) {
            assertEquals(null, frenchPostalAddressQuery(query))
        }
    }

    @Test
    fun urlUsesTheDocumentedAddressIndexAndPostalCodeFilter() {
        val query = "12 rue des Tests 75001 & limit=999"
        val url = phoneFrenchAddressSearchUrl(query, "75001")
        assertEquals("https", url.scheme)
        assertEquals("data.geopf.fr", url.host)
        assertEquals("/geocodage/search", url.encodedPath)
        assertEquals(query, url.queryParameter("q"))
        assertEquals("address", url.queryParameter("index"))
        assertEquals("housenumber", url.queryParameter("type"))
        assertEquals("75001", url.queryParameter("postcode"))
        assertEquals("5", url.queryParameter("limit"))
        assertEquals(setOf("q", "index", "type", "postcode", "limit"), url.queryParameterNames)
    }

    @Test
    fun exactAddressKeepsCorrectCoordinateOrderAndSourceCredit() {
        assertEquals(listOf(expected), parsePhoneFrenchAddresses(ADDRESS_FIXTURE, address))
    }

    @Test
    fun houseNumberIsIncludedWhenTheProviderNameContainsOnlyTheStreet() {
        val root = JsonParser.parseString(ADDRESS_FIXTURE).asJsonObject
        root
            .getAsJsonArray("features")[0]
            .asJsonObject
            .getAsJsonObject("properties")
            .addProperty("name", "Rue des Tests")
        assertEquals(listOf(expected), parsePhoneFrenchAddresses(root.toString(), address))
    }

    @Test
    fun houseNumberSuffixIsKeptWithoutDuplicatingAnExistingNumber() {
        val root = JsonParser.parseString(ADDRESS_FIXTURE).asJsonObject
        val properties = root.getAsJsonArray("features")[0].asJsonObject.getAsJsonObject("properties")
        properties.addProperty("housenumber", "12 bis")
        val query = FrenchPostalAddressQuery("12bis", "75001")
        for (name in listOf("Rue des Tests", "12 BIS Rue des Tests", "12bis Rue des Tests")) {
            properties.addProperty("name", name)
            val label = if (name == "Rue des Tests") "12 bis Rue des Tests" else name
            assertEquals(listOf(expected.copy(label = label)), parsePhoneFrenchAddresses(root.toString(), query))
        }
    }

    @Test
    fun approximateWrongNumberWrongPostcodeAndInvalidCoordinatesAreRejected() {
        val mismatches =
            listOf(
                "score" to "0.4",
                "housenumber" to "13",
                "postcode" to "75002",
                "type" to "street",
            )
        for ((key, value) in mismatches) {
            val root = JsonParser.parseString(ADDRESS_FIXTURE).asJsonObject
            root
                .getAsJsonArray("features")[0]
                .asJsonObject
                .getAsJsonObject("properties")
                .addProperty(key, value)
            assertTrue(parsePhoneFrenchAddresses(root.toString(), address).isEmpty())
        }
        val root = JsonParser.parseString(ADDRESS_FIXTURE).asJsonObject
        val point = root.getAsJsonArray("features")[0].asJsonObject.getAsJsonObject("geometry")
        point.getAsJsonArray("coordinates").set(1, JsonParser.parseString("91"))
        assertTrue(parsePhoneFrenchAddresses(root.toString(), address).isEmpty())
    }

    @Test
    fun malformedRecordsDoNotHideValidRecordsAndMissingCollectionIsAnError() {
        val root = JsonParser.parseString(ADDRESS_FIXTURE).asJsonObject
        val features = root.getAsJsonArray("features")
        features.add(JsonParser.parseString("null"))
        features.add(features[0].deepCopy())
        assertEquals(listOf(expected), parsePhoneFrenchAddresses(root.toString(), address))
        val failure = runCatching { parsePhoneFrenchAddresses("{\"error\":\"busy\"}", address) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(parsePhoneFrenchAddresses("{\"features\":[]}", address).isEmpty())
    }

    @Test
    fun exactFrenchAddressUsesBanWithoutCallingPhoton() =
        runBlocking {
            var photonCalled = false
            val search =
                PhoneOnlinePlaceSearch(
                    photon = {
                        photonCalled = true
                        emptyList()
                    },
                    frenchAddress = { listOf(expected) },
                )
            assertEquals(listOf(expected), search.search(request))
            assertFalse(photonCalled)
        }

    @Test
    fun noAddressMatchOrServiceFailureFallsBackToPhoton() =
        runBlocking {
            for (failure in listOf(false, true)) {
                val search =
                    PhoneOnlinePlaceSearch(
                        photon = { listOf(expected.copy(id = "photon:fixture")) },
                        frenchAddress = { if (failure) throw IOException("Unavailable") else emptyList() },
                    )
                assertEquals("photon:fixture", search.search(request).single().id)
            }
        }

    @Test
    fun worldwidePlaceSearchAndOfflineModeDoNotQueryBan() =
        runBlocking {
            var photonCalls = 0
            val search =
                PhoneOnlinePlaceSearch(
                    photon = {
                        photonCalls++
                        listOf(expected)
                    },
                    frenchAddress = { error("BAN must not be queried") },
                )
            assertEquals(listOf(expected), search.search(request.copy(query = "Chamonix")))
            assertTrue(search.search(request.copy(online = false)).isEmpty())
            assertEquals(1, photonCalls)
        }

    @Test
    fun cancellationDoesNotTriggerAFallbackRequest() =
        runBlocking {
            val search =
                PhoneOnlinePlaceSearch(
                    photon = { error("Cancelled work must not make another request") },
                    frenchAddress = { throw CancellationException() },
                )
            assertTrue(runCatching { search.search(request) }.exceptionOrNull() is CancellationException)
        }

    @Test
    fun repeatedSearchDoesNotMakeAnotherHttpRequest() =
        runBlocking {
            var calls = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        calls++
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ADDRESS_FIXTURE.toResponseBody())
                            .build()
                    }.build()
            try {
                val search = PhoneFrenchAddressSearch(client)
                assertEquals(listOf(expected), search.search(request))
                assertEquals(listOf(expected), search.search(request))
                assertEquals(1, calls)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
}

private const val ADDRESS_FIXTURE = """
{"type":"FeatureCollection","features":[{
  "geometry":{"type":"Point","coordinates":[2.34,48.86]},
  "properties":{"id":"fixture-12","name":"12 Rue des Tests","type":"housenumber",
    "housenumber":"12","postcode":"75001","city":"Paris","score":0.96}
}]}
"""
