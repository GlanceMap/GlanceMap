package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.domain.sensors.normalize360Deg
import com.glancemap.glancemapwearos.domain.sensors.shortestAngleDiffDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CompassDisplayFrameReplayTest {
    @Test
    fun capturedPlateauAndBurstRemainBoundedWithoutDroppingSmallActiveSteps() {
        // 10 October 18:39 capture: heading held at 85.4 for about 121ms, then 90.2 -> 103.4.
        val samples = listOf(0L to 85.4f, 121L to 85.4f, 135L to 90.2f, 159L to 103.4f)
        val replay = Replay(85.3f)
        for (atMs in 0L..800L step 11L) {
            val target = samples.last { it.first <= atMs }.second
            replay.frame(target, atMs, frameMs = 11f)
        }
        assertEquals(103.4f, replay.displayed, 0.01f)
        assertTrue(replay.appliedSteps.any { abs(it) in 0.001f..0.2f })
        assertTrue(replay.appliedSteps.all { abs(it) <= 10f })
        assertTrue(replay.appliedSteps.all { it >= 0f })
    }

    @Test
    fun fullTurnsAndImmediateReversalsCrossNorthSmoothlyInBothDirections() {
        for (direction in listOf(-1f, 1f)) {
            val replay = Replay(350f)
            for (frame in 1..240) {
                val unwrappedTarget =
                    if (frame <= 120) 350f + direction * frame * 3f else 350f + direction * (240 - frame) * 3f
                replay.frame(normalize360Deg(unwrappedTarget), frame * 17L, 17f)
            }
            for (frame in 241..360) replay.frame(350f, frame * 17L, 17f)
            assertEquals(0f, shortestAngleDiffDeg(350f, replay.displayed), 0.01f)
            assertTrue(replay.appliedSteps.all { abs(it) <= 10f })
            assertTrue(replay.appliedSteps.any { it > 0f })
            assertTrue(replay.appliedSteps.any { it < 0f })
        }
    }

    @Test
    fun stationaryNoiseStillUsesTheExistingVisualDeadband() {
        for (delta in listOf(-0.15f, 0f, 0.15f)) {
            assertFalse(shouldAnimateNavigationHeading(delta, activeTurn = false))
            assertFalse(shouldApplyMapsforgeRotation(delta, highFrequencyRotation = false))
        }
        assertTrue(shouldAnimateNavigationHeading(0.1f, activeTurn = true))
        assertTrue(shouldApplyMapsforgeRotation(0.1f, highFrequencyRotation = true))
    }

    private class Replay(
        initialHeading: Float,
    ) {
        var displayed = initialHeading
        val appliedSteps = mutableListOf<Float>()
        private var lastAppliedAtMs = Long.MIN_VALUE

        fun frame(
            target: Float,
            atMs: Long,
            frameMs: Float,
        ) {
            val diff = shortestAngleDiffDeg(target, displayed)
            if (!shouldAnimateNavigationHeading(diff, activeTurn = true)) return
            val delta = resolveHeadingAnimationDelta(diff, activeTurn = true, frameDeltaMs = frameMs)
            assertTrue(shouldApplyMapsforgeRotation(delta, highFrequencyRotation = true))
            assertFalse(
                shouldThrottleMapsforgeRotation(
                    NavMode.COMPASS_FOLLOW,
                    atMs,
                    lastAppliedAtMs,
                    highFrequencyRotation = true,
                ),
            )
            displayed = normalize360Deg(displayed + delta)
            appliedSteps.add(delta)
            lastAppliedAtMs = atMs
        }
    }
}
