package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.trailcore.geo.GeoPoint
import com.google.gson.JsonParser
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class TrailProviderTest {
    private val instance =
        GeotrekInstance("Regional Park", "https://geotrek.example/", "CC BY 4.0", "© Regional Park", 1)

    @Test
    fun osmWaysRespectDirectionAndCalculateDistanceWithoutFabricatingElevation() {
        val trail = parseOsmPublishedTrails(fixture("osm")).single()
        assertEquals("Ridge & Lake", trail.title)
        assertEquals(3, trail.geometry.size)
        assertTrue(trail.canSave)
        assertTrue(trail.distanceMeters!! in 200.0..400.0)
        assertTrue(trail.geometry.all { it.elevationMeters == null })
        assertEquals("Hiking Club", trail.sources.single().publisherName)
        assertTrue(
            trail.sources
                .single()
                .license
                .contains("ODbL"),
        )
    }

    @Test
    fun nestedOrMalformedOsmGeometryCannotBeSavedAndNeverBridgesInvalidCoordinates() {
        val root = JsonParser.parseString(fixture("osm")).asJsonObject
        val members = root.getAsJsonArray("elements")[0].asJsonObject.getAsJsonArray("members")
        members.add(JsonParser.parseString("""{"type":"relation","ref":90}"""))
        assertFalse(parseOsmPublishedTrails(root.toString()).single().canSave)
        members.remove(members.size() - 1)
        members[0]
            .asJsonObject
            .getAsJsonArray("geometry")[1]
            .asJsonObject
            .addProperty("lat", 91)
        val trail = parseOsmPublishedTrails(root.toString()).single()
        assertFalse(trail.canSave)
        assertEquals(2, trail.geometry.size)
        assertTrue(trail.geometry.all { it.location.latitude <= 90 })
    }

    @Test
    fun disconnectedOsmMembersKeepSegmentBoundariesThroughGpxRoundTrip() {
        val json = fixture("osm").replaceFirst("46.001", "47.001")
        val trail = parseOsmPublishedTrails(json).single()
        assertTrue(trail.geometry.any { it.startsNewSegment })
        val gpx = publishedTrailGpx(trail)
        val parsed = CompanionGpxRouteParser.parse(ByteArrayInputStream(gpx))
        assertEquals(trail.title, parsed.title)
        assertEquals(trail.geometry, parsed.points)
        val xml = gpx.toString(Charsets.UTF_8)
        assertTrue(xml.contains("© OpenStreetMap contributors"))
        assertTrue(xml.contains("ODbL"))
        assertTrue(xml.contains("https://www.openstreetmap.org/relation/123"))
        assertTrue(xml.contains("Ridge &amp; Lake"))
    }

    @Test
    fun geotrekUsesLongitudeFirstElevationAndTrailCoreRatherThanProviderDistance() {
        val trail = parseGeotrekPublishedTrails(fixture("geotrek"), instance).single()
        assertEquals(GeoPoint(46.0, 7.0), trail.startLocation)
        assertEquals(500.0, trail.geometry.first().elevationMeters!!, 0.0)
        assertTrue(trail.distanceMeters!! < 400)
        assertEquals(10.0, trail.profile!!.totalAscentMeters, 0.01)
        assertEquals(5.0, trail.profile.totalDescentMeters, 0.01)
        assertEquals("© Regional Park", trail.sources.single().attribution)
    }

    @Test
    fun badGeoJsonIsInformationalRatherThanAFalseNavigableRoute() {
        listOf("91", "NaN", "1e999").forEach { latitude ->
            val bad = fixture("geotrek").replace("46.001", latitude)
            assertFalse(parseGeotrekPublishedTrails(bad, instance).single().canSave)
        }
        val malformed = """{"type":"LineString","coordinates":[[7,46],[7.1,91],[7.2,46.2]]}"""
        assertTrue(runCatching { parseTrailGeoJson(JsonParser.parseString(malformed).asJsonObject) }.isFailure)
    }

    @Test
    fun datatourismeRetainsRequiredPublisherAndUpdateButCannotSaveAPoiLocationAsATrack() {
        val trail = parseDatatourismePublishedTrails(fixture("datatourisme")).single()
        assertEquals("Lake trail", trail.title)
        assertEquals(GeoPoint(46.0, 7.0), trail.startLocation)
        assertFalse(trail.canSave)
        assertTrue(
            trail.sources
                .single()
                .attribution
                .contains("Tourism Board"),
        )
        assertTrue(
            trail.sources
                .single()
                .attribution
                .contains("2026-10-01"),
        )
        assertTrue(runCatching { publishedTrailGpx(trail) }.isFailure)
        assertTrue(
            runCatching {
                parseDatatourismePublishedTrails(fixture("datatourisme").replace("Tourism Board", ""))
            }.isFailure,
        )
    }

    @Test
    fun missingCredentialsAndUnsafeEndpointsAreRejectedWithoutRequests() {
        val provider = DatatourismePublishedTrailProvider("", TrailDiscoveryHttp())
        assertNotNull(provider.unavailableReason)
        listOf(
            "http://example.com",
            "https://name:password@example.com",
            "https://example.com/?api_key=placeholder",
        ).forEach {
            assertTrue(runCatching { validatedDiscoveryUrl(it) }.isFailure)
        }
        val request = datatourismeDiscoveryRequest(TrailDiscoveryQuery(GeoPoint(46.0, 7.0)), "PLACEHOLDER")
        assertEquals("PLACEHOLDER", request.header("X-API-Key"))
        assertFalse(request.url.toString().contains("PLACEHOLDER"))
        assertEquals("46.0,7.0,10000m", request.url.queryParameter("geo_distance"))
    }

    @Test
    fun invalidEnvelopesAndOverpassTruncationAreFailuresWhileEmptyArraysAreValid() {
        listOf("{}", "not json", """{"elements":[],"remark":"timeout"}""").forEach {
            assertTrue(runCatching { parseOsmPublishedTrails(it) }.isFailure)
        }
        assertTrue(parseOsmPublishedTrails("""{"elements":[]}""").isEmpty())
    }

    @Test
    fun boundedResponsesRejectHttpErrorsAndOversizedBodies() {
        listOf(429 to "busy", 200 to "x".repeat(4 * 1024 * 1024 + 1)).forEach { (code, body) ->
            Response
                .Builder()
                .request(Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fixture")
                .body(body.toResponseBody())
                .build()
                .use {
                    assertTrue(runCatching { it.boundedTrailResponse() }.exceptionOrNull() is IOException)
                }
        }
    }

    @Test
    fun geotrekRegistryHasDistinctInstancesWithExplicitRightsAndHikingPracticeIds() {
        val instances =
            parseGeotrekRegistry("Park A|https://a.example/|CC BY|© A|1\nPark B|https://b.example/|CC0|© B|4")
        assertEquals(2, instances.size)
        assertEquals(4, instances[1].hikingPracticeId)
        val urls = geotrekDiscoveryUrls(instances[0], TrailDiscoveryQuery(GeoPoint(0.0, 179.99)))
        assertEquals(2, urls.size)
        assertEquals("1", urls[0].queryParameter("practices"))
        assertEquals("/api/v2/trek/", urls[0].encodedPath)
        assertTrue(runCatching { parseGeotrekRegistry("Park|https://a.example") }.isFailure)
    }

    @Test
    fun conservativeDeduplicationPreservesAttributionAndDoesNotMergeNearbyDifferentTrails() {
        val trail = parseGeotrekPublishedTrails(fixture("geotrek"), instance).single()
        val other = trail.copy(id = "other", sources = trail.sources.map { it.copy(provider = TrailProvider.WANDERER) })
        val merged = mergePublishedTrails(listOf(trail, other))
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().sources.size)
        assertEquals(2, mergePublishedTrails(listOf(trail, other.copy(geometry = trail.geometry.reversed()))).size)
        assertEquals(2, mergePublishedTrails(listOf(trail, other.copy(title = "Different trail"))).size)
        assertEquals(2, mergePublishedTrails(listOf(trail, other.copy(geometryComplete = false))).size)
        assertEquals(1, mergePublishedTrails(listOf(trail, trail)).size)
        val xml = publishedTrailGpx(merged.single()).toString(Charsets.UTF_8)
        assertTrue(xml.contains("Wanderer"))
        assertTrue(xml.contains("Geotrek"))
    }

    @Test
    fun malformedRecordsAreIsolatedAndAnEntirelyInvalidCatalogueIsAFailure() {
        val root = JsonParser.parseString(fixture("osm")).asJsonObject
        root.getAsJsonArray("elements").add(JsonParser.parseString("{\"type\":\"relation\"}"))
        assertEquals(1, parseOsmPublishedTrails(root.toString()).size)
        assertTrue(runCatching { parseOsmPublishedTrails("{\"elements\":[{\"type\":\"relation\"}]}") }.isFailure)
    }

    @Test
    fun multiLineGeoJsonPreservesGapsInsteadOfAddingDistanceAcrossThem() {
        val json = """{"type":"MultiLineString","coordinates":[[[7,46],[7.001,46.001]],[[8,47],[8.001,47.001]]]}"""
        val geometry = parseTrailGeoJson(JsonParser.parseString(json).asJsonObject)
        val profile =
            com.glancemap.trailcore.profile
                .buildTrailRouteProfile(geometry)
        assertEquals(4, geometry.size)
        assertTrue(geometry[2].startsNewSegment)
        assertTrue(profile.totalDistanceMeters < 400.0)
    }

    private fun fixture(name: String): String = javaClass.getResource("/discovery/$name.json")!!.readText()
}
