package com.glancemap.glancemapwearos.presentation.features.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGpsTogglePolicyTest {
    @Test
    fun gpsStaysOffUntilLocationPermissionIsGranted() {
        assertEquals(HomeGpsToggleAction.REQUEST_LOCATION, homeGpsToggleAction(gpsOff = true, locationGranted = false))
        assertEquals(HomeGpsToggleAction.TURN_ON, homeGpsToggleAction(gpsOff = true, locationGranted = true))
        assertEquals(HomeGpsToggleAction.TURN_OFF, homeGpsToggleAction(gpsOff = false, locationGranted = true))
        assertEquals(HomeGpsToggleAction.TURN_OFF, homeGpsToggleAction(gpsOff = false, locationGranted = false))
    }
}
