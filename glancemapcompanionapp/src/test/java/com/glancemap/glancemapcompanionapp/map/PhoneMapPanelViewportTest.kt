package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class PhoneMapPanelViewportTest {
    @Test
    fun openingABottomPanelMovesTheCenterUpSoTheVisibleMapStays() {
        // The map shrinks from 2000 px to 1000 px tall: the new center is 500 px above the old one.
        val center = requireNotNull(phoneMapCenterKeepingVisibleMap(camera(bearing = 0f), 1000, 2000, 1000, 1000))

        assertEquals(LATITUDE + metersToLatitude(500.0), center.latitude, 1e-9)
        assertEquals(LONGITUDE, center.longitude, 1e-9)
    }

    @Test
    fun closingThePanelMovesTheCenterBackDown() {
        val center = requireNotNull(phoneMapCenterKeepingVisibleMap(camera(bearing = 0f), 1000, 1000, 1000, 2000))

        assertEquals(LATITUDE - metersToLatitude(500.0), center.latitude, 1e-9)
    }

    @Test
    fun aRotatedMapMovesAlongTheScreenUpDirection() {
        // Facing east, screen-up is east: the center moves east, not north.
        val center = requireNotNull(phoneMapCenterKeepingVisibleMap(camera(bearing = 90f), 1000, 2000, 1000, 1000))

        assertEquals(LATITUDE, center.latitude, 1e-9)
        assertEquals(true, center.longitude > LONGITUDE)
    }

    @Test
    fun aSidePanelOnAWideScreenMovesTheCenterLeft() {
        val center = requireNotNull(phoneMapCenterKeepingVisibleMap(camera(bearing = 0f), 2000, 1000, 1000, 1000))

        assertEquals(LATITUDE, center.latitude, 1e-9)
        assertEquals(true, center.longitude < LONGITUDE)
    }

    @Test
    fun nothingMovesWithoutASizeChangeOrAGroundScale() {
        assertNull(phoneMapCenterKeepingVisibleMap(camera(bearing = 0f), 1000, 2000, 1000, 2000))
        assertNull(phoneMapCenterKeepingVisibleMap(camera(bearing = 0f, resolution = null), 1000, 2000, 1000, 1000))
        assertNull(phoneMapCenterKeepingVisibleMap(camera(bearing = 0f), 0, 0, 1000, 1000))
    }

    @Test
    fun onlyPanelChangesCountAsAPanelResize() {
        val closed = MapToolPanelState().layoutKey()
        val open = MapToolPanelState().select(MapTool.LAYER).layoutKey()

        assertEquals(false, closed.open)
        assertEquals(true, open.open)
        assertEquals(closed, MapToolPanelState().layoutKey())
    }

    private fun camera(
        bearing: Float,
        resolution: Double? = 2.0,
    ) = PhoneMapCameraSnapshot(LATITUDE, LONGITUDE, 14.0, bearing, resolution)

    private fun metersToLatitude(meters: Double) = meters * 2.0 / 6_371_008.8 * 180.0 / PI

    private companion object {
        const val LATITUDE = 49.8
        const val LONGITUDE = 9.93
    }
}
