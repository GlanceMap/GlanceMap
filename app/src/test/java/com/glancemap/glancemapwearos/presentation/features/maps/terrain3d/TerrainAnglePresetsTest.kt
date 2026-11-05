package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Test

class TerrainAnglePresetsTest {
    @Test
    fun `tapping cycles through 0 30 45 and 60 degrees`() {
        assertEquals(30, nextTerrainAnglePreset(0))
        assertEquals(45, nextTerrainAnglePreset(30))
        assertEquals(60, nextTerrainAnglePreset(45))
        assertEquals(0, nextTerrainAnglePreset(60))
        assertEquals(60, nextTerrainAnglePreset(50))
        assertEquals(0, nextTerrainAnglePreset(70))
    }

    @Test
    fun `legacy camera accepts the 30 degree preset`() {
        val pitch = TerrainPitch(60)
        pitch.setPresetViewingAngle(30)
        assertEquals(30f, pitch.viewingAngleDegrees, 0f)
        pitch.setPresetViewingAngle(0)
        assertEquals(0f, pitch.viewingAngleDegrees, 0f)
    }
}
