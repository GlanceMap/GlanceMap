package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWindSelectionTest {
    @Test
    fun `only selected renderer is eligible and disabled terrain activates neither`() {
        assertFalse(useWorldWind(false, "worldwind"))
        assertFalse(useWorldWind(true, "custom"))
        assertTrue(useWorldWind(true, "worldwind"))
        assertFalse(useWorldWind(true, "invalid"))
    }

    @Test
    fun `switch waits for outgoing renderer cleanup`() {
        assertFalse(customTerrainMayStart(true, "custom", worldWindRunning = true))
        assertTrue(customTerrainMayStart(true, "custom", worldWindRunning = false))
        assertFalse(worldWindMayStart(true, "worldwind", customViewPresent = true, customLoading = false))
        assertFalse(worldWindMayStart(true, "worldwind", customViewPresent = false, customLoading = true))
        assertTrue(worldWindMayStart(true, "worldwind", customViewPresent = false, customLoading = false))
        assertFalse(worldWindMayStart(false, "worldwind", customViewPresent = false, customLoading = false))
    }
}
