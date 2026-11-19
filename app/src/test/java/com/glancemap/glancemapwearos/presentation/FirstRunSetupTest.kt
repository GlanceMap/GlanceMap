package com.glancemap.glancemapwearos.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunSetupTest {
    @Test
    fun onlyFreshInstallsStartWelcomeWhileExistingUsersKeepReleaseNotes() {
        assertTrue(shouldStartFirstRunSetup(100L, 100L, lastShownVersion = 0, completed = false))
        assertFalse(shouldStartFirstRunSetup(100L, 200L, lastShownVersion = 0, completed = false))
        assertFalse(shouldStartFirstRunSetup(100L, 100L, lastShownVersion = 1040, completed = false))
        assertFalse(shouldStartFirstRunSetup(100L, 100L, lastShownVersion = 0, completed = true))
        assertFalse(shouldStartFirstRunSetup(0L, 0L, lastShownVersion = 0, completed = false))
    }

    @Test
    fun permissionDecisionsSelectTheRightSetupSteps() {
        assertEquals(FirstRunStep.STAY, nextFirstRunStepAfterLocation(granted = true))
        assertEquals(FirstRunStep.LOCATION_DENIED, nextFirstRunStepAfterLocation(granted = false))
        assertEquals(FirstRunStep.MAP, nextFirstRunStepAfterNotifications(granted = true))
        assertEquals(FirstRunStep.NOTIFICATIONS_DENIED, nextFirstRunStepAfterNotifications(granted = false))
        assertTrue(firstRunMayEnableStay(notificationsGranted = true))
        assertFalse(firstRunMayEnableStay(notificationsGranted = false))
    }

    @Test
    fun continuingWithoutLocationStillOffersNotificationsBeforeTheMap() {
        assertEquals(
            FirstRunStep.STAY,
            nextFirstRunStepAfterLocation(granted = false, continueWithoutLocation = true),
        )
        assertEquals(
            FirstRunStep.STAY_UNAVAILABLE,
            firstRunStepWhenStayCannotStart(notificationsGranted = true),
        )
        assertEquals(
            FirstRunStep.NOTIFICATIONS_DENIED,
            firstRunStepWhenStayCannotStart(notificationsGranted = false),
        )
    }
}
