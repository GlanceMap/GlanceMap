package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.recording.RecordedTraceSummary
import com.glancemap.trailcore.recording.RecordingPoint
import com.glancemap.trailcore.recording.encodeRecordedTraceAsGpx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedActivityStatisticsTest {
    @Test
    fun savedGpxReopensWithTheSameStatisticsAsTheCompletionRecap() {
        val summary = summary()
        val points =
            listOf(
                RecordingPoint(GeoPoint(46.0, 7.0), 100.0, 1_000, 5f, 2f),
                RecordingPoint(GeoPoint(46.001, 7.001), 110.0, 61_000, 5f, 2f),
            )
        val bytes = encodeRecordedTraceAsGpx("Morning ride", points, summary)
        val parsed = CompanionGpxRouteParser.parse(bytes.inputStream())

        assertEquals(summary.activityStatistics(), parsed.activityStatistics)
        assertEquals("Morning ride", parsed.title)
        assertEquals(60.0, parsed.activityStatistics?.durationSeconds)
        assertEquals(90.0, parsed.activityStatistics?.totalDurationSeconds)
        assertEquals(130, parsed.activityStatistics?.averageHeartRateBpm)
        assertEquals(450, parsed.activityStatistics?.maxPowerWatts)
    }

    @Test
    fun summaryAcceptsANamespaceAliasAndIgnoresOtherNamespacesAndPointExtensions() {
        val alias = parse("<x:activityType>recording</x:activityType><x:distanceMeters>12</x:distanceMeters>")
        assertEquals(12.0, alias.activityStatistics?.distanceMeters)
        val otherNamespace = parse("<x:distanceMeters>12</x:distanceMeters>", "https://example.invalid/other")
        assertNull(otherNamespace.activityStatistics)
        val noMetadataSummary =
            """
            <gpx xmlns:x="https://glancemap.app/gpx/extensions/1">
                <trk><trkseg><trkpt lat="46" lon="7">
                <extensions><x:distanceMeters>99</x:distanceMeters></extensions></trkpt>
                <trkpt lat="46.001" lon="7.001"/></trkseg></trk>
            </gpx>
            """.trimIndent()
        assertNull(CompanionGpxRouteParser.parse(noMetadataSummary.byteInputStream()).activityStatistics)
    }

    @Test
    fun invalidAndMissingMeasurementsRemainUnavailable() {
        val stats =
            requireNotNull(
                parse(
                    """
                    <x:activityType>recording</x:activityType>
                    <x:durationSeconds>NaN</x:durationSeconds><x:totalDurationSeconds>-1</x:totalDurationSeconds>
                    <x:fastestSpeedMps>Infinity</x:fastestSpeedMps><x:averagePowerWatts>-5</x:averagePowerWatts>
                    <x:caloriesGrossKcal>Infinity</x:caloriesGrossKcal>
                    """.trimIndent(),
                ).activityStatistics,
            )
        assertNull(stats.durationSeconds)
        assertNull(stats.totalDurationSeconds)
        assertNull(stats.fastestSpeedMps)
        assertNull(stats.averagePowerWatts)
        assertNull(stats.averageHeartRateBpm)
        assertNull(stats.caloriesGrossKcal)
        assertTrue(requireNotNull(stats.distanceMeters) > 0.0)
    }

    @Test
    fun legacyRecordingGeometryDoesNotInventActiveTimeOrBridgeSegments() {
        val parsed =
            CompanionGpxRouteParser.parse(
                """
                <gpx><trk><trkseg><trkpt lat="46" lon="7"/></trkseg>
                <trkseg><trkpt lat="47" lon="8"/></trkseg></trk></gpx>
                """.trimIndent().byteInputStream(),
            )
        val geometry = recordedActivityGeometry(parsed.points)
        assertEquals(0.0, geometry.distanceMeters)
        assertNull(geometry.durationSeconds)
        assertNull(geometry.totalDurationSeconds)
        assertNull(geometry.averageSpeedMps)
        assertNull(geometry.elevationGainMeters)
    }

    @Test
    fun shortRecordingUsesTheWatchThresholdsWithoutTreatingUnknownTimeAsZero() {
        val normal = RecordedActivityStatistics(pointCount = 2, distanceMeters = 20.0, durationSeconds = 10.0)
        assertFalse(normal.isShortRecording)
        assertTrue(normal.copy(pointCount = 1).isShortRecording)
        assertTrue(normal.copy(distanceMeters = 19.9).isShortRecording)
        assertTrue(normal.copy(durationSeconds = 9.9).isShortRecording)
        assertFalse(normal.copy(distanceMeters = null, durationSeconds = null).isShortRecording)
    }

    private fun parse(
        extensions: String,
        namespace: String = "https://glancemap.app/gpx/extensions/1",
    ) = CompanionGpxRouteParser.parse(
        """
        <gpx xmlns:x="$namespace"><metadata><extensions>$extensions</extensions></metadata>
            <trk><trkseg><trkpt lat="46" lon="7"/><trkpt lat="46.001" lon="7.001"/></trkseg></trk>
        </gpx>
        """.trimIndent().byteInputStream(),
    )

    private fun summary() =
        RecordedTraceSummary(
            activityProfile = "BIKE",
            durationSeconds = 60.0,
            totalDurationSeconds = 90.0,
            distanceMeters = 120.0,
            elevationGainMeters = 10.0,
            elevationLossMeters = 2.0,
            currentElevationMeters = 110.0,
            currentSpeedMps = 2f,
            averageSpeedMps = 2.0,
            fastestSpeedMps = 4.0,
            gpsAccuracyMeters = 5f,
            pointCount = 2,
            gpsActiveDurationSeconds = 60.0,
            recordingGapCount = 0,
            recordingMaxGapSeconds = 0.0,
            caloriesGrossKcal = 30.0,
            caloriesActiveKcal = 20.0,
            caloriesRestingKcal = 10.0,
            calorieModel = "test",
            cyclingMechanicalKj = 12.0,
            cyclingPowerSampleSegments = 1,
            cyclingPhysicsSegments = 0,
            heartRateBpm = 125,
            averageHeartRateBpm = 130,
            maxHeartRateBpm = 140,
            stepCount = 42,
            cadenceSpm = 70,
            averageCadenceSpm = 75,
            maxCadenceSpm = 90,
            powerWatts = 100,
            averagePowerWatts = 200,
            maxPowerWatts = 450,
            barometricPressureHpa = 1_000.0,
        )
}
