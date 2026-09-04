package com.glancemap.glancemapcompanionapp.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CompanionGpxRouteParserTest {
    @Test
    fun `parses track metadata waypoints and segment boundaries`() {
        val parsed =
            parseGpx(
                """
                <gpx version="1.1">
                  <metadata><name>Dolomites Loop</name></metadata>
                  <wpt lat="46.5000" lon="11.9000">
                    <name>Water source</name><desc>Fill bottles here</desc>
                  </wpt>
                  <trk><name>Track title</name>
                    <trkseg>
                      <trkpt lat="46.0000" lon="11.0000"><ele>1200</ele></trkpt>
                      <trkpt lat="46.0010" lon="11.0010"><ele>1210</ele></trkpt>
                    </trkseg>
                    <trkseg>
                      <trkpt lat="46.0020" lon="11.0020"><ele>1220</ele></trkpt>
                    </trkseg>
                  </trk>
                  <rte>
                    <rtept lat="40.0000" lon="10.0000" />
                    <rtept lat="40.0010" lon="10.0010" />
                  </rte>
                </gpx>
                """.trimIndent(),
            )

        assertEquals("Dolomites Loop", parsed.title)
        assertEquals(3, parsed.points.size)
        assertEquals(1_200.0, parsed.points[0].elevationMeters)
        assertFalse(parsed.points[0].startsNewSegment)
        assertTrue(parsed.points[2].startsNewSegment)
        assertEquals(1, parsed.waypoints.size)
        assertEquals("Water source", parsed.waypoints.single().title)
        assertEquals("Fill bottles here", parsed.waypoints.single().description)
    }

    @Test
    fun `uses route points when the gpx has no usable track`() {
        val parsed =
            parseGpx(
                """
                <gpx version="1.1">
                  <rte><name>Fallback route</name>
                    <rtept lat="46.0000" lon="11.0000"><ele>1000</ele></rtept>
                    <rtept lat="46.0010" lon="11.0010"><ele>1015</ele></rtept>
                  </rte>
                </gpx>
                """.trimIndent(),
            )

        assertEquals("Fallback route", parsed.title)
        assertEquals(2, parsed.points.size)
        assertEquals(1_015.0, parsed.points.last().elevationMeters)
    }

    @Test
    fun `keeps metadata title absent when the gpx contains no route name`() {
        val parsed =
            parseGpx(
                """
                <gpx version="1.1">
                  <trk><trkseg>
                    <trkpt lat="46.0000" lon="11.0000" />
                    <trkpt lat="46.0010" lon="11.0010" />
                  </trkseg></trk>
                </gpx>
                """.trimIndent(),
            )

        assertNull(parsed.title)
        assertEquals(2, parsed.points.size)
    }

    private fun parseGpx(gpx: String): ParsedCompanionRoute {
        val input = ByteArrayInputStream(gpx.toByteArray())
        return input.use(CompanionGpxRouteParser::parse)
    }
}
