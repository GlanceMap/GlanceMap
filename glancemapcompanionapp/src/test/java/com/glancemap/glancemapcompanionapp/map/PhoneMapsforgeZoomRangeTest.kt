package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.model.MapViewPosition

class PhoneMapsforgeZoomRangeTest {
    @Test
    fun zoomRangeTransitionsRespectMapsforgePositionConstraints() {
        val position = MapViewPosition(DisplayModel())
        try {
            position.setZoomLevelRange(14.toByte(), 14.toByte())

            position.setZoomLevelRange(15.toByte(), 15.toByte())
            assertZoomRange(position, 15, 15)

            position.setZoomLevelRange(14.toByte(), 14.toByte())
            assertZoomRange(position, 14, 14)

            position.setZoomLevelRange(13.toByte(), 15.toByte())
            assertZoomRange(position, 13, 15)

            position.setZoomLevelRange(13.toByte(), 15.toByte())
            assertZoomRange(position, 13, 15)
        } finally {
            position.destroy()
        }
    }

    private fun assertZoomRange(
        position: MapViewPosition,
        min: Int,
        max: Int,
    ) {
        assertEquals(min, position.zoomLevelMin.toInt())
        assertEquals(max, position.zoomLevelMax.toInt())
    }
}
