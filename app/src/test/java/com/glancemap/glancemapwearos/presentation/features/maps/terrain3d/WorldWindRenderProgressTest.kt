package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldWindRenderProgressTest {
    @Test
    fun reportsVisibleTileProgressAndRestartsForANewView() {
        val progress = WorldWindRenderProgress()
        assertNull(progress.update(null, pendingElevation = 0, pendingImagery = 0, nowMs = 0))
        assertEquals(50, progress.update(WorldWindVisibleTiles(2, 4), 0, 2, 100))
        assertEquals(99, progress.update(WorldWindVisibleTiles(4, 4), 0, 0, 200))
        assertEquals(100, progress.update(WorldWindVisibleTiles(4, 4), 0, 0, 700))
        assertEquals(75, progress.update(WorldWindVisibleTiles(3, 4), 0, 1, 800))
    }

    @Test
    fun elevationAndImageryWorkPreventCompletionAndRestartTheQuietPeriod() {
        val progress = WorldWindRenderProgress()
        val visible = WorldWindVisibleTiles(4, 4)
        assertEquals(99, progress.update(visible, 1, 0, 0))
        assertEquals(99, progress.update(visible, 0, 0, 100))
        assertEquals(99, progress.update(visible, 0, 1, 500))
        assertEquals(99, progress.update(visible, 0, 0, 600))
        assertEquals(100, progress.update(visible, 0, 0, 1100))
    }
}
