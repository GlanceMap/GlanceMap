package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Test

class TerrainModeButtonTest {
    @Test
    fun modeButtonReusesRecenterCompassAndNorthBeforeOrbit() {
        assertEquals(TerrainModeAction.RECENTER, terrainModeButtonAction(NavMode.PANNING, false, false))
        assertEquals(
            TerrainModeAction.TOGGLE_ORIENTATION,
            terrainModeButtonAction(NavMode.COMPASS_FOLLOW, false, false),
        )
        assertEquals(TerrainModeAction.ENTER_ORBIT, terrainModeButtonAction(NavMode.NORTH_UP_FOLLOW, false, false))
        assertEquals(TerrainModeAction.EXIT_ORBIT, terrainModeButtonAction(NavMode.NORTH_UP_FOLLOW, true, false))
    }

    @Test
    fun offlineModeCanEnterAndLeaveOrbitWithoutChangingNavigationMode() {
        assertEquals(TerrainModeAction.ENTER_ORBIT, terrainModeButtonAction(NavMode.PANNING, false, true))
        assertEquals(TerrainModeAction.EXIT_ORBIT, terrainModeButtonAction(NavMode.PANNING, true, true))
    }
}
