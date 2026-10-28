package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Test

class TerrainSelectionTest {
    @Test
    fun selectionUsesMapsforgeUnlessEnabledAndReady() {
        assertEquals(TerrainRendererSelection.MAPSFORGE, selectTerrainRenderer(false, false, false))
        assertEquals(TerrainRendererSelection.MAPSFORGE, selectTerrainRenderer(true, false, false))
        assertEquals(TerrainRendererSelection.TERRAIN, selectTerrainRenderer(true, true, false))
        assertEquals(TerrainRendererSelection.MAPSFORGE, selectTerrainRenderer(true, true, true))
    }
}
