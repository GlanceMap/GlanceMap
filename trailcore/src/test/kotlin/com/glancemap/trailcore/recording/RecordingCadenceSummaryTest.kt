package com.glancemap.trailcore.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingCadenceSummaryTest {
    @Test
    fun walkingCadenceUsesActiveTimeAndPreservesUnknownAndZero() {
        assertEquals(120, averageWalkingCadence(1200, 600_000))
        assertEquals(0, averageWalkingCadence(0, 600_000))
        assertNull(averageWalkingCadence(null, 600_000))
        assertNull(averageWalkingCadence(100, 0))
        assertNull(averageWalkingCadence(-1, 600_000))
    }
}
