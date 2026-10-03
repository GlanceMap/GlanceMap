package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceRecordingCadenceTest {
    @Test
    fun effectiveSamplingIntervalRoundsUpToContinuitySeconds() {
        assertEquals(10, recordingTrajectoryContinuityIntervalSeconds(10_000L, fallbackSeconds = 3))
        assertEquals(11, recordingTrajectoryContinuityIntervalSeconds(10_001L, fallbackSeconds = 3))
    }

    @Test
    fun invalidEffectiveSamplingIntervalUsesConfiguredOrDefaultFallback() {
        assertEquals(7, recordingTrajectoryContinuityIntervalSeconds(0L, fallbackSeconds = 7))
        assertEquals(
            SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS,
            recordingTrajectoryContinuityIntervalSeconds(-1L, fallbackSeconds = 0),
        )
    }
}
