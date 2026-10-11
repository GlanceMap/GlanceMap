package com.glancemap.glancemapwearos.presentation.features.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpxDistanceParsingTest {
    @Test
    fun recordedMetadataRemainsIndependentOfParsedGeometry() {
        val parsed = parse(recordingMetadata + track())
        val profile = buildProfile(FileSig(0L, 0L), parsed.points)
        val summary = requireNotNull(parsed.activitySummary)

        assertTrue(parsed.isActivity)
        assertEquals(10_000.0, profile.totalDistance, 0.001)
        assertEquals(10_000.0, parsed.totalDistance, 0.001)
        assertEquals(10_000.0, resolveGpxDisplayDistance(profile.totalDistance, parsed.totalDistance, summary.distanceMeters), 0.001)
        assertEquals(10_500.0, requireNotNull(summary.distanceMeters), 0.0)
        assertEquals(2.9166666666666665, requireNotNull(summary.averageSpeedMps), 0.0)
        assertEquals(3_600.0, requireNotNull(summary.durationSeconds), 0.0)
        assertEquals(5.0, requireNotNull(summary.fastestSpeedMps), 0.0)
        assertEquals(120, summary.averageHeartRateBpm)
        assertEquals(500.0, requireNotNull(summary.caloriesGrossKcal), 0.0)
    }

    @Test
    fun multipleTrackSegmentsNeverAddTheStraightLineGap() {
        val parsed =
            parse(
                recordingMetadata +
                    """
                    <trk>
                      <trkseg><trkpt lat="0" lon="0"/><trkpt lat="0" lon="0.001"/></trkseg>
                      <trkseg><trkpt lat="1" lon="1"/><trkpt lat="1" lon="1.001"/></trkseg>
                    </trk>
                    """.trimIndent(),
            )
        val profile = buildProfile(FileSig(0L, 0L), parsed.points)

        assertTrue(parsed.points[2].startsNewSegment)
        assertEquals(0.0, profile.segLen[1], 0.0)
        assertEquals(222.4, profile.totalDistance, 1.0)
        assertEquals(profile.totalDistance, parsed.totalDistance, 0.001)
        assertEquals(profile.totalDistance, resolveGpxDisplayDistance(profile.totalDistance, parsed.totalDistance, 10_500.0), 0.0)
    }

    @Test
    fun brouterImportUsesGeometryWithoutActivityMetadata() {
        val parsed = parse(track(), creator = "BRouter-1.7.8")
        val profile = buildProfile(FileSig(0L, 0L), parsed.points)

        assertNull(parsed.activitySummary)
        assertEquals(10_000.0, resolveGpxDisplayDistance(profile.totalDistance, parsed.totalDistance, null), 0.001)
    }

    @Test
    fun metadataOnlyActivityRetainsRecordingFallback() {
        val parsed = parse(recordingMetadata)
        val profile = buildProfile(FileSig(0L, 0L), parsed.points)

        assertTrue(parsed.points.isEmpty())
        assertEquals(10_500.0, resolveGpxDisplayDistance(profile.totalDistance, parsed.totalDistance, parsed.activitySummary?.distanceMeters), 0.0)
    }

    private fun track(): String =
        """
        <trk><trkseg>
          <trkpt lat="0" lon="0"/>
          <trkpt lat="0" lon="${Math.toDegrees(10_000.0 / 6_371_000.0)}"/>
        </trkseg></trk>
        """.trimIndent()

    private fun parse(
        body: String,
        creator: String = "GlanceMap",
    ): ParsedGpxData {
        val file = File.createTempFile("gpx-distance", ".gpx")
        return try {
            file.writeText(
                """
                <gpx xmlns="http://www.topografix.com/GPX/1/1"
                     xmlns:gmap="https://glancemap.app/gpx/1" version="1.1" creator="$creator">
                  $body
                </gpx>
                """.trimIndent(),
            )
            parseGpxData(file)
        } finally {
            file.delete()
        }
    }

    private val recordingMetadata =
        """
        <metadata><extensions>
          <gmap:activityType>recording</gmap:activityType>
          <gmap:distanceMeters>10500</gmap:distanceMeters>
          <gmap:durationSeconds>3600</gmap:durationSeconds>
          <gmap:averageSpeedMps>2.9166666666666665</gmap:averageSpeedMps>
          <gmap:fastestSpeedMps>5</gmap:fastestSpeedMps>
          <gmap:averageHeartRateBpm>120</gmap:averageHeartRateBpm>
          <gmap:caloriesGrossKcal>500</gmap:caloriesGrossKcal>
        </extensions></metadata>
        """.trimIndent()
}
