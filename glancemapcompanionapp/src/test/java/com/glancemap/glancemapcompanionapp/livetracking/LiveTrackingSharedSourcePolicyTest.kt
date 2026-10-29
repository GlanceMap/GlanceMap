package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackingSharedSourcePolicyTest {
    @Test
    fun recentAccurateFixFromFastActiveSourceIsUsable() {
        assertTrue(shouldUseSharedLocation(true, 10_000L, 60_000L, 5_000L, 20f))
    }

    @Test
    fun frequentSharedFixesStillAdmitOnlyOneTransmissionPerTrackingWindow() {
        val cadence = LiveTrackingCadenceAdmission(60_000L, 1_000L)
        assertTrue(cadence.begin(cadence.observe(1_000L)))
        for (elapsed in 4_000L..58_000L step 3_000L) {
            assertFalse(cadence.begin(cadence.observe(elapsed)))
        }
        assertTrue(cadence.begin(cadence.observe(61_000L)))
    }

    @Test
    fun inactiveSlowStaleOrPoorSourcesRequireFallback() {
        assertFalse(shouldUseSharedLocation(false, 10_000L, 60_000L, 5_000L, 20f))
        assertFalse(shouldUseSharedLocation(true, 60_000L, 15_000L, 5_000L, 20f))
        assertFalse(shouldUseSharedLocation(true, 10_000L, 60_000L, 25_000L, 20f))
        assertFalse(shouldUseSharedLocation(true, 10_000L, 60_000L, 5_000L, 100f))
        assertFalse(shouldUseSharedLocation(true, 10_000L, 60_000L, -1L, 20f))
        assertFalse(shouldUseSharedLocation(true, 10_000L, 60_000L, 5_000L, Float.NaN))
    }
}
