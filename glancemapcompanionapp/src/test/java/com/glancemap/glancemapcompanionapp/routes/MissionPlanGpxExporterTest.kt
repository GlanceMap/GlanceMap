package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.profile.buildTrailRouteProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class MissionPlanGpxExporterTest {
    @Test
    fun `exports only the selected day segment and its waypoints`() {
        val parsed =
            parse(
                """
                <gpx version="1.1">
                  <wpt lat="46.001" lon="11.001"><name>Start hut</name></wpt>
                  <wpt lat="46.003" lon="11.003"><name>Finish hut</name></wpt>
                  <trk><name>Long traverse</name><trkseg>
                    <trkpt lat="46.000" lon="11.000"><ele>1000</ele></trkpt>
                    <trkpt lat="46.001" lon="11.001"><ele>1100</ele></trkpt>
                    <trkpt lat="46.002" lon="11.002"><ele>1200</ele></trkpt>
                    <trkpt lat="46.003" lon="11.003"><ele>1150</ele></trkpt>
                  </trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        val profile = buildTrailRouteProfile(parsed.points)
        val output =
            MissionPlanGpxExporter.export(
                day =
                    MissionPlanDay(
                        id = "day-2",
                        dayNumber = 2,
                        routeId = "route-1",
                        startDistanceMeters = profile.cumulativeDistanceMeters[1],
                        endDistanceMeters = profile.cumulativeDistanceMeters[3],
                    ),
                routeTitle = "Long traverse",
                parsedRoute = parsed,
                profile = profile,
            )

        val exported = parse(output)

        assertEquals("Long traverse — Day 2", exported.title)
        assertEquals(3, exported.points.size)
        assertEquals(2, exported.waypoints.size)
        assertEquals("Start hut", exported.waypoints.first().title)
        assertEquals("Finish hut", exported.waypoints.last().title)
    }

    private fun parse(gpx: String): ParsedCompanionRoute {
        val input = ByteArrayInputStream(gpx.toByteArray())
        return input.use(CompanionGpxRouteParser::parse)
    }
}
