package com.glancemap.glancemapwearos.presentation.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OamExtractionPowerDiagnosticsTest {
    @Test
    fun acquiredLockCanBeDisabledBySystemWithoutInventingARelease() {
        val events = mutableListOf<String>()
        val tracker = OamWakeLockDiagnostics(events::add)
        tracker.begin(1, 123)
        assertNull(tracker.systemEnabled)
        tracker.onSystemState(1, false)
        assertEquals(false, tracker.systemEnabled)
        assertTrue(events.single().contains("wakeLockSystemEnabled=false"))
        assertTrue(events.single().contains("wakeLockIdentity=123"))
    }

    @Test
    fun reacquisitionIgnoresCallbackFromOldLock() {
        val events = mutableListOf<String>()
        val tracker = OamWakeLockDiagnostics(events::add)
        tracker.begin(1, 123)
        tracker.onSystemState(1, false)
        tracker.begin(2, 456)
        assertNull(tracker.systemEnabled)
        tracker.onSystemState(1, true)
        assertNull(tracker.systemEnabled)
        assertEquals(1, events.size)
        tracker.onSystemState(2, true)
        assertEquals(true, tracker.systemEnabled)
        assertTrue(events.last().contains("wakeLockGeneration=2"))
    }

    @Test
    fun releaseInvalidatesQueuedCallbacksAndClearsState() {
        val events = mutableListOf<String>()
        val tracker = OamWakeLockDiagnostics(events::add)
        tracker.begin(1, 123)
        tracker.onSystemState(1, true)
        tracker.clear()
        tracker.onSystemState(1, false)
        assertNull(tracker.systemEnabled)
        assertEquals(1, events.size)
    }

    @Test
    fun repeatedSystemStateDoesNotProduceRepeatedLogs() {
        val events = mutableListOf<String>()
        val tracker = OamWakeLockDiagnostics(events::add)
        tracker.begin(1, 123)
        repeat(20) { tracker.onSystemState(1, true) }
        tracker.onSystemState(1, false)
        assertEquals(2, events.size)
        assertEquals(false, tracker.systemEnabled)
    }

    @Test
    fun transitionLogCapDoesNotFreezeLatestDiagnosticState() {
        val events = mutableListOf<String>()
        val tracker = OamWakeLockDiagnostics(events::add)
        tracker.begin(1, 123)
        repeat(41) { tracker.onSystemState(1, it % 2 == 0) }
        assertEquals(16, events.size)
        assertEquals(true, tracker.systemEnabled)
    }

    @Test
    fun powerQueriesAreThrottledAndRefreshAfterLongSuspend() {
        var now = 0L
        var idle = false
        var reads = 0
        val sampler =
            OamExtractionPowerSampler(
                read = {
                    reads++
                    OamExtractionPowerSnapshot(deviceIdleMode = idle)
                },
                nowMs = { now },
            )
        assertEquals(false, sampler.sample().deviceIdleMode)
        idle = true
        now = 4_999L
        repeat(100) { assertEquals(false, sampler.sample().deviceIdleMode) }
        assertEquals(1, reads)
        now = 5_000L
        assertEquals(true, sampler.sample().deviceIdleMode)
        now = 650_000L
        idle = false
        assertEquals(false, sampler.sample().deviceIdleMode)
        assertEquals(3, reads)
    }

    @Test
    fun diagnosticReadFailureIsUnknownAndNextSampleCanRecover() {
        var now = 0L
        val sampler =
            OamExtractionPowerSampler(
                read = {
                    if (now == 0L) {
                        error("Unavailable platform API")
                    } else {
                        OamExtractionPowerSnapshot(powerSaveMode = true)
                    }
                },
                nowMs = { now },
            )
        assertNull(sampler.sample().powerSaveMode)
        now = 5_000L
        assertEquals(true, sampler.sample().powerSaveMode)
    }

    @Test
    fun unavailablePlatformFieldsAreNotReportedAsDisabled() {
        val fields = OamExtractionPowerSnapshot().telemetryFields()
        assertTrue(fields.contains("deviceIdleMode=na"))
        assertTrue(fields.contains("lowPowerStandbyEnabled=na"))
        assertTrue(fields.contains("lowPowerStandbyExempt=na"))
        assertFalse(fields.contains("=false"))
    }

    @Test
    fun existingBoundedReporterCorrelatesPolicyAndSystemStateWithSuspend() {
        var elapsed = 0L
        var uptime = 0L
        val events = mutableListOf<String>()
        val reporter =
            OamExtractionTelemetryReporter(
                label = "Map",
                entryFileName = "Area.map",
                totalBytes = 100L,
                wallNowMs = { elapsed },
                uptimeNowMs = { uptime },
                processCpuMs = { 0L },
                runtimeSnapshot = {
                    OamExtractionRuntimeSnapshot(
                        interactive = false,
                        charging = false,
                        plugged = "battery",
                        batteryPercent = "90",
                        thermalStatus = "0",
                        wakeLockHeld = true,
                        wifiLockHeld = true,
                        wakeLockSystemEnabled = false,
                        foregroundServiceActive = true,
                        power = OamExtractionPowerSnapshot(lowPowerStandbyEnabled = true, processImportance = 125),
                    )
                },
                emit = events::add,
            )
        elapsed = 650_000L
        uptime = 5_000L
        reporter.onBytesWritten(50L)
        elapsed += 30_000L
        uptime += 1_000L
        reporter.emitStallHeartbeatIfNeeded()
        reporter.complete(50L)
        assertEquals(3, events.size)
        for (event in events) {
            assertTrue(event.contains("wakeLockIsHeld=true"))
            assertTrue(event.contains("wakeLockSystemEnabled=false"))
            assertTrue(event.contains("lowPowerStandbyEnabled=true"))
            assertTrue(event.contains("foregroundServiceActive=true"))
            assertTrue(event.contains("elapsedRealtimeMs="))
            assertTrue(event.contains("uptimeMillis="))
        }
        assertTrue(events.first().contains("suspendOrSleepDeltaMs=645000"))
        assertTrue(events.last().contains("suspendOrSleepDurationMs=674000"))
    }
}
