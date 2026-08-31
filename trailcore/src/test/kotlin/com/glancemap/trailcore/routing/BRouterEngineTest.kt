package com.glancemap.trailcore.routing

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BRouterEngineTest {
    @Test
    fun `required tiles cover every coordinate including negative hemispheres`() {
        val tiles =
            requiredRoutingSegmentFileNames(
                listOf(
                    GeoPoint(latitude = 45.1, longitude = 5.1),
                    GeoPoint(latitude = 49.9, longitude = 9.9),
                    GeoPoint(latitude = -0.1, longitude = -0.1),
                ),
            )

        assertTrue(tiles.contains("E5_N45.rd5"))
        assertTrue(tiles.contains("W5_S5.rd5"))
        assertEquals(33, tiles.size)
    }

    @Test
    fun `normalizes common routing failures for both apps`() {
        assertEquals(
            "Routing data is damaged. Refresh the routing packs.",
            normalizeBRouterErrorMessage("top index checksum error"),
        )
        assertEquals("No route found.", normalizeBRouterErrorMessage("No track found"))
    }
}
