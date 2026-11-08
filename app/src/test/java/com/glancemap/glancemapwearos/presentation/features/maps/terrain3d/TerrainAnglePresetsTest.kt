package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Test

class TerrainAnglePresetsTest {
    @Test
    fun `tapping cycles through 0 60 and 80 degrees`() {
        assertEquals(60, nextTerrainAnglePreset(0))
        assertEquals(80, nextTerrainAnglePreset(60))
        assertEquals(0, nextTerrainAnglePreset(80))
        assertEquals(60, nextTerrainAnglePreset(50))
        assertEquals(60, nextTerrainAnglePreset(45))
        assertEquals(80, nextTerrainAnglePreset(70))
    }
}
