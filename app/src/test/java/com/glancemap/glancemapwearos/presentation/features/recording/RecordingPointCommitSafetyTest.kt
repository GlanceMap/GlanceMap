package com.glancemap.glancemapwearos.presentation.features.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPointCommitSafetyTest {
    @Test
    fun delayedLocationIsDiscardedAfterManualPauseOrPauseAndResume() =
        runTest {
            val capturedGeneration = 3L
            var generation = capturedGeneration
            var state = TraceRecordingUiState(active = true)
            val elevationReady = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val result =
                async {
                    started.complete(Unit)
                    elevationReady.await()
                    state.canCommitRecordingPoint(capturedGeneration, generation)
                }
            started.await()
            state = state.copy(paused = true, autoPaused = false)
            generation++
            elevationReady.complete(Unit)
            assertFalse(result.await())
            state = state.copy(paused = false)
            generation++
            assertFalse(state.canCommitRecordingPoint(capturedGeneration, generation))
            assertTrue(state.canCommitRecordingPoint(generation, generation))
            assertFalse(state.copy(saving = true).canCommitRecordingPoint(generation, generation))
        }
}
