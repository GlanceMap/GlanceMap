package com.glancemap.glancemapcompanionapp.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneKnownWatchTest {
    @Test
    fun phonesThatNeverSawAWatchStayUnknown() {
        assertFalse(phoneWatchKnown(alreadyKnown = false, reachableNow = false, pairedNow = false))
    }

    @Test
    fun aReachableOrPairedWatchBecomesKnown() {
        assertTrue(phoneWatchKnown(alreadyKnown = false, reachableNow = true, pairedNow = false))
        assertTrue(phoneWatchKnown(alreadyKnown = false, reachableNow = false, pairedNow = true))
    }

    @Test
    fun aKnownWatchStaysKnownWhileOutOfRange() {
        assertTrue(phoneWatchKnown(alreadyKnown = true, reachableNow = false, pairedNow = false))
    }
}
