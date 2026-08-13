package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.RecordingProgressVibrationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingProgressVibrationTest {
    @Test
    fun distanceReminderFiresOncePerCompletedDistanceInterval() {
        val settings = RecordingProgressVibrationSettings(distanceEnabled = true, distanceMeters = 1_000)
        val tracker = RecordingProgressVibrationTracker()
        tracker.start(settings)

        assertTrue(
            tracker
                .next(
                    settings = settings,
                    distanceMeters = 999.0,
                    activeDurationMillis = 0L,
                ).isEmpty(),
        )
        assertEquals(
            listOf(RecordingProgressVibrationTrigger.Distance(1L)),
            tracker.next(
                settings = settings,
                distanceMeters = 1_005.0,
                activeDurationMillis = 0L,
            ),
        )
        assertTrue(
            tracker
                .next(
                    settings = settings,
                    distanceMeters = 1_100.0,
                    activeDurationMillis = 0L,
                ).isEmpty(),
        )
        assertEquals(
            listOf(RecordingProgressVibrationTrigger.Distance(2L)),
            tracker.next(
                settings = settings,
                distanceMeters = 2_000.0,
                activeDurationMillis = 0L,
            ),
        )
    }

    @Test
    fun changingReminderWhileRecordingWaitsForTheNextMilestone() {
        val settings = RecordingProgressVibrationSettings(distanceEnabled = true, distanceMeters = 1_000)
        val tracker = RecordingProgressVibrationTracker()

        tracker.rebase(
            settings = settings,
            distanceMeters = 1_600.0,
            activeDurationMillis = 0L,
        )

        assertTrue(
            tracker
                .next(
                    settings = settings,
                    distanceMeters = 1_900.0,
                    activeDurationMillis = 0L,
                ).isEmpty(),
        )
        assertEquals(
            listOf(RecordingProgressVibrationTrigger.Distance(2L)),
            tracker.next(
                settings = settings,
                distanceMeters = 2_000.0,
                activeDurationMillis = 0L,
            ),
        )
    }

    @Test
    fun timeReminderUsesActiveRecordingTime() {
        val settings = RecordingProgressVibrationSettings(timeEnabled = true, timeMinutes = 30)
        val tracker = RecordingProgressVibrationTracker()
        tracker.start(settings)

        assertTrue(
            tracker
                .next(
                    settings = settings,
                    distanceMeters = 0.0,
                    activeDurationMillis = 29 * 60_000L,
                ).isEmpty(),
        )
        assertEquals(
            listOf(RecordingProgressVibrationTrigger.Time(1L)),
            tracker.next(
                settings = settings,
                distanceMeters = 0.0,
                activeDurationMillis = 30 * 60_000L,
            ),
        )
        assertEquals(30 * 60_000L, tracker.millisecondsUntilNextTimeMilestone(30 * 60_000L))
    }

    @Test
    fun distanceAndTimeRemindersCanFireTogether() {
        val settings =
            RecordingProgressVibrationSettings(
                distanceEnabled = true,
                distanceMeters = 1_000,
                timeEnabled = true,
                timeMinutes = 30,
            )
        val tracker = RecordingProgressVibrationTracker()
        tracker.start(settings)

        assertEquals(
            listOf(
                RecordingProgressVibrationTrigger.Distance(1L),
                RecordingProgressVibrationTrigger.Time(1L),
            ),
            tracker.next(
                settings = settings,
                distanceMeters = 1_000.0,
                activeDurationMillis = 30 * 60_000L,
            ),
        )
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
