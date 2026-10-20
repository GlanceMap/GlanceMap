package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.model.MapViewPosition
import kotlin.math.pow

class PhoneOfflineMarkerScaleTest {
    @Test
    fun integerZoomKeepsTheOnlineBitmapSize() {
        listOf(0, 7, 14, 22).forEach { zoom ->
            assertEquals(1f, phoneOfflineMarkerBitmapScale(zoom.toByte(), 2.0.pow(zoom)), 0f)
        }
    }

    @Test
    fun fractionalZoomKeepsTheSameScreenSizeAtDifferentDensities() {
        listOf(1f, 1.5f, 2.625f, 3.5f).forEach { density ->
            listOf(0.25, 7.5, 14.01, 14.25, 14.75, 14.99, 15.0, 15.01, 21.99).forEach { zoom ->
                val zoomLevel = zoom.toInt()
                val mapScaleFactor = 2.0.pow(zoom)
                val framebufferScale = mapScaleFactor / 2.0.pow(zoomLevel)
                val bitmapScale = phoneOfflineMarkerBitmapScale(zoomLevel.toByte(), mapScaleFactor)
                listOf(32f, 58f).forEach { sizeDp ->
                    val onlineSizePx = sizeDp * density * 0.85f
                    val offlineSizePx = onlineSizePx * bitmapScale * framebufferScale
                    assertEquals(onlineSizePx.toDouble(), offlineSizePx, 0.0001)
                }
            }
        }
    }

    @Test
    fun compensationUsesTheActualMapsforgeZoomScale() {
        val position = MapViewPosition(DisplayModel())
        try {
            listOf(14.0, 14.25, 14.75, 14.99, 15.0).forEach { zoom ->
                position.setZoom(zoom, false)
                val scale = phoneOfflineMarkerBitmapScale(position.zoomLevel, position.scaleFactor)
                val framebufferScale = position.scaleFactor / 2.0.pow(position.zoomLevel.toInt())
                assertEquals(1.0, scale * framebufferScale, 0.000001)
            }
        } finally {
            position.destroy()
        }
    }
}
