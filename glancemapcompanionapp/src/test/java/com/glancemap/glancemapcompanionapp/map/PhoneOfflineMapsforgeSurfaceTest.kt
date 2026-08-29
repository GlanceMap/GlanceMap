package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.model.BoundingBox

class PhoneOfflineMapsforgeSurfaceTest {
    @Test
    fun mapsforgeSegmentsKeepEachRouteSegmentSeparate() {
        val segments =
            listOf(
                PhoneMapRouteSegment(
                    listOf(
                        GeoPoint(45.0, 6.0),
                        GeoPoint(45.1, 6.1),
                    ),
                ),
                PhoneMapRouteSegment(
                    listOf(
                        GeoPoint(45.2, 6.2),
                        GeoPoint(45.3, 6.3),
                    ),
                ),
            )

        val mapsforgeSegments = segments.toMapsforgeSegments()

        assertEquals(listOf(2, 2), mapsforgeSegments.map { it.size })
        assertEquals(45.2, mapsforgeSegments[1].first().latitude, 0.0)
    }

    @Test
    fun mapsforgeViewportUsesVisibleBoundsAndZoom() {
        val viewport =
            mapsforgeViewportOrNull(
                bounds = BoundingBox(45.0, 6.0, 46.0, 7.0),
                zoom = 14,
            )

        requireNotNull(viewport)
        assertEquals(45.0, viewport.minLat, 0.0)
        assertEquals(7.0, viewport.maxLon, 0.0)
        assertEquals(14.0, viewport.zoom, 0.0)
        assertNull(mapsforgeViewportOrNull(bounds = null, zoom = 14))
    }
}
