package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.recording.RecordingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneStillnessEvidenceTest {
    @Test
    fun stopIsConfirmedAfterThirtySecondsEvenWhenGpsGoesSilent() {
        // Ride 2 ending: two still fixes about 15 s apart, inaccurate fixes, then no GPS indoors.
        val evidence = PhoneStillnessEvidence()
        assertTrue(evidence.observeStill(1_000L))
        assertFalse(evidence.observeStill(16_000L))
        assertEquals(31_000L, evidence.deadlineElapsedMillis())
        assertNull(evidence.confirmedDurationAt(30_999L))
        assertEquals(30_000L, evidence.confirmedDurationAt(31_000L))
        assertEquals(90_000L, evidence.confirmedDurationAt(91_000L))
    }

    @Test
    fun sparseStillFixesKeepTheOriginalStart() {
        val evidence = PhoneStillnessEvidence()
        evidence.observeStill(0L)
        evidence.observeStill(20_000L)
        evidence.observeStill(40_000L)
        assertEquals(0L, evidence.stillSinceElapsedMillis)
        assertEquals(40_000L, evidence.confirmedDurationAt(40_000L))
    }

    @Test
    fun movementClearsTheEvidenceAndANewRunStartsFresh() {
        val evidence = PhoneStillnessEvidence()
        evidence.observeStill(0L)
        assertTrue(evidence.clear())
        assertNull(evidence.deadlineElapsedMillis())
        assertNull(evidence.confirmedDurationAt(60_000L))
        assertFalse(evidence.clear())
        assertTrue(evidence.observeStill(50_000L))
        assertNull(evidence.confirmedDurationAt(60_000L))
        assertEquals(30_000L, evidence.confirmedDurationAt(80_000L))
    }

    @Test
    fun sparseStopNeedsLongerEvidenceThanTheRegularBikeWindow() {
        assertEquals(30_000L, PHONE_BIKE_SPARSE_STOP_DURATION_MS)
        assertTrue(PHONE_BIKE_SPARSE_STOP_DURATION_MS > RecordingSettings.AUTO_PAUSE_BIKE_STOP_DURATION_MS)
    }
}
