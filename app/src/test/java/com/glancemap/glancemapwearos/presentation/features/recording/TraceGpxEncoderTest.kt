package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class TraceGpxEncoderTest {
    @Test
    fun defaultRecordingTitleContainsDateStartTimeAndEndTime() {
        val startedAtMillis = localTime("2026-07-10T09:47:05")
        val endedAtMillis = localTime("2026-07-10T10:12:49")

        val title =
            buildRecordingTitle(
                startedAtMillis = startedAtMillis,
                endedAtMillis = endedAtMillis,
            )

        assertEquals("2026-07-10 09:47 10:12", title)
        assertFalse(title.contains("Recording", ignoreCase = true))
    }

    @Test
    fun defaultRecordingFileNameContainsStartAndEndTime() {
        val startedAtMillis = localTime("2026-07-10T09:47:05")
        val endedAtMillis = localTime("2026-07-10T10:12:49")

        assertEquals(
            "2026-07-10-09-47-10-12.gpx",
            buildRecordingFileName(
                startedAtMillis = startedAtMillis,
                endedAtMillis = endedAtMillis,
            ),
        )
    }

    @Test
    fun encodeRecordedTraceAsGpxStartsNewTrackSegmentAfterResume() {
        val points =
            listOf(
                recordedPoint(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L),
                recordedPoint(latitude = 45.0001, longitude = 6.0001, timeMillis = 2_000L),
                recordedPoint(
                    latitude = 45.001,
                    longitude = 6.001,
                    timeMillis = 60_000L,
                    startsNewSegment = true,
                    segmentStartReason = RecordingSegmentStartReason.GPS_GAP,
                ),
            )

        val xml =
            encodeRecordedTraceAsGpx(
                title = "Paused recording",
                points = points,
            ).toString(Charsets.UTF_8)

        assertEquals(2, "<trkseg>".toRegex().findAll(xml).count())
        assertEquals(listOf(2, 1), recordedTraceSegments(points).map(List<RecordedTracePoint>::size))
        assertTrue(xml.contains("<gmap:segmentStartReason>GPS_GAP</gmap:segmentStartReason>"))
    }

    @Test
    fun fiveMeterManualPauseIsVisuallyContinuous() {
        val points = pauseBoundary(displacementMeters = 5.0, reason = RecordingSegmentStartReason.MANUAL_PAUSE)

        val xml = encodeRecordedTraceAsGpx(title = "Pause", points = points).toString(Charsets.UTF_8)

        assertEquals(listOf(2), recordedTraceSegments(points).map(List<RecordedTracePoint>::size))
        assertEquals(1, "<trkseg>".toRegex().findAll(xml).count())
        assertTrue(xml.contains("<gmap:segmentStartReason>MANUAL_PAUSE</gmap:segmentStartReason>"))
    }

    @Test
    fun twentyFiveMeterAutoPauseWithNormalAccuracyIsVisuallyContinuous() {
        val points = pauseBoundary(displacementMeters = 25.0, reason = RecordingSegmentStartReason.AUTO_PAUSE)

        assertEquals(listOf(2), recordedTraceSegments(points).map(List<RecordedTracePoint>::size))
    }

    @Test
    fun pauseBridgeUsesCombinedEndpointAccuracyAroundThirtyMeters() {
        val normalAccuracy = pauseBoundary(displacementMeters = 33.0, reason = RecordingSegmentStartReason.MANUAL_PAUSE)
        val widerAccuracy =
            pauseBoundary(
                displacementMeters = 33.0,
                reason = RecordingSegmentStartReason.MANUAL_PAUSE,
                beforeAccuracyMeters = 20f,
                afterAccuracyMeters = 20f,
            )

        assertEquals(2, recordedTraceSegments(normalAccuracy).size)
        assertEquals(1, recordedTraceSegments(widerAccuracy).size)
    }

    @Test
    fun largePausedDisplacementRemainsSeparateTrackSegment() {
        val points = pauseBoundary(displacementMeters = 100.0, reason = RecordingSegmentStartReason.AUTO_PAUSE)

        assertEquals(listOf(1, 1), recordedTraceSegments(points).map(List<RecordedTracePoint>::size))
    }

    @Test
    fun encodeRecordedTraceAsGpxWritesCoreTrackPointAndExtensions() {
        val bytes =
            encodeRecordedTraceAsGpx(
                title = "Morning Test",
                points =
                    listOf(
                        RecordedTracePoint(
                            latLong = LatLong(45.123456789, 6.987654321),
                            elevationMeters = 1234.56,
                            timeMillis = Instant.parse("2026-06-10T10:15:30Z").toEpochMilli(),
                            accuracyMeters = 7.5f,
                            speedMps = 1.25f,
                            elevationSource = "DEM",
                            heartRateBpm = 142,
                            stepCount = 87,
                            cadenceSpm = 164,
                            barometricPressureHpa = 913.42,
                        ),
                    ),
            )

        val xml = bytes.toString(Charsets.UTF_8)

        assertTrue(xml.contains("creator=\"GlanceMap\""))
        assertTrue(xml.contains("xmlns:gmap=\"https://glancemap.app/gpx/extensions/1\""))
        assertTrue(xml.contains("lat=\"45.12345679\""))
        assertTrue(xml.contains("lon=\"6.98765432\""))
        assertTrue(xml.contains("<ele>1234.6</ele>"))
        assertTrue(xml.contains("<time>2026-06-10T10:15:30Z</time>"))
        assertTrue(xml.contains("<extensions>"))
        assertTrue(xml.contains("<gmap:accuracyMeters>7.50</gmap:accuracyMeters>"))
        assertTrue(xml.contains("<gmap:speedMps>1.25</gmap:speedMps>"))
        assertTrue(xml.contains("<gmap:elevationSource>DEM</gmap:elevationSource>"))
        assertTrue(xml.contains("<gmap:heartRateBpm>142</gmap:heartRateBpm>"))
        assertTrue(xml.contains("<gmap:stepCount>87</gmap:stepCount>"))
        assertTrue(xml.contains("<gmap:cadenceSpm>164</gmap:cadenceSpm>"))
        assertTrue(xml.contains("<gmap:pressureHpa>913.42</gmap:pressureHpa>"))
    }

    @Test
    fun encodeRecordedTraceAsGpxWritesActivityProfileAndCalorieModelSummary() {
        val bytes =
            encodeRecordedTraceAsGpx(
                title = "Bike Test",
                points = listOf(recordedPoint(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L)),
                summary = recordingSummary(SettingsRepository.RECORDING_SENSOR_SOURCE_POD),
            )

        val xml = bytes.toString(Charsets.UTF_8)

        assertTrue(xml.contains("<gmap:activityProfile>BIKE</gmap:activityProfile>"))
        assertTrue(xml.contains("<gmap:recordingTrackSmoothingMode>ADAPTIVE</gmap:recordingTrackSmoothingMode>"))
        assertTrue(
            xml.contains(recordingDistanceSourceTag(SettingsRepository.RECORDING_SENSOR_SOURCE_POD)),
        )
        assertTrue(xml.contains("<gmap:recordingTrackFilterVersion>1</gmap:recordingTrackFilterVersion>"))
        assertSmartElevationSummaryExtensions(xml)
        assertTrue(xml.contains("<gmap:calorieModel>cycling_physics_fallback_v1</gmap:calorieModel>"))
        assertTrue(xml.contains("<gmap:cyclingMechanicalKj>202.40</gmap:cyclingMechanicalKj>"))
        assertFalse(xml.contains("<gmap:cyclingPowerSampleSegments>"))
        assertTrue(xml.contains("<gmap:cyclingPhysicsSegments>9</gmap:cyclingPhysicsSegments>"))
    }

    @Test
    fun recordingDistanceSourceUsesTheStoredSummaryValueAndOmitsBlankValues() {
        val watchGps =
            encodeRecordedTraceAsGpx(
                title = "Watch GPS",
                points = listOf(recordedPoint(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L)),
                summary = recordingSummary(SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS),
            ).toString(Charsets.UTF_8)
        val blank =
            encodeRecordedTraceAsGpx(
                title = "Blank source",
                points = listOf(recordedPoint(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L)),
                summary = recordingSummary(" "),
            ).toString(Charsets.UTF_8)

        assertTrue(
            watchGps.contains(
                recordingDistanceSourceTag(SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS),
            ),
        )
        assertFalse(blank.contains("<gmap:recordingDistanceSource>"))
    }

    @Test
    fun encodeRecordedTraceAsGpxWritesExtensionsForSensorOnlyPointData() {
        val bytes =
            encodeRecordedTraceAsGpx(
                title = "Sensor Test",
                points =
                    listOf(
                        RecordedTracePoint(
                            latLong = LatLong(45.0, 6.0),
                            elevationMeters = null,
                            timeMillis = Instant.parse("2026-06-10T10:15:30Z").toEpochMilli(),
                            accuracyMeters = null,
                            speedMps = null,
                            elevationSource = null,
                            heartRateBpm = 138,
                        ),
                    ),
            )

        val xml = bytes.toString(Charsets.UTF_8)

        assertTrue(xml.contains("<extensions>"))
        assertTrue(xml.contains("<gmap:heartRateBpm>138</gmap:heartRateBpm>"))
    }

    @Test
    fun encodeRecordedTraceAsGpxSkipsExtensionsWhenNoExtraPointDataExists() {
        val bytes =
            encodeRecordedTraceAsGpx(
                title = "Plain Test",
                points =
                    listOf(
                        RecordedTracePoint(
                            latLong = LatLong(45.0, 6.0),
                            elevationMeters = null,
                            timeMillis = Instant.parse("2026-06-10T10:15:30Z").toEpochMilli(),
                            accuracyMeters = null,
                            speedMps = null,
                            elevationSource = null,
                        ),
                    ),
            )

        val xml = bytes.toString(Charsets.UTF_8)

        assertTrue(xml.contains("<trkpt"))
        assertEquals(1, "<extensions>".toRegex().findAll(xml).count())
        assertFalse(xml.substringAfter("<trkpt").substringBefore("</trkpt>").contains("<extensions>"))
        assertFalse(xml.contains("gmap:accuracyMeters"))
    }

    @Suppress("LongParameterList")
    private fun recordedPoint(
        latitude: Double,
        longitude: Double,
        timeMillis: Long,
        startsNewSegment: Boolean = false,
        segmentStartReason: String? = null,
        accuracyMeters: Float? = 8f,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = LatLong(latitude, longitude),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = accuracyMeters,
            speedMps = 1f,
            startsNewSegment = startsNewSegment,
            segmentStartReason = segmentStartReason,
        )

    private fun recordingSummary(recordingDistanceSource: String?) =
        RecordedTraceSummary(
            activityProfile = "BIKE",
            durationSeconds = 600.0,
            totalDurationSeconds = 620.0,
            distanceMeters = 4_000.0,
            elevationGainMeters = 50.0,
            elevationLossMeters = 20.0,
            currentElevationMeters = 300.0,
            currentSpeedMps = 6.0f,
            averageSpeedMps = 6.67,
            fastestSpeedMps = 8.4,
            gpsAccuracyMeters = 5.0f,
            pointCount = 10,
            gpsActiveDurationSeconds = 590.0,
            recordingGapCount = 0,
            recordingMaxGapSeconds = 0.0,
            caloriesGrossKcal = 220.0,
            caloriesActiveKcal = 210.0,
            caloriesRestingKcal = 10.0,
            calorieModel = "cycling_physics_fallback_v1",
            cyclingMechanicalKj = 202.4,
            cyclingPowerSampleSegments = 0,
            cyclingPhysicsSegments = 9,
            heartRateBpm = 130,
            averageHeartRateBpm = 128,
            maxHeartRateBpm = 142,
            stepCount = null,
            cadenceSpm = 82,
            averageCadenceSpm = 80,
            maxCadenceSpm = 96,
            powerWatts = null,
            averagePowerWatts = null,
            maxPowerWatts = null,
            barometricPressureHpa = null,
            recordingTrackSmoothingMode = "ADAPTIVE",
            recordingDistanceSource = recordingDistanceSource,
            recordingTrackFilterVersion = 1,
            recordingElevationFilterVersion = 2,
            smartElevationPressurePointCount = 8,
            smartElevationDemAnchorPointCount = 10,
            smartElevationGpsFallbackPointCount = 2,
        )

    private fun recordingDistanceSourceTag(value: String): String {
        val tagName = "gmap:recordingDistanceSource"
        return "<$tagName>$value</$tagName>"
    }

    private fun pauseBoundary(
        displacementMeters: Double,
        reason: String,
        beforeAccuracyMeters: Float? = 8f,
        afterAccuracyMeters: Float? = 8f,
    ): List<RecordedTracePoint> =
        listOf(
            recordedPoint(
                latitude = 45.0,
                longitude = 6.0,
                timeMillis = 1_000L,
                accuracyMeters = beforeAccuracyMeters,
            ),
            recordedPoint(
                latitude = 45.0 + displacementMeters / 111_320.0,
                longitude = 6.0,
                timeMillis = 2_000L,
                startsNewSegment = true,
                segmentStartReason = reason,
                accuracyMeters = afterAccuracyMeters,
            ),
        )

    private fun assertSmartElevationSummaryExtensions(xml: String) {
        assertSummaryExtension(xml, "recordingElevationFilterVersion", "2")
        assertSummaryExtension(xml, "smartElevationPressurePointCount", "8")
        assertSummaryExtension(xml, "smartElevationDemAnchorPointCount", "10")
        assertSummaryExtension(xml, "smartElevationGpsFallbackPointCount", "2")
    }

    private fun assertSummaryExtension(
        xml: String,
        name: String,
        value: String,
    ) {
        assertTrue(xml.contains("<gmap:$name>$value</gmap:$name>"))
    }

    private fun localTime(value: String): Long =
        LocalDateTime
            .parse(value)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
