package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeRecordingInstrumentationSummarySection
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

    @Test
    fun instrumentationCountersSeparateScreenOffWorkAndResetForNewCapture() {
        RecordingScreenOffDiagnostics.configure(fullDiagnostics = true)
        RecordingScreenOffDiagnostics.updateRuntimeState(
            isInteractive = true,
            isRecordingActive = true,
        )
        RecordingScreenOffDiagnostics.recordDashboardTick()
        RecordingScreenOffDiagnostics.recordDashboardSnapshotBuild(pointsScanned = 4)
        RecordingScreenOffDiagnostics.recordTbtProjection(segmentsScanned = 3)
        RecordingScreenOffDiagnostics.recordDraftPersist(
            jsonBytesWritten = 10L,
            gpxBytesWritten = 20L,
            pointCount = 4,
        )
        RecordingScreenOffDiagnostics.recordSensorCallback(RecordingSensorDiagnosticKind.HEART_RATE)
        RecordingScreenOffDiagnostics.recordSensorUiPublish(RecordingSensorDiagnosticKind.HEART_RATE)

        RecordingScreenOffDiagnostics.updateRuntimeState(
            isInteractive = false,
            isRecordingActive = true,
        )
        RecordingScreenOffDiagnostics.recordDashboardTick()
        RecordingScreenOffDiagnostics.recordDashboardSnapshotBuild(pointsScanned = 8)
        RecordingScreenOffDiagnostics.recordTbtProjection(segmentsScanned = 5)
        RecordingScreenOffDiagnostics.recordDraftPersist(
            jsonBytesWritten = 30L,
            gpxBytesWritten = 70L,
            pointCount = 7,
        )
        RecordingScreenOffDiagnostics.recordSensorCallback(RecordingSensorDiagnosticKind.HEART_RATE)
        RecordingScreenOffDiagnostics.recordSensorUiPublish(RecordingSensorDiagnosticKind.HEART_RATE)

        val counters = RecordingScreenOffDiagnostics.snapshotInstrumentation()

        assertInstrumentationCounters(counters)

        val report = buildString { writeRecordingInstrumentationSummarySection(counters) }
        assertInstrumentationReport(report)

        RecordingScreenOffDiagnostics.configure(fullDiagnostics = false)
        RecordingScreenOffDiagnostics.configure(fullDiagnostics = true)

        assertEquals(0L, RecordingScreenOffDiagnostics.snapshotInstrumentation().recordingDashboardTickCount)
    }

    private fun assertInstrumentationCounters(counters: RecordingInstrumentationCounters) {
        assertEquals(2L, counters.recordingDashboardTickCount)
        assertEquals(1L, counters.recordingDashboardScreenOffTickCount)
        assertEquals(2L, counters.recordingDashboardSnapshotBuildCount)
        assertEquals(1L, counters.recordingDashboardScreenOffSnapshotBuildCount)
        assertEquals(12L, counters.recordingDashboardPointsScanned)
        assertEquals(8L, counters.recordingDashboardScreenOffPointsScanned)
        assertEquals(2L, counters.tbtProjectionRunCount)
        assertEquals(1L, counters.tbtScreenOffProjectionRunCount)
        assertEquals(8L, counters.tbtProjectionSegmentsScanned)
        assertEquals(5L, counters.tbtScreenOffProjectionSegmentsScanned)
        assertEquals(5L, counters.tbtProjectionMaxSegments)
        assertEquals(5L, counters.tbtScreenOffProjectionMaxSegments)
        assertEquals(2L, counters.recordingDraftPersistCount)
        assertEquals(1L, counters.recordingDraftScreenOffPersistCount)
        assertEquals(40L, counters.recordingDraftJsonBytesWritten)
        assertEquals(30L, counters.recordingDraftScreenOffJsonBytesWritten)
        assertEquals(90L, counters.recordingDraftGpxBytesWritten)
        assertEquals(70L, counters.recordingDraftScreenOffGpxBytesWritten)
        assertEquals(130L, counters.recordingDraftTotalBytesWritten)
        assertEquals(100L, counters.recordingDraftScreenOffTotalBytesWritten)
        assertEquals(7L, counters.recordingDraftMaxPointCount)
        assertEquals(7L, counters.recordingDraftScreenOffMaxPointCount)
        assertEquals(11L, counters.recordingDraftPointsSerialized)
        assertEquals(7L, counters.recordingDraftScreenOffPointsSerialized)
        assertEquals(2L, counters.recordingSensorCallbackCount)
        assertEquals(1L, counters.recordingSensorScreenOffCallbackCount)
        assertEquals(2L, counters.recordingSensorUiPublishCount)
        assertEquals(1L, counters.recordingSensorScreenOffUiPublishCount)
        assertEquals(2L, counters.sensorCallbackCounts.getValue("HeartRate").count)
        assertEquals(1L, counters.sensorCallbackCounts.getValue("HeartRate").screenOffCount)
    }

    private fun assertInstrumentationReport(report: String) {
        assertTrue(report.contains("recordingDashboardScreenOffTickCount=1"))
        assertTrue(report.contains("tbtProjectionSegmentsScanned=8"))
        assertTrue(report.contains("recordingDraftTotalBytesWritten=130"))
        assertTrue(report.contains("recordingSensorHeartRateCallbackCount=2"))
    }
}
