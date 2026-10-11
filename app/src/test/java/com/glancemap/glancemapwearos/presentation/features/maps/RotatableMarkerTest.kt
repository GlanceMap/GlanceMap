package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.Layers
import org.mapsforge.map.layer.Redrawer
import org.mapsforge.map.model.DisplayModel
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

    @Test
    fun displayFrameMarkerIsDrawnOnceAndCounterRotatesWithTheLiveCamera() {
        val rotations = mutableListOf<Float>()
        var bitmaps = 0
        val canvas =
            Proxy.newProxyInstance(Canvas::class.java.classLoader, arrayOf(Canvas::class.java)) { _, method, args ->
                when (method.name) {
                    "rotate" -> rotations.add(args[0] as Float)
                    "drawBitmap" -> bitmaps++
                }
                null
            } as Canvas
        val marker = RotatableMarker(LatLong(1.0, 1.0), TestBitmap(), -12, -12, displayFrameOwner = TestFrameOwner())
        marker.displayModel = DisplayModel()
        val bounds = BoundingBox(0.0, 0.0, 2.0, 2.0)
        marker.draw(bounds, 1, canvas, Point(0.0, 0.0), Rotation(20f, 0f, 0f))
        assertEquals(0, bitmaps)
        marker.drawDisplayFrame(bounds, 1, canvas, Point(0.0, 0.0), Rotation(90f, 0f, 0f))
        assertEquals(1, bitmaps)
        assertEquals(listOf(270f), rotations)
        marker.isVisible = false
        assertTrue(displayFrameLayers(listOf(marker)).isEmpty())
        marker.drawDisplayFrame(bounds, 1, canvas, Point(0.0, 0.0), Rotation(180f, 0f, 0f))
        assertEquals(1, bitmaps)
    }

    @Test
    fun foregroundPreservesListOrderAndExcludesBackgroundAndHiddenLayers() {
        val dot = RotatableMarker(LatLong(1.0, 1.0), TestBitmap(), 0, 0, displayFrameOwner = TestFrameOwner())
        val background = RotatableMarker(LatLong(1.0, 1.0), TestBitmap(), 0, 0)
        val inspection = DisplayFrameMarker(LatLong(1.0, 1.0), TestBitmap(), displayFrameOwner = TestFrameOwner())
        val hidden = DisplayFrameMarker(LatLong(1.0, 1.0), TestBitmap(), displayFrameOwner = TestFrameOwner())
        hidden.isVisible = false
        assertEquals(listOf(dot, inspection), displayFrameLayers(listOf(background, dot, hidden, inspection)))
        // Marker clipping must use buffer dimensions, not the smaller native view canvas.
        val frameCanvas = DisplayFrameCanvas(noOpCanvas(), Dimension(700, 700))
        assertEquals(700, frameCanvas.width)
        assertEquals(700, frameCanvas.height)
        assertEquals(Dimension(700, 700), frameCanvas.dimension)
    }

    @Test
    fun foregroundRegistrationTracksLayerLifecycleAndReordering() {
        val owner = TestFrameOwner()
        val dot = RotatableMarker(LatLong(1.0, 1.0), TestBitmap(), 0, 0, displayFrameOwner = owner)
        val inspection = DisplayFrameMarker(LatLong(1.0, 1.0), TestBitmap(), displayFrameOwner = owner)
        // Exercise Mapsforge's assign/unassign callbacks through the real layer collection.
        val constructor = Layers::class.java.getDeclaredConstructor(Redrawer::class.java, DisplayModel::class.java)
        constructor.isAccessible = true
        val layers = constructor.newInstance(Redrawer {}, DisplayModel())
        layers.add(dot)
        layers.add(inspection)
        assertEquals(listOf(dot, inspection), owner.layers)
        layers.remove(dot)
        layers.add(dot)
        assertEquals(listOf(inspection, dot), owner.layers)
        layers.remove(inspection)
        layers.remove(dot)
        assertTrue(owner.layers.isEmpty())
    }

    private class TestFrameOwner : DisplayFrameLayerOwner {
        val layers = mutableListOf<Layer>()

        override fun addDisplayFrameLayer(layer: Layer) {
            if (layer !in layers) layers.add(layer)
        }

        override fun removeDisplayFrameLayer(layer: Layer) {
            layers.remove(layer)
        }
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
