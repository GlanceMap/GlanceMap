package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.glancemapcompanionapp.map.PhoneMapLocation
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTrailTest {
    @Test
    fun offlineMetadataPreservesDetailsAndAllCreditsWithoutDuplicatingGeometry() {
        val trail =
            PublishedTrail(
                "osm/123",
                "Ridge walk",
                listOf(
                    TrailSource(
                        TrailProvider.OPEN_STREET_MAP,
                        "123",
                        "https://example.com/123",
                        "© Contributors",
                        "ODbL",
                        "Park",
                        "2026-10-01",
                    ),
                ),
                description = "A lakeside hike & ridge climb.",
                geometry = listOf(TrailPoint(GeoPoint(46.0, 7.0)), TrailPoint(GeoPoint(46.001, 7.001))),
                geometryComplete = true,
                difficulty = "Easy",
            )
        val record = OfflinePublishedTrail(trail, "library-123", 42)
        val encoded = offlineTrailJson(record).toString()
        val decoded = parseOfflineTrail(JsonParser.parseString(encoded).asJsonObject)
        assertFalse(encoded.contains("geometry"))
        assertEquals(trail.sources, decoded.trail.sources)
        assertEquals(trail.description, decoded.trail.description)
        assertEquals(trail.startLocation, decoded.trail.startLocation)
        assertEquals(trail.distanceMeters, decoded.trail.distanceMeters)
        assertEquals("Easy", decoded.trail.difficulty)
        assertEquals("library-123", decoded.libraryRouteId)
        assertEquals(42, decoded.downloadedAtMillis)
        assertFalse(decoded.trail.canSave)
    }

    @Test
    fun invalidOfflineCoordinateIsRejected() {
        val json =
            JsonParser
                .parseString(
                    """{"id":"x","title":"x","sources":[{"provider":"GEOTREK","sourceUrl":"https://example.com"}],
                "startLocation":{"latitude":91,"longitude":0},"downloadedAtMillis":0}""",
                ).asJsonObject
        assertTrue(runCatching { parseOfflineTrail(json) }.isFailure)
    }

    @Test
    fun nearMeRequiresARecentValidFix() {
        val fix = PhoneMapLocation(46.0, 7.0, 10f, 1000)
        assertEquals(GeoPoint(46.0, 7.0), discoveryCurrentLocationQuery(fix, 2000)?.center)
        assertNull(discoveryCurrentLocationQuery(null, 2000))
        assertNull(discoveryCurrentLocationQuery(fix, 122000))
        assertNull(discoveryCurrentLocationQuery(fix, 999))
        assertNull(discoveryCurrentLocationQuery(fix.copy(latitude = Double.NaN), 2000))
        assertNull(discoveryCurrentLocationQuery(fix.copy(longitude = 181.0), 2000))
    }
}
