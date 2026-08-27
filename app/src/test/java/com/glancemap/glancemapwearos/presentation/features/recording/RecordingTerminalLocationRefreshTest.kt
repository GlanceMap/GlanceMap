package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingTerminalLocationRefreshTest {
    @Test
    fun staleMovingBikePauseWaitsForAnAcceptedCanonicalEndpoint() {
        val refresh = RecordingTerminalLocationRefresh()
        val previous = recordedPoint(xMeters = 0.0, elapsedMillis = 0L, speedMps = 3f)

        assertTrue(
            refresh.begin(
                request = RecordingTerminalActionRequest(RecordingTerminalAction.PAUSE),
                input = staleBikeInput(),
                nowElapsedMillis = 10_000L,
            ),
        )

        val candidate = motionSample(xMeters = 38.0, elapsedMillis = 12_000L, speedMps = 3f)
        val motion = RecordingMovementConfidenceGate().evaluate(previous, candidate, BIKE)
        val qualityGate = RecordingFixQualityGate()
        qualityGate.evaluate(fixSample(xMeters = 0.0, elapsedMillis = 0L, speedMps = 3f), BIKE)
        val quality =
            qualityGate.evaluate(
                fixSample(xMeters = 38.0, elapsedMillis = 12_000L, speedMps = 3f),
                BIKE,
            )

        assertTrue(motion.accepted)
        assertTrue(quality.accepted)
        val canonical =
            appendCanonicalRecordingPoint(
                existingPoints = listOf(previous),
                point = recordedPoint(xMeters = 38.0, elapsedMillis = 12_000L, speedMps = 3f),
                options =
                    RecordingPointSmoothingOptions(
                        mode = SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                        activityProfile = BIKE,
                    ),
            )
        assertEquals(2, canonical.points.size)
        assertEquals(
            RecordingTerminalActionRequest(RecordingTerminalAction.PAUSE),
            refresh.takeActionAfterResolvedCandidate(candidate.elapsedRealtimeMillis),
        )
    }

    @Test
    fun stationaryCandidateCompletesRefreshWithoutAppendingAPoint() {
        val refresh = RecordingTerminalLocationRefresh()
        assertTrue(beginPauseRefresh(refresh))
        val points = mutableListOf(recordedPoint(xMeters = 0.0, elapsedMillis = 0L, speedMps = 3f))
        val motion =
            RecordingMovementConfidenceGate().evaluate(
                previous = points.last(),
                candidate = motionSample(xMeters = 2.0, elapsedMillis = 12_000L, speedMps = 0f),
                activityProfile = BIKE,
            )

        assertEquals(RecordingMotionStatus.SUPPRESSED, motion.status)
        assertEquals(RecordingMotionReason.STATIONARY_JITTER, motion.reason)
        assertEquals(1, points.size)
        assertEquals(
            RecordingTerminalActionRequest(RecordingTerminalAction.PAUSE),
            refresh.takeActionAfterResolvedCandidate(candidateElapsedMillis = 12_000L),
        )
        assertFalse(refresh.isPending)
    }

    @Test
    fun heldSlowProgressDoesNotCompleteRefreshImmediately() {
        val refresh = RecordingTerminalLocationRefresh()
        assertTrue(beginPauseRefresh(refresh))
        val motion =
            RecordingMovementConfidenceGate().evaluate(
                previous = recordedPoint(xMeters = 0.0, elapsedMillis = 0L, speedMps = 3f),
                candidate = motionSample(xMeters = 38.0, elapsedMillis = 12_000L, speedMps = 0.4f),
                activityProfile = BIKE,
            )

        assertEquals(RecordingMotionStatus.HELD, motion.status)
        assertEquals(RecordingMotionReason.UNCONFIRMED_SLOW_PROGRESS, motion.reason)
        assertTrue(refresh.isPending)
    }

    @Test
    fun qualityHeldCandidateDoesNotCompleteRefreshAndTimeoutStillSaves() {
        val refresh = RecordingTerminalLocationRefresh()
        assertTrue(
            refresh.begin(
                request = RecordingTerminalActionRequest(RecordingTerminalAction.SAVE, "Ride"),
                input = staleBikeInput(),
                nowElapsedMillis = 10_000L,
            ),
        )
        val qualityGate = RecordingFixQualityGate()
        qualityGate.evaluate(fixSample(xMeters = 0.0, elapsedMillis = 0L, speedMps = 3f), BIKE)
        val quality = qualityGate.evaluate(fixSample(xMeters = 1_000.0, elapsedMillis = 12_000L, speedMps = 3f), BIKE)

        assertEquals(RecordingFixQualityStatus.HELD, quality.status)
        assertTrue(refresh.isPending)
        assertEquals(
            RecordingTerminalActionRequest(RecordingTerminalAction.SAVE, "Ride"),
            refresh.takeActionOnTimeout(),
        )
    }

    @Test
    fun noFreshCandidateCompletesTheOriginalActionOnTimeout() {
        val refresh = RecordingTerminalLocationRefresh()

        assertTrue(beginPauseRefresh(refresh))
        assertEquals(RecordingTerminalActionRequest(RecordingTerminalAction.PAUSE), refresh.takeActionOnTimeout())
        assertNull(refresh.takeActionOnTimeout())
    }

    @Test
    fun freshLatestFixDoesNotRequestOrWait() {
        val refresh = RecordingTerminalLocationRefresh()

        assertFalse(
            refresh.begin(
                request = RecordingTerminalActionRequest(RecordingTerminalAction.PAUSE),
                input = staleBikeInput(latestLocationAgeMillis = 3_000L),
                nowElapsedMillis = 10_000L,
            ),
        )
        assertFalse(refresh.isPending)
    }

    @Test
    fun hikeAndAutoPausedSessionsDoNotEnterManualTerminalRefresh() {
        val refresh = RecordingTerminalLocationRefresh()

        assertFalse(
            refresh.begin(
                request = RecordingTerminalActionRequest(RecordingTerminalAction.PAUSE),
                input = staleBikeInput(activityProfile = HIKE),
                nowElapsedMillis = 10_000L,
            ),
        )
        assertFalse(
            refresh.begin(
                request = RecordingTerminalActionRequest(RecordingTerminalAction.PAUSE),
                input = staleBikeInput(paused = true),
                nowElapsedMillis = 10_000L,
            ),
        )
    }

    @Test
    fun cancelledPauseAndPromotedSaveRetainTheManualActionSemantics() {
        val refresh = RecordingTerminalLocationRefresh()
        assertTrue(beginPauseRefresh(refresh))
        assertTrue(
            refresh.begin(
                request = RecordingTerminalActionRequest(RecordingTerminalAction.SAVE, "Final ride"),
                input = staleBikeInput(),
                nowElapsedMillis = 10_500L,
            ),
        )

        assertEquals(
            RecordingTerminalActionRequest(RecordingTerminalAction.SAVE, "Final ride"),
            refresh.takeActionOnTimeout(),
        )
        assertTrue(beginPauseRefresh(refresh))
        assertTrue(refresh.cancelPause())
        assertNull(refresh.takeActionOnTimeout())
    }

    private fun beginPauseRefresh(refresh: RecordingTerminalLocationRefresh): Boolean =
        refresh.begin(
            request = RecordingTerminalActionRequest(RecordingTerminalAction.PAUSE),
            input = staleBikeInput(),
            nowElapsedMillis = 10_000L,
        )

    private fun staleBikeInput(
        activityProfile: String = BIKE,
        paused: Boolean = false,
        latestLocationAgeMillis: Long = 11_000L,
    ): RecordingTerminalLocationRefreshInput =
        RecordingTerminalLocationRefreshInput(
            active = true,
            paused = paused,
            saving = false,
            activityProfile = activityProfile,
            hasRecordedPoint = true,
            previousSpeedMps = 3f,
            acceptedPointAgeMillis = 11_000L,
            latestLocationAgeMillis = latestLocationAgeMillis,
            freshLocationMaxAgeMillis = 3_000L,
        )

    private fun recordedPoint(
        xMeters: Double,
        elapsedMillis: Long,
        speedMps: Float,
    ): RecordedTracePoint =
        RecordedTracePoint(
            latLong = latLongFromMeters(xMeters),
            elevationMeters = null,
            timeMillis = elapsedMillis,
            accuracyMeters = 7f,
            speedMps = speedMps,
        )

    private fun motionSample(
        xMeters: Double,
        elapsedMillis: Long,
        speedMps: Float,
    ): RecordingMotionSample =
        RecordingMotionSample(
            latLong = latLongFromMeters(xMeters),
            elapsedRealtimeMillis = elapsedMillis,
            accuracyMeters = 7f,
            speedMps = speedMps,
            speedAccuracyMps = 1f,
            stepCount = null,
            cadenceSpm = null,
        )

    private fun fixSample(
        xMeters: Double,
        elapsedMillis: Long,
        speedMps: Float,
    ): RecordingFixSample =
        RecordingFixSample(
            latLong = latLongFromMeters(xMeters),
            timeMillis = elapsedMillis,
            elapsedRealtimeMillis = elapsedMillis,
            accuracyMeters = 7f,
            speedMps = speedMps,
            speedAccuracyMps = 1f,
        )

    private fun latLongFromMeters(xMeters: Double): LatLong =
        LatLong(
            45.0,
            6.0 + Math.toDegrees(xMeters / (EARTH_RADIUS_METERS * COSINE_LATITUDE)),
        )

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val COSINE_LATITUDE = 0.7071067811865476
        const val BIKE = SettingsRepository.ACTIVITY_PROFILE_BIKE
        const val HIKE = SettingsRepository.ACTIVITY_PROFILE_HIKE
    }
}
