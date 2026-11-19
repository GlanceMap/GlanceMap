package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAutoResumeRestartBurstBudgetTest {
    private val resumeSpeed = 2.5f

    @Test
    fun poorFixWithMovingSpeedRequestsOneBurstPerPause() {
        val budget = RecordingAutoResumeRestartBurstBudget(cooldownMillis = 120_000L)
        budget.onPauseStarted()

        // 2026-11-17 red light: a 49.5 m fix arrives while already riding.
        assertTrue(budget.request(reliable = false, speed = 5.0f, at = 45_000L))
        assertFalse(budget.request(reliable = false, speed = 5.0f, at = 53_000L))
    }

    @Test
    fun goodFixesSlowFixesAndPositionOnlyDriftNeverRequestABurst() {
        val budget = RecordingAutoResumeRestartBurstBudget()
        budget.onPauseStarted()

        assertFalse(budget.request(reliable = true, speed = 5.0f, at = 1_000L))
        assertFalse(budget.request(reliable = false, speed = 0.4f, at = 2_000L))
        assertFalse(budget.request(reliable = false, speed = null, at = 3_000L))
        assertFalse(budget.request(reliable = false, speed = Float.NaN, at = 4_000L))
    }

    @Test
    fun aNewPauseCanBurstAgainOnlyAfterTheCooldown() {
        val budget = RecordingAutoResumeRestartBurstBudget(cooldownMillis = 120_000L)
        budget.onPauseStarted()
        assertTrue(budget.request(reliable = false, speed = 5.0f, at = 10_000L))

        // Stop-and-go: the next red light 60 s later is still inside the cooldown.
        budget.onPauseStarted()
        assertFalse(budget.request(reliable = false, speed = 5.0f, at = 70_000L))

        budget.onPauseStarted()
        assertTrue(budget.request(reliable = false, speed = 5.0f, at = 130_000L))
    }

    @Test
    fun resetClearsTheCooldownForANewRecording() {
        val budget = RecordingAutoResumeRestartBurstBudget(cooldownMillis = 120_000L)
        budget.onPauseStarted()
        assertTrue(budget.request(reliable = false, speed = 5.0f, at = 10_000L))
        budget.reset()
        assertTrue(budget.request(reliable = false, speed = 5.0f, at = 20_000L))
    }

    private fun RecordingAutoResumeRestartBurstBudget.request(
        reliable: Boolean,
        speed: Float?,
        at: Long,
    ): Boolean =
        requestIfNeeded(
            reliableFix = reliable,
            speedMps = speed,
            resumeSpeedMps = resumeSpeed,
            nowElapsedMillis = at,
        )
}
