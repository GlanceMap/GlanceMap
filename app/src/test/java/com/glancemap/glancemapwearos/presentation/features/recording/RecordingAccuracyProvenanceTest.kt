package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAccuracyProvenanceTest {
    @Test
    fun normalAccuracyKeepsRawAndEffectiveValuesWithRawInterpretation() {
        val provenance =
            resolveRecordingAccuracyProvenance(
                rawAccuracyMeters = 7f,
                knownWatchGpsAccuracyFloorActive = false,
            )

        assertEquals(7f, provenance.effectiveAccuracyMeters ?: -1f, 0f)
        assertEquals(RECORDING_ACCURACY_INTERPRETATION_RAW, provenance.interpretation)
    }

    @Test
    fun suspiciousWatchGpsAccuracyKeepsRawValueAndRecordsFilteringInterpretation() {
        val provenance =
            resolveRecordingAccuracyProvenance(
                rawAccuracyMeters = 125f,
                knownWatchGpsAccuracyFloorActive = true,
            )

        assertEquals(18f, provenance.effectiveAccuracyMeters ?: -1f, 0f)
        assertEquals(
            RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS,
            provenance.interpretation,
        )
    }

    @Test
    fun knownFloorWithoutWatchGpsContextDoesNotApplyTheHeuristic() {
        val provenance =
            resolveRecordingAccuracyProvenance(
                rawAccuracyMeters = 125f,
                knownWatchGpsAccuracyFloorActive = false,
            )

        assertEquals(125f, provenance.effectiveAccuracyMeters ?: -1f, 0f)
        assertEquals(RECORDING_ACCURACY_INTERPRETATION_RAW, provenance.interpretation)
    }

    @Test
    fun watchChipFixedAccuracyIsRecognisedThroughWatchGpsAndPhonelessFusedLocation() {
        val watchGps =
            GpsSignalSnapshot(
                watchGpsOnlyActive = true,
                activeSourceModeValue = LocationSourceMode.WATCH_GPS.telemetryValue,
            )
        val fused = GpsSignalSnapshot(activeSourceModeValue = LocationSourceMode.AUTO_FUSED.telemetryValue)

        assertTrue(isRecordingKnownWatchGpsAccuracyFloor(watchGps, 125f))
        assertFalse(isRecordingKnownWatchGpsAccuracyFloor(watchGps.copy(watchGpsDegraded = true), 125f))
        assertTrue(isRecordingKnownWatchGpsAccuracyFloor(fused, 125f))
        assertFalse(isRecordingKnownWatchGpsAccuracyFloor(fused, 60f))
        assertFalse(
            isRecordingKnownWatchGpsAccuracyFloor(
                GpsSignalSnapshot(activeSourceModeValue = LocationSourceMode.PASSIVE_EXTERNAL.telemetryValue),
                125f,
            ),
        )
    }
}
