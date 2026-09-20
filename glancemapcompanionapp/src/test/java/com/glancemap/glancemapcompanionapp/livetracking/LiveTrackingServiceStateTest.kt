package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackingServiceStateTest {
    @Test
    fun blocksPositionSendsAfterPauseOrStopButAllowsStopControl() {
        assertTrue(canContinueLiveTrackingSend(isPaused = false, isStopping = false, stop = false))
        assertFalse(canContinueLiveTrackingSend(isPaused = true, isStopping = false, stop = false))
        assertFalse(canContinueLiveTrackingSend(isPaused = false, isStopping = true, stop = false))
        assertTrue(canContinueLiveTrackingSend(isPaused = true, isStopping = true, stop = true))
    }
}
