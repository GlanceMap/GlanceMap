package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingProgressVibrationTest {
    @Test
    fun distanceReminderFiresOncePerCompletedDistanceInterval() {
        val tracker = RecordingProgressVibrationTracker()
        tracker.start(SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER)

        assertNull(
            tracker.next(
                mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
                distanceMeters = 999.0,
                activeDurationMillis = 0L,
            ),
        )
        assertEquals(
            RecordingProgressVibrationTrigger.Distance(1L),
            tracker.next(
                mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
                distanceMeters = 1_005.0,
                activeDurationMillis = 0L,
            ),
        )
        assertNull(
            tracker.next(
                mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
                distanceMeters = 1_100.0,
                activeDurationMillis = 0L,
            ),
        )
        assertEquals(
            RecordingProgressVibrationTrigger.Distance(2L),
            tracker.next(
                mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
                distanceMeters = 2_000.0,
                activeDurationMillis = 0L,
            ),
        )
    }

    @Test
    fun changingReminderWhileRecordingWaitsForTheNextMilestone() {
        val tracker = RecordingProgressVibrationTracker()

        tracker.rebase(
            mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
            distanceMeters = 1_600.0,
            activeDurationMillis = 0L,
        )

        assertNull(
            tracker.next(
                mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
                distanceMeters = 1_900.0,
                activeDurationMillis = 0L,
            ),
        )
        assertEquals(
            RecordingProgressVibrationTrigger.Distance(2L),
            tracker.next(
                mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
                distanceMeters = 2_000.0,
                activeDurationMillis = 0L,
            ),
        )
    }

    @Test
    fun timeReminderUsesActiveRecordingTime() {
        val tracker = RecordingProgressVibrationTracker()
        tracker.start(SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES)

        assertNull(
            tracker.next(
                mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES,
                distanceMeters = 0.0,
                activeDurationMillis = 29 * 60_000L,
            ),
        )
        assertEquals(
            RecordingProgressVibrationTrigger.Time(1L),
            tracker.next(
                mode = SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES,
                distanceMeters = 0.0,
                activeDurationMillis = 30 * 60_000L,
            ),
        )
        assertEquals(30 * 60_000L, tracker.millisecondsUntilNextTimeMilestone(30 * 60_000L))
    }

    @Test
    fun activeDurationExcludesCurrentPause() {
        val state =
            TraceRecordingUiState(
                active = true,
                paused = true,
                startedAtMillis = 1_000L,
                pausedAtMillis = 6_000L,
                accumulatedPausedMillis = 1_000L,
            )

        assertEquals(4_000L, recordingActiveDurationMillis(state, nowMillis = 10_000L))
    }
}
