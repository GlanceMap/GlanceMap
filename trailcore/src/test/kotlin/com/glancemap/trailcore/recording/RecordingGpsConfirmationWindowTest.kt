package com.glancemap.trailcore.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingGpsConfirmationWindowTest {
    @Test
    fun stepResumeRequiresFreshIncreasingMeasurements() {
        val steps = RecordingStepResumeEvidence()
        assertNull(steps.observe(100f, 1000, 1000, true, 8000))
        assertNull(steps.observe(102f, 4000, 4000, true, 8000))
        assertEquals(9000L, steps.observe(108f, 10000, 10000, true, 8000))
        steps.reset()
        assertNull(steps.observe(100f, 1000, 1000, true, 8000))
        assertNull(steps.observe(120f, 2000, 40000, true, 8000))
        assertNull(steps.observe(120f, 2000, 2000, false, 8000))
    }

    @Test
    fun blackoutAndInvalidObservationDoNotCountAsContinuousEvidence() {
        val window = RecordingGpsConfirmationWindow()
        assertEquals(0L, window.observe(1_000, true, 5_000))
        assertEquals(5_000L, window.observe(6_000, true, 5_000))
        assertNull(window.observe(5_000, true, 5_000))
        assertEquals(0L, window.observe(30_000, true, 5_000))
        assertEquals(5_000L, window.observe(35_000, true, 5_000))
        assertNull(window.observe(40_000, false, 5_000))
        assertEquals(0L, window.observe(45_000, true, 5_000))
    }
}
