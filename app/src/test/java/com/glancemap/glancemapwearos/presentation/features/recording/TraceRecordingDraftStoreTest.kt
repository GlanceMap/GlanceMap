package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class TraceRecordingDraftStoreTest {
    @Test
    fun draftAccuracyMappingRoundTripPreservesAllFields() {
        val restored =
            restoreRecordingAccuracyProvenance(
                rawAccuracyMeters = 125f,
                effectiveAccuracyMeters = 18f,
                accuracyInterpretation = RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS,
            )

        assertEquals(18f, restored.effectiveAccuracyMeters ?: -1f, 0f)
        assertEquals(
            RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS,
            restored.interpretation,
        )
    }

    @Test
    fun legacyDraftDefaultsMissingProvenanceToRawAccuracy() {
        val restored =
            restoreRecordingAccuracyProvenance(
                rawAccuracyMeters = 125f,
                effectiveAccuracyMeters = null,
                accuracyInterpretation = null,
            )

        assertEquals(125f, restored.effectiveAccuracyMeters ?: -1f, 0f)
        assertEquals(RECORDING_ACCURACY_INTERPRETATION_RAW, restored.interpretation)
    }
}
