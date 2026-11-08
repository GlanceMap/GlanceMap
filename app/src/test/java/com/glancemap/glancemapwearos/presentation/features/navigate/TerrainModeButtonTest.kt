package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Test

class TerrainModeButtonTest {
    @Test
    fun modeButtonReusesRecenterCompassAndNorthWithoutOrbit() {
        assertEquals(NavModeAction.RECENTER, navModeButtonAction(NavMode.PANNING))
        assertEquals(
            NavModeAction.TOGGLE_ORIENTATION,
            navModeButtonAction(NavMode.COMPASS_FOLLOW),
        )
        assertEquals(NavModeAction.TOGGLE_ORIENTATION, navModeButtonAction(NavMode.NORTH_UP_FOLLOW))
    }
}
