package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingTraceSmoothingTest {
    @Test
    fun lowSpeedMovementInsideAccuracyDeadbandIsSuppressed() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)
        val result =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00001, elapsedMillis = 8_000L, speedMps = 0.2f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
        assertEquals(RecordingMotionReason.STATIONARY_JITTER, result.reason)
    }

    @Test
    fun normalWalkingMovementIsPreserved() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 1.1f)
        val result =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00001, elapsedMillis = 8_000L, speedMps = 1.2f),
                activityProfile = HIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.REPORTED_MOTION, result.reason)
    }

    @Test
    fun unreliableReportedSpeedDoesNotReleaseStationaryJitter() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)
        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(latitude = 45.00001, elapsedMillis = 8_000L, speedMps = 1.2f)
                        .copy(speedAccuracyMps = 3f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
    }

    @Test
    fun stationaryWanderingRemainsSuppressedWithoutKeepalive() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)
        val result =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00001, elapsedMillis = 61_000L, speedMps = 0.1f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, result.status)
    }

    @Test
    fun freshStepsPreserveVerySlowHikingMovement() {
        val gate = RecordingMovementConfidenceGate()
        val previous =
            point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)
                .copy(stepCount = 100)
        val result =
            gate.evaluate(
                previous = previous,
                candidate =
                    sample(
                        latitude = 45.00001,
                        elapsedMillis = 8_000L,
                        speedMps = 0.2f,
                        stepCount = 106,
                    ),
                activityProfile = HIKE,
            )

        assertTrue(result.accepted)
        assertEquals(RecordingMotionReason.SENSOR_MOTION, result.reason)
    }

    @Test
    fun consistentSlowProgressIsAcceptedWithoutMotionSensors() {
        val gate = RecordingMovementConfidenceGate()
        val previous = point(latitude = 45.0, longitude = 6.0, timeMillis = 1_000L, speedMps = 0.1f)

        val first =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00009, elapsedMillis = 8_000L, speedMps = 0.2f),
                activityProfile = HIKE,
            )
        val confirmed =
            gate.evaluate(
                previous = previous,
                candidate = sample(latitude = 45.00013, elapsedMillis = 15_000L, speedMps = 0.2f),
                activityProfile = HIKE,
            )

        assertEquals(RecordingMotionStatus.HELD, first.status)
        assertTrue(confirmed.accepted)
        assertEquals(RecordingMotionReason.CONFIRMED_SLOW_PROGRESS, confirmed.reason)
    }

    private fun sample(
        latitude: Double,
        elapsedMillis: Long,
        speedMps: Float,
        stepCount: Int? = null,
    ): RecordingMotionSample =
        RecordingMotionSample(
            latLong = LatLong(latitude, 6.0),
            elapsedRealtimeMillis = elapsedMillis,
            accuracyMeters = 12f,
            speedMps = speedMps,
            speedAccuracyMps = 0.2f,
            stepCount = stepCount,
            cadenceSpm = null,
        )

    private fun point(
        latitude: Double,
        longitude: Double,
        timeMillis: Long,
        speedMps: Float,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = LatLong(latitude, longitude),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = 12f,
            speedMps = speedMps,
        )

    private companion object {
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
    }
}
