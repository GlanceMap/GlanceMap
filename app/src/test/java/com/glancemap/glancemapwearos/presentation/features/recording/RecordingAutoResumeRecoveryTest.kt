package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.core.service.location.service.sortBatchByTimestamp
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingAutoResumeRecoveryTest {
    @Test
    fun bikeAndWalkingRestartsKeepMeasuredTimesInOrder() {
        listOf(SettingsRepository.ACTIVITY_PROFILE_BIKE, SettingsRepository.ACTIVITY_PROFILE_HIKE).forEach { profile ->
            val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
            buffer.offer(fix(0, 10_000, 0.0), eligible = true)
            buffer.offer(fix(1, 15_000, 20.0), eligible = true)
            buffer.offer(fix(2, 20_000, 40.0), eligible = true)
            assertEquals(listOf(0, 1, 2), buffer.recover(profile).map { it.value })
            assertEquals(listOf(10_000L, 15_000L, 20_000L), buffer.recover(profile).map { it.point.timeMillis })
        }
    }

    @Test
    fun stationaryDriftAndAbandonedFalseStartAreNotRecovered() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        buffer.offer(fix(0, 10_000, 0.0), eligible = true)
        buffer.offer(fix(1, 15_000, 1.0), eligible = true)
        assertTrue(buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE).isEmpty())
        buffer.offer(fix(2, 20_000, 20.0), eligible = false)
        buffer.offer(fix(3, 25_000, 50.0), eligible = true)
        assertTrue(buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE).isEmpty())
    }

    @Test
    fun walkingProgressCanBeConfirmedAcrossSeveralSmallSteps() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        listOf(0.0, 6.0, 12.0).forEachIndexed { index, meters ->
            val sample = fix(index, 10_000L + index * 5_000L, meters)
            buffer.offer(sample.copy(point = sample.point.copy(speedMps = 1.2f)), eligible = true)
        }
        assertEquals(listOf(0, 1, 2), buffer.recover(SettingsRepository.ACTIVITY_PROFILE_HIKE).map { it.value })
    }

    @Test
    fun manualPauseSavingAndDisabledAutoPauseDoNotQualifyForBuffering() {
        val auto =
            TraceRecordingUiState(active = true, paused = true, autoPaused = true, recordingAutoPauseEnabled = true)
        assertTrue(auto.canBufferAutoResumeFix())
        assertTrue(!auto.copy(autoPaused = false).canBufferAutoResumeFix())
        assertTrue(!auto.copy(saving = true).canBufferAutoResumeFix())
        assertTrue(!auto.copy(active = false).canBufferAutoResumeFix())
        assertTrue(!auto.copy(recordingAutoPauseEnabled = false).canBufferAutoResumeFix())
    }

    @Test
    fun implausibleJumpCannotConfirmRestart() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        buffer.offer(fix(0, 10_000, 0.0), eligible = true)
        buffer.offer(fix(1, 15_000, 1_000.0), eligible = true)
        assertTrue(buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE).isEmpty())
    }

    @Test
    fun recoveredDistanceAndActiveTimeExcludeThePauseConnector() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        buffer.offer(fix(0, 10_000, 0.0), eligible = true)
        buffer.offer(fix(1, 15_000, 20.0), eligible = true)
        buffer.offer(fix(2, 20_000, 40.0), eligible = true)
        val recovered = buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE).map { it.point }
        val before = fix(-1, 1_000, -200.0).point
        val points =
            listOf(before) +
                recovered.mapIndexed { index, point ->
                    if (index == 0) {
                        point.copy(startsNewSegment = true, segmentStartReason = RecordingSegmentStartReason.AUTO_PAUSE)
                    } else {
                        point
                    }
                }
        val geometry = RecordingWatchGpsDistanceGeometry()
        geometry.append(before, false, SettingsRepository.ACTIVITY_PROFILE_BIKE, 5)
        geometry.reset()
        var distance = 0.0

        fun add(segment: RecordingWatchGpsDistanceSegment?) {
            if (segment == null) return
            distance +=
                estimateRecordingDistanceDelta(
                    RecordingDistanceInput(
                        segment.geometricDeltaMeters,
                        segment.previous,
                        segment.current,
                        segment.elapsedSincePreviousMs,
                        SettingsRepository.ACTIVITY_PROFILE_BIKE,
                        segment.isContinuityRecovery,
                    ),
                ).distanceMeters
        }
        points.drop(1).forEach { add(geometry.append(it, false, SettingsRepository.ACTIVITY_PROFILE_BIKE, 5)) }
        add(geometry.flush())
        assertEquals(recordingCanonicalPathDistance(points), distance, 0.1)
        assertEquals(40.0, distance, 0.2)
        val paused =
            TraceRecordingUiState(
                active = true,
                paused = true,
                autoPaused = true,
                startedAtMillis = 1_000,
                pausedAtMillis = 2_000,
            )
        val added = autoPauseAddedMillisAtResume(paused, 20_000, 10_000)
        val resumed =
            paused.copy(paused = false, autoPaused = false, pausedAtMillis = null, accumulatedPausedMillis = added)
        assertEquals(8_000L, added)
        assertEquals(11_000L, recordingActiveDurationMillis(resumed, 20_000))
        assertEquals(10_000L, recovered.last().timeMillis - recovered.first().timeMillis)
    }

    @Test
    fun delayedConfirmationKeepsOnlyABoundedRecentWindow() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        repeat(30) { index -> buffer.offer(fix(index, 10_000L + index * 5_000L, index * 20.0), eligible = true) }
        val recovered = buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE)
        assertTrue(recovered.isNotEmpty())
        assertTrue(recovered.size <= 13)
        assertTrue(recovered.last().elapsedMillis - recovered.first().elapsedMillis <= 60_000)
    }

    @Test
    fun aDelayedBatchIsRecoveredInMeasurementOrder() {
        val batch = listOf(fix(2, 20_000, 40.0), fix(0, 10_000, 0.0), fix(1, 15_000, 20.0))
        val ordered =
            sortBatchByTimestamp(
                items = batch,
                elapsedRealtimeNanosOf = { it.elapsedMillis * 1_000_000L },
                wallClockTimeMsOf = { it.point.timeMillis },
            )
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        ordered.forEach { buffer.offer(it, eligible = true) }
        assertEquals(listOf(0, 1, 2), buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE).map { it.value })
    }

    @Test
    fun batchTimestampsDuplicatesInvalidAndOutOfOrderSamplesAreNotReplayed() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        buffer.offer(fix(0, 10_000, 0.0), eligible = true)
        buffer.offer(fix(1, 10_000, 100.0), eligible = true)
        buffer.offer(fix(2, 9_000, 100.0), eligible = true)
        val invalid = fix(3, 15_000, 10.0)
        buffer.offer(invalid.copy(point = invalid.point.copy(effectiveAccuracyMeters = Float.NaN)), eligible = true)
        buffer.offer(fix(4, 15_000, 20.0), eligible = true)
        buffer.offer(fix(5, 25_000, 60.0), eligible = true)
        assertEquals(listOf(0, 4, 5), buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE).map { it.value })
    }

    @Test
    fun sourceChangeAndExplicitClearCannotBridgePauseOrSavedSession() {
        val buffer = RecordingAutoResumeRecoveryBuffer<Int>()
        buffer.offer(fix(0, 10_000, 0.0), eligible = true)
        buffer.offer(fix(1, 15_000, 20.0).copy(source = "watch"), eligible = true)
        assertTrue(buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE).isEmpty())
        buffer.clear()
        buffer.offer(fix(2, 20_000, 40.0), eligible = false)
        assertTrue(buffer.recover(SettingsRepository.ACTIVITY_PROFILE_BIKE).isEmpty())
    }

    private fun fix(
        value: Int,
        millis: Long,
        meters: Double,
    ) = RecordingAutoResumeFix(
        value = value,
        point =
            RecordedTracePoint(
                latLong = LatLong(0.0, meters / 111_320.0),
                elevationMeters = null,
                timeMillis = millis,
                accuracyMeters = 5f,
                speedMps = 4f,
            ),
        elapsedMillis = millis,
        speedAccuracyMps = 0.5f,
        source = "fused",
    )
}
