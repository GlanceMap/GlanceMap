package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeGlobalTelemetryProducerVolumeSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringWriter

class DebugTelemetryProducerVolumeTest {
    @Before
    fun setUp() {
        DebugTelemetry.setTransitionMarkersEnabledForTests(false)
        DebugTelemetry.setEnabledFromLocationService(false)
        DebugTelemetry.clear()
        GnssDiagnostics.clear()
        EnergyDiagnostics.configure(captureActive = false, fullDiagnostics = false)
    }

    @After
    fun tearDown() {
        DebugTelemetry.setEnabledFromLocationService(false)
        DebugTelemetry.clear()
        GnssDiagnostics.clear()
        EnergyDiagnostics.configure(captureActive = false, fullDiagnostics = false)
        DebugTelemetry.setTransitionMarkersEnabledForTests(true)
    }

    @Test
    fun producerAttributionIncrementsForKnownTags() {
        DebugTelemetry.setEnabledFromLocationService(true)
        log("LocTelemetry")
        log("GnssTelemetry")
        log("NavigationTelemetry")
        log("MapHotPath")
        log("MarkerMotion")
        log("TurnByTurn")
        log("TraceRecording")
        log("CompassTelemetry")
        log("ScreenTelemetry")

        val volumes = volumesByProducer()

        assertEquals(1L, volumes[GlobalTelemetryProducer.LOC_TELEMETRY]?.generatedLines)
        assertEquals(1L, volumes[GlobalTelemetryProducer.GNSS_TELEMETRY]?.generatedLines)
        assertEquals(1L, volumes[GlobalTelemetryProducer.NAVIGATION_TELEMETRY]?.generatedLines)
        assertEquals(1L, volumes[GlobalTelemetryProducer.MAP_HOT_PATH]?.generatedLines)
        assertEquals(1L, volumes[GlobalTelemetryProducer.MARKER_MOTION]?.generatedLines)
        assertEquals(1L, volumes[GlobalTelemetryProducer.TBT_HAPTIC]?.generatedLines)
        assertEquals(1L, volumes[GlobalTelemetryProducer.RECORDING]?.generatedLines)
        assertEquals(1L, volumes[GlobalTelemetryProducer.COMPASS]?.generatedLines)
        assertEquals(1L, volumes[GlobalTelemetryProducer.DIAGNOSTICS_SYSTEM]?.generatedLines)
    }

    @Test
    fun unknownTagsAreCategorizedAsOther() {
        DebugTelemetry.setEnabledFromLocationService(true)
        log("UnknownProducer")

        val other = volumesByProducer().getValue(GlobalTelemetryProducer.OTHER)

        assertEquals(1L, other.generatedLines)
        assertEquals(1L, other.retainedLines)
        assertEquals(0L, other.droppedLines)
    }

    @Test
    fun globalRingEvictionTracksProducerDropAndRetentionCounts() {
        DebugTelemetry.setEnabledFromLocationService(true)
        repeat(DebugTelemetry.maxBufferedLines()) { log("LocTelemetry") }
        log("GnssTelemetry")

        val snapshot = DebugTelemetry.captureSessionSnapshot()
        val volumes = snapshot.producerVolumes.associateBy { it.producer }

        assertEquals(DebugTelemetry.maxBufferedLines().toLong() + 1L, snapshot.totalLoggedLines)
        assertEquals(1, snapshot.droppedLines)
        assertEquals(
            DebugTelemetry.maxBufferedLines().toLong(),
            volumes.getValue(GlobalTelemetryProducer.LOC_TELEMETRY).generatedLines,
        )
        assertEquals(
            DebugTelemetry.maxBufferedLines().toLong() - 1L,
            volumes.getValue(GlobalTelemetryProducer.LOC_TELEMETRY).retainedLines,
        )
        assertEquals(1L, volumes.getValue(GlobalTelemetryProducer.LOC_TELEMETRY).droppedLines)
        assertEquals(1L, volumes.getValue(GlobalTelemetryProducer.GNSS_TELEMETRY).retainedLines)
    }

    @Test
    fun clearResetsProducerCounters() {
        DebugTelemetry.setEnabledFromLocationService(true)
        log("LocTelemetry")
        log("UnknownProducer")

        DebugTelemetry.setEnabledFromLocationService(false)
        DebugTelemetry.clear()

        val snapshot = DebugTelemetry.captureSessionSnapshot()

        assertEquals(0L, snapshot.totalLoggedLines)
        assertEquals(0, snapshot.droppedLines)
        assertTrue(
            snapshot.producerVolumes.all { volume ->
                volume.generatedLines == 0L && volume.retainedLines == 0L && volume.droppedLines == 0L
            },
        )
    }

    @Test
    fun disabledAndBatteryBenchmarkCaptureDoNotAccountFullProducerVolume() {
        log("LocTelemetry")
        EnergyDiagnostics.configure(captureActive = true, fullDiagnostics = false)
        log("GnssTelemetry")

        val snapshot = DebugTelemetry.captureSessionSnapshot()

        assertEquals(0L, snapshot.totalLoggedLines)
        assertTrue(snapshot.producerVolumes.all { volume -> volume.generatedLines == 0L })
    }

    @Test
    fun exportSummaryReportsProducerCounts() {
        DebugTelemetry.setEnabledFromLocationService(true)
        log("LocTelemetry")
        log("UnknownProducer")

        val output = StringWriter()
        output.writeGlobalTelemetryProducerVolumeSummary(DebugTelemetry.captureSessionSnapshot().producerVolumes)

        assertTrue(output.toString().contains("telemetryProducer_loc_generatedLines=1"))
        assertTrue(output.toString().contains("telemetryProducer_loc_retainedLines=1"))
        assertTrue(output.toString().contains("telemetryProducer_other_generatedLines=1"))
    }

    @Test
    fun gnssRingReportsGeneratedRetainedAndDroppedCounts() {
        DebugTelemetry.setEnabledFromLocationService(true)
        repeat(1_201) { runCatching { GnssDiagnostics.recordEvent(event = "test") } }

        assertEquals(1_201L, GnssDiagnostics.generatedLineCount())
        assertEquals(GnssDiagnostics.maxBufferedLines(), GnssDiagnostics.snapshotLines().size)
        assertEquals(1, GnssDiagnostics.droppedLineCount())
    }

    private fun log(tag: String) {
        runCatching { DebugTelemetry.log(tag, "event=test") }
    }

    private fun volumesByProducer() = DebugTelemetry.captureSessionSnapshot().producerVolumes.associateBy { it.producer }
}
