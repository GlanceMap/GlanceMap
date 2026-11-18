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
    fun declinedPermissionsKeepStayOffAndShowTheRightExplanation() {
        assertEquals(FirstRunStep.STAY, nextFirstRunStepAfterLocation(granted = true))
        assertEquals(FirstRunStep.LOCATION_DENIED, nextFirstRunStepAfterLocation(granted = false))
        assertEquals(FirstRunStep.MAP, nextFirstRunStepAfterNotifications(granted = true))
        assertEquals(FirstRunStep.NOTIFICATIONS_DENIED, nextFirstRunStepAfterNotifications(granted = false))
        assertTrue(firstRunMayEnableStay(locationGranted = true, notificationsGranted = true))
        assertFalse(firstRunMayEnableStay(locationGranted = false, notificationsGranted = true))
        assertFalse(firstRunMayEnableStay(locationGranted = true, notificationsGranted = false))
    }
}
