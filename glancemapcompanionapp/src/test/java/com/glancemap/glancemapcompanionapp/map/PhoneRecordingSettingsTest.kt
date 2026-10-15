package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.recording.RecordingCallbackGapTracker
import com.glancemap.trailcore.recording.RecordingDelayedDeliveryEvidence
import com.glancemap.trailcore.recording.RecordingMotionReason
import com.glancemap.trailcore.recording.RecordingMotionSample
import com.glancemap.trailcore.recording.RecordingMovementConfidenceGate
import com.glancemap.trailcore.recording.RecordingPoint
import com.glancemap.trailcore.recording.RecordingSettings
import com.glancemap.trailcore.recording.RecordingWatchGpsDistanceGeometry
import com.glancemap.trailcore.recording.recordingGpsUsageTiming
import com.glancemap.trailcore.recording.recordingHaversineMeters
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneRecordingSettingsTest {
    @Test
    fun changingActivityPreservesCustomGpsIntervals() {
        val custom =
            PhoneRecordingSettings(
                gpsUsageProfile = RecordingSettings.GPS_USAGE_PROFILE_CUSTOM,
                screenOnIntervalSeconds = 5,
                screenOffIntervalSeconds = 20,
            )
        assertEquals(custom, custom.withGpsPreset(PhoneActivityProfile.BIKE, custom.gpsUsageProfile))
    }

    @Test
    fun phonePresetsUseTheSharedWatchTiming() {
        for (profile in PhoneActivityProfile.entries) {
            for (preset in listOf("BEST_TRACE", "BALANCED", "LONG_BATTERY")) {
                val phone = PhoneRecordingSettings().withGpsPreset(profile, preset)
                val shared = recordingGpsUsageTiming(preset, profile.storageValue)
                assertEquals(shared.recordingScreenOnSeconds, phone.screenOnIntervalSeconds)
                assertEquals(shared.recordingScreenOffSeconds, phone.screenOffIntervalSeconds)
            }
        }
    }

    @Test
    fun sparseScreenOffFixesKeepConfiguredSamplingAndActualContinuitySeparate() {
        val settings = PhoneRecordingSettings(screenOnIntervalSeconds = 5, screenOffIntervalSeconds = 20)
        for (profile in PhoneActivityProfile.entries) {
            val options = settings.smoothingOptions(profile, 20_000L)
            assertEquals(5, options.sampleIntervalSeconds)
            assertEquals(5, settings.sampleIntervalSeconds)
            assertEquals(20, options.trajectoryContinuityIntervalSeconds)
        }
    }

    @Test
    fun screenOffDistanceSmoothingUsesTheWatchConfiguredCadence() {
        val settings = PhoneRecordingSettings(screenOnIntervalSeconds = 3, screenOffIntervalSeconds = 10)
        val points =
            listOf(
                distancePoint(0.0, 0.0, 1_000L),
                distancePoint(20.0, 3.0, 11_000L),
                distancePoint(40.0, 0.0, 21_000L),
            )
        val geometry = RecordingWatchGpsDistanceGeometry()
        val segments =
            points.mapNotNull { point ->
                geometry.append(
                    current = point,
                    isContinuityRecovery = false,
                    activityProfile = "HIKE",
                    sampleIntervalSeconds = settings.sampleIntervalSeconds,
                )
            }
        // Ten-second legs exceed the watch's configured three-second smoothing window.
        assertEquals(points[1], segments.single().current)
        assertEquals(
            recordingHaversineMeters(points[0].location, points[1].location),
            segments.single().geometricDeltaMeters,
            0.0,
        )
    }

    @Test
    fun delayedBikeEndpointAfterWakeUsesTheScreenOffDeliveryEvidence() {
        val settings = PhoneRecordingSettings(screenOnIntervalSeconds = 1, screenOffIntervalSeconds = 5)
        val tracker = RecordingCallbackGapTracker(settings.screenOffIntervalSeconds * 1_000L)
        tracker.observeCallback(1_000L)
        tracker.updateEffectiveInterval(settings.screenOnIntervalSeconds * 1_000L)
        val timing = tracker.observeCallback(21_000L)
        val previous = distancePoint(0.0, 0.0, 1_000L).copy(accuracyMeters = 7f, speedMps = 1.46f)
        val candidate =
            RecordingMotionSample(
                location = distancePoint(38.0, 0.0, 21_000L).location,
                elapsedRealtimeMillis = 21_000L,
                accuracyMeters = 9.6f,
                speedMps = 0.47f,
                speedAccuracyMps = null,
                stepCount = null,
                cadenceSpm = null,
                delayedDelivery =
                    RecordingDelayedDeliveryEvidence(20_000L, timing.callbackGapMillis, timing.expectedIntervalMillis),
            )

        assertEquals(5_000L, timing.expectedIntervalMillis)
        assertEquals(
            RecordingMotionReason.DELAYED_DELIVERY_RECOVERY,
            RecordingMovementConfidenceGate().evaluate(previous, candidate, "BIKE").reason,
        )
        assertEquals(
            RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS,
            RecordingMovementConfidenceGate().evaluate(previous, candidate.copy(delayedDelivery = null), "BIKE").reason,
        )
    }

    private fun distancePoint(
        xMeters: Double,
        yMeters: Double,
        timeMillis: Long,
    ) = RecordingPoint(
        location = GeoPoint(Math.toDegrees(yMeters / 6_371_000.0), Math.toDegrees(xMeters / 6_371_000.0)),
        elevationMeters = null,
        timeMillis = timeMillis,
        accuracyMeters = 12f,
        speedMps = 2f,
    )
}
