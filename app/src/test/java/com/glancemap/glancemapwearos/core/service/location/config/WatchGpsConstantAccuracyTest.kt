package com.glancemap.glancemapwearos.core.service.location.config

import com.glancemap.glancemapwearos.core.service.location.policy.FixAcceptancePolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationFixPolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WatchGpsConstantAccuracyTest {
    @Before
    fun setUp() = WatchGpsConstantAccuracy.reset()

    @After
    fun tearDown() = WatchGpsConstantAccuracy.reset()

    @Test
    fun anotherBrandsStuckValueIsLearnedAfterFiveIdenticalFixesOverTwentySeconds() {
        assertFalse(isKnownWatchGpsAccuracyFloor(100f))
        repeat(4) { index -> assertFalse(WatchGpsConstantAccuracy.observe(100f, index * 5_000L)) }
        assertFalse(isKnownWatchGpsAccuracyFloor(100f))
        assertTrue(WatchGpsConstantAccuracy.observe(100f, 20_000L))

        assertEquals(100f, WatchGpsConstantAccuracy.learnedAccuracyM ?: -1f, 0f)
        assertTrue(isKnownWatchGpsAccuracyFloor(100f))
        assertFalse(isKnownWatchGpsAccuracyFloor(100.2f))
        assertEquals(WATCH_GPS_EFFECTIVE_ACCURACY_M, resolveEffectiveWatchGpsAccuracyMeters(100f, true) ?: -1f, 0f)
    }

    @Test
    fun varyingSmallOrQuicklyRepeatedValuesAreNotLearned() {
        listOf(60.1f, 60.4f, 59.8f, 61.0f, 60.2f, 60.7f).forEachIndexed { index, accuracy ->
            WatchGpsConstantAccuracy.observe(accuracy, index * 10_000L)
        }
        repeat(8) { index -> WatchGpsConstantAccuracy.observe(4f, 100_000L + index * 10_000L) }
        repeat(6) { index -> WatchGpsConstantAccuracy.observe(80f, 300_000L + index * 1_000L) }

        assertNull(WatchGpsConstantAccuracy.learnedAccuracyM)
    }

    @Test
    fun theKnownOnePlusValueNeedsNoLearning() {
        assertTrue(isKnownWatchGpsAccuracyFloor(125f))
        assertTrue(isKnownWatchGpsAccuracyFloor(127.5f))
        assertNull(WatchGpsConstantAccuracy.learnedAccuracyM)
    }

    @Test
    fun aLearnedPlaceholderAboveTheWatchGpsLimitIsStillAccepted() {
        repeat(5) { index -> WatchGpsConstantAccuracy.observe(150f, index * 5_000L) }
        val policy =
            LocationFixPolicy.allowKnownWatchGpsFloor(
                FixAcceptancePolicy(maxAgeMs = 20_000L, maxAccuracyM = 130f),
                LocationSourceMode.WATCH_GPS,
                phoneConnected = true,
                floorMaxAccuracyM = WATCH_GPS_FLOOR_MAX_ACCEPTED_ACCURACY_M,
            )

        assertFalse(policy.rejectsAccuracy(150f))
        assertTrue(policy.rejectsAccuracy(140f))
    }
}
