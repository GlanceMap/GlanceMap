package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException

class WaymarkedDiscoveryTest {
    @Test
    fun newInstallDefaultsToWaymarkedWithoutStartingOverpass() {
        assertEquals(setOf("WAYMARKED_TRAILS"), RouteSourceSettings().enabled.map { it.name }.toSet())
    }

    @Test
    fun listingUsesMercatorBoundsAndRetainsRelationMetadataWithoutGeometry() {
        val query = TrailDiscoveryQuery(GeoPoint(48.1367362, 11.5386586))
        val url = waymarkedDiscoveryUrls(query).single()
        val bounds = url.queryParameter("bbox")!!.split(',').map(String::toDouble)
        assertEquals(1269449.95, bounds[0], 2.0)
        assertEquals(6144673.95, bounds[3], 2.0)
        assertEquals("40", url.queryParameter("limit"))
        val json = """{"results":[{"id":2140140,"type":"relation","name":"MVV Wanderweg Dreieck","group":"LOC"}]}"""
        val trail = parseWaymarkedListing(json).single()
        assertEquals("osm:2140140", trail.id)
        assertEquals("2140140", trail.sources.single().providerTrailId)
        assertEquals("MVV Wanderweg Dreieck", trail.title)
        assertFalse(trail.canSave)
        assertEquals("/api/v1/details/relation/2140140", waymarkedDetailRequest("2140140").url.encodedPath)
        assertTrue(parseWaymarkedListing("""{"results":[]}""").isEmpty())
        assertTrue(runCatching { parseWaymarkedListing("{}") }.isFailure)
    }

    @Test
    fun capturedMunichDetailProducesRealSaveableGeometry() {
        val json = javaClass.getResource("/discovery/waymarked-detail.json")!!.readText()
        val listing =
            parseWaymarkedListing("""{"results":[{"id":2140140,"type":"relation","name":"MVV Wanderweg Dreieck"}]}""")
                .single()
        val trail = parseWaymarkedDetail(json, listing)
        assertTrue(trail.canSave)
        assertTrue(trail.geometry.size > 100)
        assertEquals(
            48.1998247,
            trail.geometry
                .first()
                .location.latitude,
            0.00001,
        )
        assertEquals(
            11.6116934,
            trail.geometry
                .first()
                .location.longitude,
            0.00001,
        )
        val gpx = CompanionGpxRouteParser.parse(ByteArrayInputStream(publishedTrailGpx(trail)))
        assertEquals(trail.geometry, gpx.points)
        val broken = JsonParser.parseString(json).asJsonObject
        broken
            .getAsJsonObject("route")
            .getAsJsonArray("main")[0]
            .asJsonObject
            .getAsJsonArray("ways")[0]
            .asJsonObject
            .remove("geometry")
        assertFalse(parseWaymarkedDetail(broken.toString(), listing).canSave)
        val missingWays = JsonParser.parseString(json).asJsonObject
        missingWays
            .getAsJsonObject("route")
            .getAsJsonArray("main")[0]
            .asJsonObject
            .add("ways", JsonArray())
        assertFalse(parseWaymarkedDetail(missingWays.toString(), listing).canSave)
    }

    @Test
    fun sameOsmRelationMergesButDifferentIdsStaySeparate() {
        val waymarked = parseWaymarkedListing("""{"results":[{"id":2140140,"type":"relation","name":"A"}]}""").single()
        val direct =
            waymarked.copy(
                id = "direct",
                sources = waymarked.sources.map { it.copy(provider = TrailProvider.OPEN_STREET_MAP) },
            )
        assertEquals(2, mergePublishedTrails(listOf(waymarked, direct)).single().sources.size)
        val geometry = listOf(TrailPoint(GeoPoint(48.1, 11.5)), TrailPoint(GeoPoint(48.2, 11.6)))
        val first = waymarked.copy(geometry = geometry, geometryComplete = true)
        val another =
            direct.copy(
                id = "other",
                sources = direct.sources.map { it.copy(providerTrailId = "2140141") },
                geometry = geometry,
                geometryComplete = true,
            )
        assertEquals(2, mergePublishedTrails(listOf(first, another)).size)
        val geotrek =
            waymarked.copy(
                id = "geotrek:1",
                sources = waymarked.sources.map { it.copy(provider = TrailProvider.GEOTREK) },
            )
        assertEquals(2, mergePublishedTrails(listOf(waymarked, geotrek)).size)
    }

    @Test
    fun boundsCrossingDateLineSplitAndResultsAreBounded() {
        assertEquals(2, waymarkedDiscoveryUrls(TrailDiscoveryQuery(GeoPoint(0.0, 179.99))).size)
        val records = (1..45).joinToString(",") { """{"id":$it,"type":"relation","ref":"R$it"}""" }
        val trails = parseWaymarkedListing("""{"results":[$records]}""")
        assertEquals(40, trails.size)
        assertEquals("R1", trails.first().title)
    }

    @Test
    fun splitAndAppendixRemainSeparateSegments() {
        val listing = parseWaymarkedListing("""{"results":[{"id":7,"type":"relation","name":"Branches"}]}""").single()

        fun line(x: Int) =
            """{"route_type":"linear","ways":[{"geometry":{"type":"LineString",""" +
                """"coordinates":[[$x,6100000],[${x + 100},6100000]]}}]}"""
        val json =
            """{"id":7,"type":"relation","route":{"route_type":"route","main":[{"route_type":"split",""" +
                """"forward":[${line(1280000)}],"backward":[${line(1280100)}]}],""" +
                """"appendices":[{"main":[${line(1280200)}]}]}}"""
        val trail = parseWaymarkedDetail(json, listing)
        assertTrue(trail.canSave)
        assertEquals(2, trail.geometry.count { it.startsNewSegment })
    }

    @Test
    fun listingIsLazyAndSelectedDetailIsCached() =
        runBlocking {
            val paths = mutableListOf<String>()
            val fixture = javaClass.getResource("/discovery/waymarked-detail.json")!!.readText()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        paths.add(request.url.encodedPath)
                        val body =
                            if (request.url.encodedPath.endsWith("by_area")) {
                                """{"results":[{"id":2140140,"type":"relation","name":"MVV Wanderweg Dreieck"}]}"""
                            } else {
                                fixture
                            }
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody())
                            .build()
                    }.build()
            try {
                val provider = WaymarkedPublishedTrailProvider(TrailDiscoveryHttp(client))
                val failingDirect =
                    object : PublishedTrailProvider {
                        override val provider = TrailProvider.OPEN_STREET_MAP

                        override suspend fun discover(
                            query: TrailDiscoveryQuery,
                        ): List<PublishedTrail> = throw IOException("fixture")
                    }
                val repository = TrailDiscoveryRepository(listOf(provider, failingDirect))
                val reply =
                    repository.discover(
                        TrailDiscoveryQuery(GeoPoint(48.1367362, 11.5386586)),
                        setOf(TrailProvider.WAYMARKED_TRAILS, TrailProvider.OPEN_STREET_MAP),
                    )
                assertEquals(
                    listOf(TrailSourceOutcome.SUCCESS, TrailSourceOutcome.FAILED),
                    reply.statuses.map { it.outcome },
                )
                assertEquals(listOf("/api/v1/list/by_area"), paths)
                val result = repository.detail(reply.trails.single())
                assertTrue(result.canSave)
                assertTrue(repository.detail(reply.trails.single()).canSave)
                assertEquals(2, paths.size)
                assertEquals("/api/v1/details/relation/2140140", paths.last())
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

    @Test
    fun providerDistinguishesEmptyMalformedHttpFailureAndTimeout() =
        runBlocking {
            val cases = listOf("empty", "malformed", "http", "timeout")
            for (scenario in cases) {
                val client =
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            if (scenario == "timeout") throw SocketTimeoutException()
                            val body = if (scenario == "empty") """{"results":[]}""" else "{}"
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(if (scenario == "http") 503 else 200)
                                .message("fixture")
                                .body(body.toResponseBody())
                                .build()
                        }.build()
                try {
                    val provider = WaymarkedPublishedTrailProvider(TrailDiscoveryHttp(client))
                    val repo = TrailDiscoveryRepository(listOf(provider))
                    val reply =
                        repo.discover(
                            TrailDiscoveryQuery(GeoPoint(48.1367362, 11.5386586)),
                            setOf(TrailProvider.WAYMARKED_TRAILS),
                        )
                    assertTrue(reply.trails.isEmpty())
                    val expected = if (scenario == "empty") TrailSourceOutcome.SUCCESS else TrailSourceOutcome.FAILED
                    assertEquals(expected, reply.statuses.single().outcome)
                    if (scenario == "timeout") {
                        assertEquals(TrailDiscoveryFailure.TIMED_OUT, reply.statuses.single().failure)
                    }
                    if (scenario == "http") {
                        assertEquals(TrailDiscoveryFailure.SERVICE_UNAVAILABLE, reply.statuses.single().failure)
                    }
                } finally {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
        }
}
