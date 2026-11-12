package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingAutoPauseEvidenceTest {
    @Test
    fun longGpsBlackoutCannotConfirmEitherMovementOrStillness() {
        val moving = RecordingGpsConfirmationWindow()
        val stationary = RecordingGpsConfirmationWindow()
        assertEquals(0L, moving.observe(100_000, true, 10_000))
        assertEquals(0L, stationary.observe(100_000, true, 10_000))
        assertEquals(0L, moving.observe(189_534, true, 10_000))
        assertEquals(0L, stationary.observe(189_534, true, 10_000))
        assertEquals(10_000L, moving.observe(199_534, true, 10_000))
        assertEquals(0L, moving.observe(224_534, true, 10_000))
        val screenOff = RecordingGpsConfirmationWindow()
        assertEquals(0L, screenOff.observe(10_000, true, 5_000))
        assertEquals(12_000L, screenOff.observe(22_000, true, 5_000))
    }

    @Test
    fun measurementTimesAllowDelayedBatchesButIgnoreDuplicatesAndOldFixes() {
        val window = RecordingGpsConfirmationWindow()
        assertEquals(0L, window.observe(10_000, true, 12_000))
        assertNull(window.observe(10_000, true, 12_000))
        assertNull(window.observe(9_000, false, 12_000))
        assertEquals(12_000L, window.observe(22_000, true, 12_000))
        assertEquals(24_000L, window.observe(34_000, true, 12_000))
        assertNull(window.observe(46_000, false, 12_000))
        assertEquals(0L, window.observe(58_000, true, 12_000))
    }

    @Test
    fun stepEvidenceRequiresFreshForwardSamplesAndSustainedSteps() {
        val evidence = RecordingStepResumeEvidence()
        assertNull(evidence.observe(100f, 1_000, 1_000, true, 8_000))
        assertNull(evidence.observe(102f, 4_000, 4_000, true, 8_000))
        assertEquals(9_000L, evidence.observe(108f, 10_000, 10_000, true, 8_000))
    }

    @Test
    fun stepEvidenceIgnoresOldFutureDuplicateAndLongGaps() {
        val evidence = RecordingStepResumeEvidence()
        assertNull(evidence.observe(100f, 10_000, 10_000, true, 8_000))
        assertNull(evidence.observe(120f, 10_000, 10_000, true, 8_000))
        assertNull(evidence.observe(122f, 40_000, 10_000, true, 8_000))
        assertNull(evidence.observe(124f, 20_000, 50_000, true, 8_000))
        assertNull(evidence.observe(125f, 100_000, 100_000, true, 8_000))
        assertNull(evidence.observe(126f, 104_000, 104_000, false, 8_000))
        assertNull(evidence.observe(130f, 110_000, 110_000, true, 8_000))
    }

    @Test
    fun freshWheelMovementCanResumeButCoastingAndSilenceAreNotStillness() {
        val evidence = RecordingBikeResumeEvidence()
        assertNull(evidence.observe(2.0f, 10_000, 10_000, true, 5_000))
        assertEquals(6_000L, evidence.observe(2.1f, 16_000, 16_000, true, 5_000))
        evidence.reset()
        assertNull(evidence.observe(null, 20_000, 20_000, true, 5_000))
        assertNull(evidence.observe(null, 60_000, 60_000, true, 5_000))
        assertNull(evidence.observe(2f, 70_000, 70_000, false, 5_000))
    }

    @Test
    fun falseStartsAndDelayedSensorReadingsDoNotConfirmMovement() {
        val steps = RecordingStepResumeEvidence()
        assertNull(steps.observe(10f, 1_000, 1_000, true, 8_000))
        assertNull(steps.observe(12f, 3_000, 3_000, true, 8_000))
        assertNull(steps.observe(12f, 8_000, 8_000, true, 8_000))
        assertNull(steps.observe(20f, 10_000, 40_000, true, 8_000))
        assertNull(steps.observe(Float.NaN, 11_000, 11_000, true, 8_000))
        assertNull(steps.observe(13f, 12_000, 12_000, true, 8_000))
        val wheel = RecordingBikeResumeEvidence()
        assertNull(wheel.observe(2f, 1_000, 1_000, true, 5_000))
        assertNull(wheel.observe(2f, 40_000, 40_000, true, 5_000))
        assertNull(wheel.observe(2f, 46_000, 80_000, true, 5_000))
        wheel.reset()
        assertNull(wheel.observe(2f, 47_000, 47_000, true, 5_000))
    }
}
