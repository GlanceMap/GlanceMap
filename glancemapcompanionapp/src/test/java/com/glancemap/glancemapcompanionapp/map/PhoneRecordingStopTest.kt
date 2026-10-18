package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRecordingStopTest {
    @Test
    fun missingOrSingleGpsPointAlwaysOffersAChoice() {
        assertTrue(phoneRecordingNeedsStopConfirmation(0, 120_000L, 100.0))
        assertTrue(phoneRecordingNeedsStopConfirmation(1, 120_000L, 100.0))
    }

    @Test
    fun accidentalShortRecordingAsksBeforeSavingEvenWithATrack() {
        assertTrue(phoneRecordingNeedsStopConfirmation(20, 59_999L, 100.0))
        assertFalse(phoneRecordingNeedsStopConfirmation(20, 60_000L, 100.0))
    }

    @Test
    fun negligibleMovementStillOffersDiscardAfterWaitingForGps() {
        assertTrue(phoneRecordingNeedsStopConfirmation(20, 120_000L, 9.99))
        assertFalse(phoneRecordingNeedsStopConfirmation(20, 120_000L, 10.0))
    }

    @Test
    fun establishedRecordingKeepsTheExistingDirectSave() {
        assertFalse(phoneRecordingNeedsStopConfirmation(100, 600_000L, 500.0))
    }
}
