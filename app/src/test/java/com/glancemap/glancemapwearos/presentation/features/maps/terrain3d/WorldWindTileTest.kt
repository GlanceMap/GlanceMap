package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWindTileTest {
    @Test
    fun `mercator tile bounds and coverage are geographic`() {
        val bounds = worldWindTileBounds(1, 1, 1)
        assertEquals(0.0, bounds.west, 1e-8)
        assertEquals(180.0, bounds.east, 1e-8)
        assertEquals(0.0, bounds.north, 1e-8)
        assertEquals(-85.05112878, bounds.south, 1e-6)
        assertTrue(bounds.intersects(-10.0, 10.0, -1.0, 20.0))
        assertFalse(bounds.intersects(1.0, 10.0, 10.0, 20.0))
    }

    @Test
    fun `tile identity includes every rendering input`() {
        val key = WorldWindTileKey("map", "theme", 1f, 1, 1, 1, 256)
        assertFalse(key == key.copy(theme = "other"))
        assertFalse(key == key.copy(map = "other"))
        assertFalse(key == key.copy(scale = 2f))
        assertFalse(key == key.copy(resolution = 512))
        assertFalse(key == key.copy(x = 0))
    }
}
