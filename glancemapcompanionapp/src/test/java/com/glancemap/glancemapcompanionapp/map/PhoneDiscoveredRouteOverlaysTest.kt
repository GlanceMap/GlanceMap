package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.discovery.PublishedTrail
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDiscoveredRouteOverlaysTest {
    @Test
    fun checkedRoutesAndPreviewAppearOnceAndRetainSegmentBoundaries() {
        val first = trail("first")
        val second = trail("second")
        val emptyPreview = PhoneMapGpxOverlay("discovery/first", "first", emptyList())
        val overlays = discoveryMapOverlays(listOf(first, second), emptyPreview)
        assertEquals(listOf("discovery/first", "discovery/second"), overlays.map { it.id })
        assertTrue(overlays.first().segments.any { !it.isGap && it.points.size >= 2 })
        assertTrue(overlays.first().segments.any { it.isGap })
        assertEquals(2, discoveryMapOverlays(listOf(first, second), null).size)
        assertEquals(listOf(emptyPreview), discoveryMapOverlays(emptyList(), emptyPreview))
    }

    private fun trail(id: String): PublishedTrail =
        PublishedTrail(
            id = id,
            title = id,
            sources = emptyList(),
            geometry =
                listOf(
                    TrailPoint(GeoPoint(48.1, 11.5)),
                    TrailPoint(GeoPoint(48.101, 11.501)),
                    TrailPoint(GeoPoint(48.2, 11.6), startsNewSegment = true),
                    TrailPoint(GeoPoint(48.201, 11.601)),
                ),
            geometryComplete = true,
        )
}
