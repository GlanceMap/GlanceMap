package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRecordingStopTest {
    @Test
    fun missingOrSingleGpsPointIsAShortRecording() {
        assertTrue(phoneRecordingIsShort(0, 120_000L, 100.0))
        assertTrue(phoneRecordingIsShort(1, 120_000L, 100.0))
    }

    @Test
    fun accidentalShortRecordingIsFlaggedEvenWithATrack() {
        assertTrue(phoneRecordingIsShort(20, 59_999L, 100.0))
        assertFalse(phoneRecordingIsShort(20, 60_000L, 100.0))
    }

    @Test
    fun negligibleMovementIsFlaggedAfterWaitingForGps() {
        assertTrue(phoneRecordingIsShort(20, 120_000L, 9.99))
        assertFalse(phoneRecordingIsShort(20, 120_000L, 10.0))
    }

    @Test
    fun establishedRecordingIsNotShort() {
        assertFalse(phoneRecordingIsShort(100, 600_000L, 500.0))
    }
}
