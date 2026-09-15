package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import java.io.OutputStream
import java.lang.reflect.Proxy

class RotatableMarkerTest {
    @Test
    fun cachedBitmapsSurviveRepeatedCurrentHistoricalSwitches() {
        val current = TestBitmap()
        val historical = TestBitmap()
        val marker = RotatableMarker(LatLong(1.0, 1.0), current, -12, -12)

        repeat(20) { index ->
            val expected = if (index % 2 == 0) historical else current
            marker.setBitmap(expected)

            assertSame(expected, marker.bitmap)
            assertReadable(current)
            assertReadable(historical)
        }

        marker.onDestroy()
        assertReadable(current)
        assertReadable(historical)

        val recreatedMarker = RotatableMarker(LatLong(1.0, 1.0), historical, -12, -12)
        recreatedMarker.setBitmap(current)
        recreatedMarker.onDestroy()

        current.decrementRefCount()
        historical.decrementRefCount()

        assertTrue(current.isDestroyed)
        assertTrue(historical.isDestroyed)
    }

    @Test
    fun drawSkipsDestroyedBitmap() {
        val bitmap = TestBitmap().apply { decrementRefCount() }
        val marker = RotatableMarker(LatLong(1.0, 1.0), bitmap, -12, -12)

        marker.draw(
            boundingBox = BoundingBox(0.0, 0.0, 2.0, 2.0),
            zoomLevel = 1,
            canvas = noOpCanvas(),
            topLeft = Point(0.0, 0.0),
            mapViewRotation = Rotation.NULL_ROTATION,
        )
    }

    private fun assertReadable(bitmap: TestBitmap) {
        assertFalse(bitmap.isDestroyed)
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
    }

    private fun noOpCanvas(): Canvas =
        Proxy.newProxyInstance(
            Canvas::class.java.classLoader,
            arrayOf(Canvas::class.java),
        ) { _, _, _ ->
            null
        } as Canvas

    private class TestBitmap : Bitmap {
        private var referenceCount = 0
        private var destroyed = false

        override fun getMutex(): Any = this

        override fun compress(outputStream: OutputStream) = Unit

        override fun decrementRefCount() {
            referenceCount -= 1
            if (referenceCount < 0) destroyed = true
        }

        override fun getHeight(): Int = checkNotDestroyed()

        override fun getWidth(): Int = checkNotDestroyed()

        override fun incrementRefCount() {
            referenceCount += 1
        }

        override fun isDestroyed(): Boolean = destroyed

        override fun scaleTo(
            width: Int,
            height: Int,
        ) = Unit

        override fun setBackgroundColor(color: Int) = Unit

        private fun checkNotDestroyed(): Int {
            check(!destroyed) { "Destroyed bitmap was read" }
            return 24
        }
    }
}
