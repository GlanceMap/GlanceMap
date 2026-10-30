package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.*
import org.junit.Test

class PhoneMapVisualMotionTest {
    private fun fix(
        time: Long,
        longitude: Double = 0.0,
        speed: Float? = 2f,
        bearing: Float? = 90f,
        accuracy: Float? = 5f,
    ) = PhoneMapLocation(45.0, longitude, accuracy, time, speedMetersPerSecond = speed, bearingDegrees = bearing)

    @Test fun freshMovingFixPredictsButStopsAtTheHorizon() {
        val motion = PhoneMapVisualMotion()
        val original = fix(1_000)
        motion.update(original, 1_000, 2_000)
        val moving = motion.sample(2_000)!!
        assertTrue(moving.longitude > original.longitude)
        assertEquals(0.0, original.longitude, 0.0)
        val stopped = motion.sample(10_000)!!
        assertEquals(stopped.longitude, motion.sample(20_000)!!.longitude, 1e-12)
        assertFalse(motion.needsFrames(20_000))
    }

    @Test fun unsuitableOrOldFixesDoNotPredict() {
        for (value in listOf(fix(1_000, accuracy = 80f), fix(1_000, speed = null), fix(1_000, bearing = null))) {
            val motion = PhoneMapVisualMotion()
            motion.update(value, 1_000, 2_000)
            assertEquals(value.longitude, motion.sample(2_000)!!.longitude, 0.0)
            assertFalse(motion.needsFrames(2_000))
        }
        for (value in listOf(
            fix(1_000).copy(speedAccuracyMetersPerSecond = 3f),
            fix(1_000).copy(bearingAccuracyDegrees = 90f),
        )) {
            val motion = PhoneMapVisualMotion()
            motion.update(value, 1_000, 2_000)
            assertFalse(motion.needsFrames(2_000))
        }
        val motion = PhoneMapVisualMotion()
        motion.update(fix(1_000), 20_000, 2_000)
        assertEquals(0.0, motion.sample(21_000)!!.longitude, 0.0)
    }

    @Test fun newFixBlendsFromTheDisplayedPositionAndRejectsOlderFixes() {
        val motion = PhoneMapVisualMotion()
        motion.update(fix(1_000), 1_000, 2_000)
        val before = motion.sample(2_000)!!
        val after = motion.update(fix(2_000, longitude = 0.00001), 2_000, 2_000)!!
        assertEquals(before.longitude, after.longitude, 0.00000001)
        motion.update(fix(1_500, longitude = 1.0), 2_100, 2_000)
        assertTrue(motion.sample(2_200)!!.longitude < 0.01)
    }

    @Test fun stoppingSettlesToTheNewFixWithoutContinuingToPredict() {
        val motion = PhoneMapVisualMotion()
        motion.update(fix(1_000), 1_000, 2_000)
        motion.sample(2_000)
        val stopped = fix(2_000, longitude = 0.00001, speed = 0f)
        motion.update(stopped, 2_000, 2_000)
        assertEquals(stopped.longitude, motion.sample(3_500)!!.longitude, 0.00000001)
        assertFalse(motion.needsFrames(3_500))
    }

    @Test fun largeJumpsAndInvalidCoordinatesDoNotCreateATrajectory() {
        val motion = PhoneMapVisualMotion()
        motion.update(fix(1_000), 1_000, 2_000)
        val farAway = fix(2_000, longitude = 1.0)
        assertEquals(farAway.longitude, motion.update(farAway, 2_000, 2_000)!!.longitude, 0.0)
        val invalid = fix(3_000).copy(latitude = Double.NaN)
        assertTrue(motion.update(invalid, 3_000, 2_000)!!.latitude.isNaN())
        assertFalse(motion.needsFrames(3_000))
    }

    @Test fun resetDiscardsThePreviousTrajectory() {
        val motion = PhoneMapVisualMotion()
        motion.update(fix(1_000), 1_000, 2_000)
        motion.reset()
        assertNull(motion.sample(2_000))
        assertFalse(motion.needsFrames(2_000))
    }
}
