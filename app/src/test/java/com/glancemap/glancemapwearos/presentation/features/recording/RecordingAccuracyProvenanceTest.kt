package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
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
}
