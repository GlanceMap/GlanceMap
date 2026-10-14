package com.glancemap.trailcore.recording

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionLogicTest {
    @Test
    fun sessionTracksElapsedTimeAcrossPauseAndResume() {
        val session = RecordingSessionLifecycle()
        session.start(startedAtMillis = 1_000L)
        session.pause(atMillis = 11_000L)
        assertEquals(10_000L, session.state.elapsedMillis(20_000L))
        session.resume(atMillis = 21_000L)
        assertEquals(11_000L, session.state.elapsedMillis(22_000L))
        assertEquals(RecordingSegmentStartReason.MANUAL_PAUSE, session.state.pendingSegmentStartReason)
    }

    @Test
    fun automaticPauseKeepsItsSegmentReasonAndStaleSaveCannotFinishNewSession() {
        val session = RecordingSessionLifecycle()
        val oldGeneration = session.start(startedAtMillis = 1L).generation
        session.pause(atMillis = 2L, automatic = true)
        session.resume(atMillis = 3L)
        assertEquals(RecordingSegmentStartReason.AUTO_PAUSE, session.state.pendingSegmentStartReason)
        session.beginSave()
        session.discard()
        session.start(startedAtMillis = 4L)
        val current = session.state
        assertEquals(current, session.saveSucceeded(oldGeneration))
        assertTrue(session.state.acceptsFixes)
    }

    @Test
    fun saveFailureLeavesSessionRetryableAndRecoveryStartsNewGeneration() {
        val session = RecordingSessionLifecycle()
        val generation = session.start(startedAtMillis = 10L).generation
        session.beginSave()
        assertEquals(RecordingSessionPhase.RECORDING, session.saveFailed(generation).phase)
        session.beginSave()
        assertEquals(RecordingSessionPhase.IDLE, session.saveSucceeded(generation).phase)

        val recovered = session.recover(100L, null, 50L, paused = false, autoPaused = false)
        assertEquals(RecordingSegmentStartReason.SESSION_RECOVERY, recovered.pendingSegmentStartReason)
        assertFalse(recovered.phase == RecordingSessionPhase.IDLE)
    }

    @Test
    fun orderedBatchPointsAreAcceptedAndOlderDeliveryIsRejected() {
        val first = recordingPoint(0.0, 1_000L)
        val second = recordingPoint(0.0001, 4_000L)
        val firstAppend = appendPoint(emptyList(), first, 1_000L, null)
        val secondAppend =
            appendPoint(firstAppend.canonical.points, second, 4_000L, firstAppend.nextLastElapsedRealtimeMillis)
        assertNotNull(secondAppend)
        assertNull(
            appendRecordingSessionPoint(
                existingPoints = secondAppend.canonical.points,
                point = recordingPoint(0.00005, 2_000L),
                options = options(),
                context =
                    RecordingSessionAppendContext(
                        elapsedRealtimeMillis = 2_000L,
                        previousElapsedRealtimeMillis = secondAppend.nextLastElapsedRealtimeMillis,
                        pendingSegmentStartReason = null,
                    ),
            ),
        )
    }

    @Test
    fun deliveredBatchIsOrderedByEachMonotonicTimestampAndKeepsEqualTimestampOrder() {
        val fixes = listOf("late", "first", "same-time")
        val timestamps = mapOf("late" to 4_000L, "first" to 1_000L, "same-time" to 1_000L)

        assertEquals(listOf("first", "same-time", "late"), orderRecordingFixBatch(fixes) { timestamps.getValue(it) })
    }

    @Test
    fun autoPauseGpsCadenceCombinesPauseMonitorWithTheSelectedRecordingPreset() {
        assertEquals(5_000L, effectiveRecordingAutoPauseGpsIntervalMillis(1_000L, 0L))
        assertEquals(10_000L, effectiveRecordingAutoPauseGpsIntervalMillis(10_000L, 30_000L))
        assertEquals(12_000L, effectiveRecordingAutoPauseGpsIntervalMillis(5_000L, 60_000L))
        assertEquals(Long.MAX_VALUE, effectiveRecordingAutoPauseGpsIntervalMillis(Long.MAX_VALUE, 60_000L))
    }

    @Test
    fun gapAndResumeBoundariesAreRetainedAndSmoothingUsesSharedOptions() {
        val first = recordingPoint(0.0, 1_000L)
        val second = recordingPoint(0.0001, 4_000L)
        val afterGap = recordingPoint(0.0002, 30_000L)
        val firstAppend = appendPoint(emptyList(), first, 1_000L, null)
        val secondAppend =
            appendPoint(firstAppend.canonical.points, second, 4_000L, firstAppend.nextLastElapsedRealtimeMillis)
        val resumed =
            appendRecordingSessionPoint(
                existingPoints = secondAppend.canonical.points,
                point = afterGap,
                options = options(RecordingSettings.RECORDING_TRACK_SMOOTHING_ADAPTIVE),
                context =
                    RecordingSessionAppendContext(
                        elapsedRealtimeMillis = 30_000L,
                        previousElapsedRealtimeMillis = secondAppend.nextLastElapsedRealtimeMillis,
                        pendingSegmentStartReason = RecordingSegmentStartReason.GPS_GAP,
                    ),
            )
        assertNotNull(resumed)
        assertTrue(resumed!!.point.startsNewSegment)
        assertEquals(RecordingSegmentStartReason.GPS_GAP, resumed.point.segmentStartReason)
        assertEquals(3, resumed.canonical.points.size)
    }

    @Test
    fun phoneAccuracyPolicyDoesNotApplyWatchGpsCorrection() {
        val rawAccuracy = RecordingSettings.RECORDING_WATCH_GPS_ACCURACY_FLOOR_M
        val phone = recordingAccuracyProvenance(rawAccuracy, watchGpsAccuracyFloorEligible = false)
        val watch = recordingAccuracyProvenance(rawAccuracy, watchGpsAccuracyFloorEligible = true)
        assertEquals(rawAccuracy, phone.effectiveAccuracyMeters)
        assertEquals(RECORDING_ACCURACY_INTERPRETATION_RAW, phone.interpretation)
        assertTrue(watch.effectiveAccuracyMeters != phone.effectiveAccuracyMeters)
    }

    private fun appendPoint(
        points: List<RecordingPoint>,
        point: RecordingPoint,
        elapsedMillis: Long,
        previousElapsedMillis: Long?,
    ): RecordingSessionPointAppend =
        requireNotNull(
            appendRecordingSessionPoint(
                existingPoints = points,
                point = point,
                options = options(),
                context =
                    RecordingSessionAppendContext(
                        elapsedRealtimeMillis = elapsedMillis,
                        previousElapsedRealtimeMillis = previousElapsedMillis,
                        pendingSegmentStartReason = null,
                    ),
            ),
        )

    private fun options(
        mode: String = RecordingSettings.RECORDING_TRACK_SMOOTHING_OFF,
    ): RecordingPointSmoothingOptions =
        RecordingPointSmoothingOptions(
            mode = mode,
            activityProfile = RecordingSettings.ACTIVITY_PROFILE_HIKE,
        )

    private fun recordingPoint(
        longitude: Double,
        timeMillis: Long,
    ): RecordingPoint =
        RecordingPoint(
            location = GeoPoint(latitude = 46.0, longitude = longitude),
            elevationMeters = null,
            timeMillis = timeMillis,
            accuracyMeters = 4f,
            speedMps = null,
        )
}
