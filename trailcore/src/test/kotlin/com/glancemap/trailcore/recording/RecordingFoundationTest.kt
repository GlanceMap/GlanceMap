package com.glancemap.trailcore.recording

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFoundationTest {
    @Test
    fun recordingPoliciesKeepPresetDefaultsAndScreenOffModes() {
        assertEquals(
            RecordingGpsUsageTiming(1, 3, 1, RecordingSettings.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS),
            recordingGpsUsageTiming(RecordingSettings.GPS_USAGE_PROFILE_BEST_TRACE, RecordingSettings.ACTIVITY_PROFILE_BIKE),
        )
        assertEquals(
            RecordingGpsUsageTiming(5, 20, 5, RecordingSettings.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS),
            recordingGpsUsageTiming(RecordingSettings.GPS_USAGE_PROFILE_LONG_BATTERY, RecordingSettings.ACTIVITY_PROFILE_HIKE),
        )
        assertEquals(
            RecordingGpsUsageTiming(1, 5, 1, RecordingSettings.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS),
            recordingGpsUsageTiming(RecordingSettings.GPS_USAGE_PROFILE_BALANCED, RecordingSettings.ACTIVITY_PROFILE_BIKE),
        )
        assertEquals(
            RecordingSettings.GPS_USAGE_PROFILE_CUSTOM,
            inferRecordingGpsUsageProfile(
                RecordingSettings.ACTIVITY_PROFILE_HIKE,
                RecordingGpsUsageTiming(2, 9, 2, RecordingSettings.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS),
            ),
        )
        assertEquals(10, RecordingSettings.normalizeRecordingScreenOffGpsInterval(-2, 10))
        assertEquals(-1, RecordingSettings.normalizeRecordingScreenOffGpsInterval(-1, 10))
        assertEquals(0, RecordingSettings.normalizeRecordingScreenOffGpsInterval(0, 10))
        assertEquals(10, RecordingSettings.normalizeRecordingScreenOffGpsInterval(61, 10))
        assertEquals(12_000L, recordingScreenOffIntervalMillis(3_000L, 12))
        assertEquals(3_000L, recordingScreenOffIntervalMillis(3_000L, 0))
        assertEquals(Long.MAX_VALUE, recordingScreenOffIntervalMillis(3_000L, -1))
    }

    @Test
    fun settingsNormalizationPreservesLegacyActivityAndSourceSemantics() {
        assertEquals(RecordingSettings.ACTIVITY_PROFILE_HIKE, RecordingSettings.normalizeActivityProfile("WALK_HIKE"))
        assertEquals(RecordingSettings.ACTIVITY_PROFILE_HIKE, RecordingSettings.normalizeActivityProfile("invalid"))
        assertEquals(RecordingSettings.ACTIVITY_PROFILE_BIKE, RecordingSettings.normalizeActivityProfile("BIKE"))
        assertEquals(
            RecordingSettings.RECORDING_AUTO_PAUSE_ALWAYS,
            RecordingSettings.normalizeLegacyAutoPauseMode("BIKE_ONLY", RecordingSettings.ACTIVITY_PROFILE_BIKE),
        )
        assertEquals(
            RecordingSettings.RECORDING_AUTO_PAUSE_OFF,
            RecordingSettings.normalizeLegacyAutoPauseMode("BIKE_ONLY", RecordingSettings.ACTIVITY_PROFILE_HIKE),
        )
        assertEquals(
            RecordingSettings.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
            RecordingSettings.normalizeAutoPauseMode("BIKE_ONLY"),
        )
        assertEquals(
            RecordingSettings.RECORDING_ELEVATION_SOURCE_AUTO,
            RecordingSettings.normalizeElevationSource("unknown"),
        )
        assertEquals(
            RecordingSettings.RECORDING_SENSOR_SOURCE_POD,
            RecordingSettings.normalizeSensorSource("POD", RecordingSettings.DEFAULT_RECORDING_SPEED_SOURCE),
        )
        assertEquals(
            RecordingSettings.DEFAULT_RECORDING_STEPS_SOURCE,
            RecordingSettings.normalizeStepsSource("POD"),
        )
        assertEquals(1_000, RecordingSettings.normalizeProgressDistanceMeters(1_249))
        assertEquals(1_000, RecordingSettings.normalizeProgressDistanceMeters(1_250))
        assertEquals(RecordingSettings.RECORDING_TRACK_SMOOTHING_ADAPTIVE, RecordingSettings.normalizeTrackSmoothingMode(null))
    }

    @Test
    fun phoneLikeAccuracyIsNotWatchFloorCorrected() {
        val phoneLike = recordingAccuracyProvenance(rawAccuracyMeters = 125f, watchGpsAccuracyFloorEligible = false)
        assertEquals(125f, phoneLike.effectiveAccuracyMeters ?: -1f, 0f)
        assertEquals(RECORDING_ACCURACY_INTERPRETATION_RAW, phoneLike.interpretation)

        val watchEligible = recordingAccuracyProvenance(rawAccuracyMeters = 125f, watchGpsAccuracyFloorEligible = true)
        assertEquals(18f, watchEligible.effectiveAccuracyMeters ?: -1f, 0f)
        assertEquals(RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS, watchEligible.interpretation)
        assertEquals(125f, phoneLike.effectiveAccuracyMeters ?: -1f, 0f)
    }

    @Test
    fun sharedFixQualityRejectsRawPhoneLike125MeterAccuracy() {
        val sample =
            RecordingFixSample(
                location = GeoPoint(45.0, 6.0),
                timeMillis = 1_000L,
                elapsedRealtimeMillis = 1_000L,
                accuracyMeters = 125f,
                speedMps = 0f,
                speedAccuracyMps = null,
            )
        val result = RecordingFixQualityGate().evaluate(sample, RecordingSettings.ACTIVITY_PROFILE_HIKE)

        assertEquals(125f, sample.accuracyMeters ?: -1f, 0f)
        assertEquals(RecordingFixQualityStatus.REJECTED, result.status)
        assertEquals(RecordingFixQualityReason.POOR_ACCURACY, result.reason)
    }

    @Test
    fun distanceSessionResetRetainsPriorProgress() {
        assertEquals(
            RecordingExternalDistanceSessionState(baseMeters = -74.0, sessionMeters = 74.0),
            advanceRecordingExternalDistanceSession(totalDistanceMeters = 0.0, baseMeters = 0.0, previousSessionMeters = 74.0),
        )
        assertNull(advanceRecordingExternalDistanceSession(Double.NaN, baseMeters = null, previousSessionMeters = null))
    }

    @Test
    fun accuracyAndPointModelsKeepUnavailableSensorValuesNullable() {
        val point = RecordingPoint(GeoPoint(45.0, 6.0), null, 1L, null, null)

        assertNull(point.elevationMeters)
        assertNull(point.accuracyMeters)
        assertNull(point.heartRateBpm)
        assertTrue(point.startsNewSegment.not())
    }
}
