package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPointCaptureExpectationTest {
    @Test
    fun `expected stored samples integrate screen on and screen off intervals`() {
        val expectation = RecordingPointCaptureExpectation()

        expectation.start(nowElapsedMs = 0L, intervalMs = 3_000L)
        expectation.updateInterval(nowElapsedMs = 30_000L, intervalMs = 10_000L)

        assertEquals(21, expectation.expectedPointCount(nowElapsedMs = 130_000L))
    }

    @Test
    fun `paused time does not add expected stored samples`() {
        val expectation = RecordingPointCaptureExpectation()

        expectation.start(nowElapsedMs = 0L, intervalMs = 10_000L)
        expectation.pause(nowElapsedMs = 60_000L)
        expectation.resume(nowElapsedMs = 120_000L, intervalMs = 10_000L)

        assertEquals(13, expectation.expectedPointCount(nowElapsedMs = 180_000L))
    }
}
