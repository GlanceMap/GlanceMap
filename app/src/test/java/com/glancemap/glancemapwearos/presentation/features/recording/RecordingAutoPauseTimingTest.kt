package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingAutoPauseTimingTest {
    @Test
    fun autoPauseConfirmationBackdatesActiveTimeToStationaryOnset() {
        val state = TraceRecordingUiState(active = true, startedAtMillis = 1_000L)

        val pausedAt =
            effectiveAutoPauseStartMillis(
                state = state,
                confirmationMillis = 60_000L,
                stationaryDurationMillis = 45_000L,
            )
        val pausedState = state.copy(paused = true, autoPaused = true, pausedAtMillis = pausedAt)

        assertEquals(15_000L, pausedAt)
        assertEquals(14_000L, recordingActiveDurationMillis(pausedState, nowMillis = 60_000L))
    }

    @Test
    fun autoResumeCountsPausedTimeOnlyThroughMovementOnset() {
        val state =
            TraceRecordingUiState(
                active = true,
                paused = true,
                autoPaused = true,
                startedAtMillis = 1_000L,
                pausedAtMillis = 15_000L,
            )

        val addedPausedMillis =
            autoPauseAddedMillisAtResume(
                state = state,
                confirmationMillis = 100_000L,
                movingDurationMillis = 8_000L,
            )
        val resumedState = state.copy(paused = false, autoPaused = false, pausedAtMillis = null, accumulatedPausedMillis = addedPausedMillis)

        assertEquals(77_000L, addedPausedMillis)
        assertEquals(22_000L, recordingActiveDurationMillis(resumedState, nowMillis = 100_000L))
    }

    @Test
    fun multipleAutoPauseCyclesAccumulateEffectivePausedIntervals() {
        val firstPause = TraceRecordingUiState(active = true, paused = true, autoPaused = true, startedAtMillis = 1_000L, pausedAtMillis = 15_000L)
        val afterFirstResume =
            firstPause.copy(
                paused = false,
                autoPaused = false,
                pausedAtMillis = null,
                accumulatedPausedMillis = autoPauseAddedMillisAtResume(firstPause, 100_000L, 8_000L),
            )
        val secondPause = afterFirstResume.copy(paused = true, autoPaused = true, pausedAtMillis = 120_000L)

        val secondAddedPausedMillis = autoPauseAddedMillisAtResume(secondPause, 210_000L, 8_000L)
        val resumed =
            secondPause.copy(
                paused = false,
                autoPaused = false,
                pausedAtMillis = null,
                accumulatedPausedMillis = secondPause.accumulatedPausedMillis + secondAddedPausedMillis,
            )

        assertEquals(77_000L, afterFirstResume.accumulatedPausedMillis)
        assertEquals(82_000L, secondAddedPausedMillis)
        assertEquals(159_000L, resumed.accumulatedPausedMillis)
        assertEquals(50_000L, recordingActiveDurationMillis(resumed, nowMillis = 210_000L))
    }

    @Test
    fun manualPauseUsesItsActualActionTime() {
        val state =
            TraceRecordingUiState(
                active = true,
                paused = true,
                startedAtMillis = 1_000L,
                pausedAtMillis = 60_000L,
            )

        assertEquals(59_000L, recordingActiveDurationMillis(state, nowMillis = 100_000L))
    }

    @Test
    fun disablingAutoPauseResumesAtTheCurrentTimeWithoutBackdating() {
        val state =
            TraceRecordingUiState(
                active = true,
                paused = true,
                autoPaused = true,
                pausedAtMillis = 15_000L,
            )

        assertEquals(85_000L, autoPauseAddedMillisAtResume(state, confirmationMillis = 100_000L, movingDurationMillis = 0L))
    }

    @Test
    fun restoredPausedStateKeepsItsAccumulatedTiming() {
        val restored =
            TraceRecordingUiState(
                active = true,
                paused = true,
                autoPaused = true,
                startedAtMillis = 1_000L,
                pausedAtMillis = 120_000L,
                accumulatedPausedMillis = 77_000L,
            )

        assertEquals(82_000L, autoPauseAddedMillisAtResume(restored, 210_000L, 8_000L))
        assertEquals(159_000L, restored.accumulatedPausedMillis + autoPauseAddedMillisAtResume(restored, 210_000L, 8_000L))
    }

    @Test
    fun invalidRestoredPauseTimeCannotProduceNegativePausedDuration() {
        val restored = TraceRecordingUiState(paused = true, autoPaused = true, pausedAtMillis = 120_000L)

        assertEquals(0L, autoPauseAddedMillisAtResume(restored, confirmationMillis = 100_000L, movingDurationMillis = 8_000L))
    }
}
