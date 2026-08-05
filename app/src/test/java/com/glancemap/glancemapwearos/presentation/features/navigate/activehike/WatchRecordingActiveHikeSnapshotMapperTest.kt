package com.glancemap.glancemapwearos.presentation.features.navigate.activehike

import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.shared.transfer.ActiveHikePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mapsforge.core.model.LatLong

class WatchRecordingActiveHikeSnapshotMapperTest {
    @Test
    fun `maps a paused recording into live companion metrics`() {
        val snapshot =
            TraceRecordingUiState(
                active = true,
                paused = true,
                distanceMeters = 2_300.0,
                startedAtMillis = 10_000L,
                pausedAtMillis = 25_000L,
                accumulatedPausedMillis = 4_000L,
                externalSpeedMps = 1.5f,
                latestLivePoint =
                    RecordedTracePoint(
                        latLong = LatLong(46.5, 11.9),
                        elevationMeters = 2_550.0,
                        timeMillis = 20_000L,
                        accuracyMeters = 5f,
                        speedMps = 0.8f,
                    ),
            ).toActiveHikeSnapshot(recordedAtEpochMillis = 30_000L)

        assertEquals(ActiveHikePhase.RECORDING_PAUSED, snapshot.phase)
        assertEquals(2_300.0, snapshot.distanceFromStartMeters)
        assertEquals(11L, snapshot.activeDurationSeconds)
        assertEquals(1.5, snapshot.currentSpeedMetersPerSecond ?: 0.0, 0.001)
        assertEquals(2_550.0, snapshot.currentAltitudeMeters ?: 0.0, 0.001)
        assertFalse(snapshot.offRoute)
    }
}
