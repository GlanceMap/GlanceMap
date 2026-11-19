package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Test

class StayTogglePolicyTest {
    @Test
    fun firstManualChangeShowsTheExplanationInEitherDirection() {
        assertEquals(StayToggleAction.SHOW_INFO, stayToggleAction(enabled = true, tipShown = false))
        assertEquals(StayToggleAction.SHOW_INFO, stayToggleAction(enabled = false, tipShown = false))
    }

    @Test
    fun laterChangesApplyTheRequestedDirection() {
        assertEquals(StayToggleAction.TURN_OFF, stayToggleAction(enabled = true, tipShown = true))
        assertEquals(StayToggleAction.TURN_ON, stayToggleAction(enabled = false, tipShown = true))
    }

    @Test
    fun stayExplanationIsNotRepeatedBeforeTheNotificationRequest() {
        assertEquals(StayNotificationAction.REQUEST_PERMISSION, stayNotificationAction(justExplained = true))
        assertEquals(StayNotificationAction.SHOW_EXPLANATION, stayNotificationAction(justExplained = false))
    }
}
