package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingScreenOffDiagnosticsTest {
    private var elapsedTimeMs = 0L

    @Before
    fun useTestClock() {
        RecordingScreenOffDiagnostics.setElapsedTimeProviderForTests { elapsedTimeMs }
    }

    @After
    fun resetDiagnostics() {
        RecordingScreenOffDiagnostics.configure(fullDiagnostics = false)
        RecordingScreenOffDiagnostics.updateRuntimeState(
            isInteractive = true,
            isRecordingActive = false,
        )
        RecordingScreenOffDiagnostics.setElapsedTimeProviderForTests(provider = null)
    }

    @Test
    fun attributesOnlyFullDiagnosticsWhileRecordingWithTheScreenOff() {
        RecordingScreenOffDiagnostics.configure(fullDiagnostics = true)
        RecordingScreenOffDiagnostics.updateRuntimeState(
            isInteractive = false,
            isRecordingActive = true,
        )

        val startedAtElapsedMs =
            RecordingScreenOffDiagnostics.start()
        elapsedTimeMs += 7L
        RecordingScreenOffDiagnostics.stop(
            activity = RecordingScreenOffActivity.RECORDING_POINT,
            startedAtElapsedMs = startedAtElapsedMs,
        )

        val snapshot = RecordingScreenOffDiagnostics.snapshotAndReset()

        assertEquals(1L, snapshot.recordingPoint.count)
        assertTrue(snapshot.recordingPoint.elapsedMs >= 7L)
    }

    @Test
    fun screenTransitionStartsANewAttributionWindow() {
        RecordingScreenOffDiagnostics.configure(fullDiagnostics = true)
        RecordingScreenOffDiagnostics.updateRuntimeState(
            isInteractive = false,
            isRecordingActive = true,
        )
        val firstStartedAtElapsedMs =
            RecordingScreenOffDiagnostics.start()
        elapsedTimeMs += 3L
        RecordingScreenOffDiagnostics.stop(
            activity = RecordingScreenOffActivity.LOCATION_CALLBACK,
            startedAtElapsedMs = firstStartedAtElapsedMs,
        )

        RecordingScreenOffDiagnostics.updateRuntimeState(
            isInteractive = true,
            isRecordingActive = true,
        )

        assertEquals(0L, RecordingScreenOffDiagnostics.snapshotAndReset().locationCallback.count)
    }

    @Test
    fun batteryBenchmarkDoesNotCollectRecordingAttribution() {
        RecordingScreenOffDiagnostics.configure(fullDiagnostics = false)
        RecordingScreenOffDiagnostics.updateRuntimeState(
            isInteractive = false,
            isRecordingActive = true,
        )

        val startedAtElapsedMs =
            RecordingScreenOffDiagnostics.start()
        RecordingScreenOffDiagnostics.stop(
            activity = RecordingScreenOffActivity.PRESSURE_CALLBACK,
            startedAtElapsedMs = startedAtElapsedMs,
        )

        assertEquals(0L, RecordingScreenOffDiagnostics.snapshotAndReset().pressureCallback.count)
    }
}
