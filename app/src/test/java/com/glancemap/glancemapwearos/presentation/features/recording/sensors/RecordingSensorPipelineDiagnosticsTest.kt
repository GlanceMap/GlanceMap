package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSensorPipelineDiagnosticsTest {
    @Test
    fun invalidCallbacksDoNotRefreshUsableMeasurementAge() {
        val diagnostics = RecordingSensorPipelineDiagnostics()
        diagnostics.callback(RecordingSensorSource.HEART_RATE, reading(80f, 1_000L))
        diagnostics.callback(RecordingSensorSource.HEART_RATE, reading(0f, 5_000L, usable = false))

        val snapshot = diagnostics.snapshot().first { it.source == RecordingSensorSource.HEART_RATE }
        assertEquals(2L, snapshot.callbacks)
        assertEquals(1L, snapshot.usable)
        assertTrue(snapshot.telemetry(6_000L).contains("validSourceAgeMs=5000"))
        assertTrue(snapshot.telemetry(6_000L).contains("callbackAgeMs=1000"))
        assertTrue(snapshot.telemetry(6_000L).contains("invalid=1"))
    }

    @Test
    fun coalescedValidCallbacksRemainDistinctFromMainThreadPublication() {
        val diagnostics = RecordingSensorPipelineDiagnostics()
        diagnostics.callback(RecordingSensorSource.HEART_RATE, reading(80f, 1_000L))
        diagnostics.published(RecordingSensorSource.HEART_RATE, 1_000L, 1_250L)
        diagnostics.callback(RecordingSensorSource.HEART_RATE, reading(81f, 1_100L))
        diagnostics.coalesced(RecordingSensorSource.HEART_RATE)

        val snapshot = diagnostics.snapshot().first { it.source == RecordingSensorSource.HEART_RATE }
        assertEquals(2L, snapshot.callbacks)
        assertEquals(2L, snapshot.usable)
        assertEquals(1L, snapshot.published)
        assertEquals(1L, snapshot.coalesced)
        assertTrue(snapshot.telemetry(2_000L).contains("publishDelayMs=250"))
        assertTrue(snapshot.telemetry(2_000L).contains("lastOutcome=coalesced"))
    }

    @Test
    fun detectorEventsDoNotMasqueradeAsCounterCallbacks() {
        val diagnostics = RecordingSensorPipelineDiagnostics()
        diagnostics.callback(RecordingSensorSource.STEP_COUNTER, reading(1_000f, 1_000L))
        diagnostics.callback(RecordingSensorSource.STEP_DETECTOR, reading(1f, 2_000L))
        diagnostics.callback(RecordingSensorSource.STEP_DETECTOR, reading(1f, 3_000L))

        val snapshots = diagnostics.snapshot().associateBy { it.source }
        assertEquals(1L, snapshots.getValue(RecordingSensorSource.STEP_COUNTER).callbacks)
        assertEquals(2L, snapshots.getValue(RecordingSensorSource.STEP_DETECTOR).callbacks)
        assertEquals(0L, snapshots.getValue(RecordingSensorSource.PRESSURE).callbacks)
        assertTrue(snapshots.getValue(RecordingSensorSource.PRESSURE).telemetry(4_000L).contains("callbackAgeMs=-1"))
    }

    @Test
    fun timelyUpdatesDoNotEraseEarlierDeliveryAndPublicationDelays() {
        val diagnostics = RecordingSensorPipelineDiagnostics()
        diagnostics.callback(
            RecordingSensorSource.HEART_RATE,
            reading(80f, 10_000L).copy(sourceElapsedMillis = 1_000L),
        )
        diagnostics.published(RecordingSensorSource.HEART_RATE, 10_000L, 12_000L)
        diagnostics.callback(RecordingSensorSource.HEART_RATE, reading(81f, 20_000L))
        diagnostics.published(RecordingSensorSource.HEART_RATE, 20_000L, 20_010L)

        val telemetry =
            diagnostics.snapshot().first { it.source == RecordingSensorSource.HEART_RATE }.telemetry(21_000L)
        assertTrue(telemetry.contains("maxDeliveryAgeMs=9000"))
        assertTrue(telemetry.contains("maxPublishDelayMs=2000"))
        assertTrue(telemetry.contains("publicationAgeMs=990"))
    }

    @Test
    fun callbacksWithoutDerivedCadenceDoNotRefreshCadenceAge() {
        val diagnostics = RecordingSensorPipelineDiagnostics()
        diagnostics.callback(RecordingSensorSource.STEP_COUNTER, reading(1_000f, 1_000L).copy(derivedCadenceSpm = 60))
        diagnostics.callback(RecordingSensorSource.STEP_COUNTER, reading(1_000f, 20_000L))

        val telemetry =
            diagnostics.snapshot().first { it.source == RecordingSensorSource.STEP_COUNTER }.telemetry(21_000L)
        assertTrue(telemetry.contains("derivedCadenceSpm=60"))
        assertTrue(telemetry.contains("cadenceDerivedAgeMs=20000"))
        assertTrue(telemetry.contains("callbackAgeMs=1000"))
    }

    @Test
    fun delayedSourceDeliveryAndSuspendTimeRemainVisible() {
        val diagnostics = RecordingSensorPipelineDiagnostics()
        diagnostics.callback(RecordingSensorSource.STEP_COUNTER, reading(1_000f, 1_000L))
        diagnostics.callback(
            RecordingSensorSource.STEP_COUNTER,
            reading(1_010f, 91_000L).copy(sourceElapsedMillis = 11_000L, callbackUptimeMillis = 21_000L),
        )

        val telemetry =
            diagnostics.snapshot().first { it.source == RecordingSensorSource.STEP_COUNTER }.telemetry(92_000L)
        assertTrue(telemetry.contains("sourceAgeMs=81000"))
        assertTrue(telemetry.contains("callbackAgeMs=1000"))
        assertTrue(telemetry.contains("suspendDeltaMs=70000"))
        assertTrue(telemetry.contains("rawAccuracy=3"))
    }

    private fun reading(
        value: Float,
        timeMillis: Long,
        usable: Boolean = true,
    ) = RecordingSensorCallbackSample(value, 3, timeMillis, timeMillis, timeMillis, usable)
}
