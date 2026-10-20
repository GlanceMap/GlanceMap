package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mapsforge.core.graphics.GraphicFactory
import org.mapsforge.core.graphics.Matrix
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.model.FrameBufferModel
import org.mapsforge.map.view.FrameBufferHA3
import java.lang.reflect.Proxy
import kotlin.math.cos
import kotlin.math.sin

class PhoneMapsforgeDisplayFrameTransformTest {
    private val bufferDimension = Dimension(700, 700)
    private val viewDimension = Dimension(320, 440)
    private val origin = LatLong(0.0, 0.0)

    @Test
    fun liveRotationMovesTheSameRenderedImageWithoutAnotherLayerFrame() {
        val matrix = RecordingMatrix()
        val buffer = frameBuffer(matrix)
        try {
            val point = Point(400.0, 350.0)
            transform(0f).applyTo(buffer)
            val before = matrix.map(point)
            transform(90f).applyTo(buffer)
            val after = matrix.map(point)

            assertEquals(210.0, before.x, 0.001)
            assertEquals(220.0, before.y, 0.001)
            assertEquals(160.0, after.x, 0.001)
            assertEquals(270.0, after.y, 0.001)
            assertNotEquals(before, after)
        } finally {
            buffer.destroy()
        }
    }

    @Test
    fun swappingInAnOlderBackgroundBearingCannotRewindTheVisibleRotation() {
        val matrix = RecordingMatrix()
        val buffer = frameBuffer(matrix)
        try {
            val point = Point(400.0, 350.0)
            transform(180f, bufferedAngle = 0f).applyTo(buffer)
            val before = matrix.map(point)
            transform(180f, bufferedAngle = 90f).applyTo(buffer)

            assertEquals(before, matrix.map(point))
            assertEquals(110.0, before.x, 0.001)
            assertEquals(220.0, before.y, 0.001)
        } finally {
            buffer.destroy()
        }
    }

    @Test
    fun panPinchPivotAndLowerAnchorKeepTheLibrarysExistingProjection() {
        val anchors = listOf(0.5f to null, 0.82f to null, 0.5f to LatLong(0.0, 22.5), 0.82f to LatLong(0.0, 22.5))
        for (angle in listOf(0f, 90f, 180f, 270f, 359f, -1f)) {
            for (scale in listOf(1f, 1.5f)) {
                for ((centerY, pivot) in anchors) assertMatchesLibraryProjection(angle, scale, centerY, pivot)
            }
        }
    }

    private fun assertMatchesLibraryProjection(
        angle: Float,
        scale: Float,
        centerY: Float,
        pivot: LatLong?,
    ) {
        val actual = RecordingMatrix()
        val expected = RecordingMatrix()
        val buffer = frameBuffer(actual)
        val reference = frameBuffer(expected)
        try {
            val rotation = Rotation(angle, 160f, 220f)
            PhoneMapsforgeDisplayFrameTransform(
                MapPosition(origin, 3.0, Rotation(20f, 160f, 220f)),
                PhoneMapsforgeDisplayFrameCamera(
                    MapPosition(LatLong(0.0, 45.0), 3.0, rotation),
                    viewDimension,
                    8.0 * scale,
                    pivot,
                    Point(0.5, centerY.toDouble()),
                ),
                256,
            ).applyTo(buffer)
            // At zoom 3, 45 degrees is 256 px; the pinch pivot is 128 px from the buffer center.
            reference.adjustMatrix(
                -256f,
                0f,
                scale,
                viewDimension,
                if (pivot == null) 0f else 128f,
                0f,
                rotation,
                0.5f,
                centerY,
            )
            for (point in listOf(Point(350.0, 350.0), Point(123.0, 456.0), Point(650.0, 50.0))) {
                val target = expected.map(point)
                assertEquals(target.x, actual.map(point).x, 0.002)
                assertEquals(target.y, actual.map(point).y, 0.002)
            }
        } finally {
            buffer.destroy()
            reference.destroy()
        }
    }

    private fun transform(
        angle: Float,
        bufferedAngle: Float = 20f,
    ) = PhoneMapsforgeDisplayFrameTransform(
        MapPosition(origin, 3.0, Rotation(bufferedAngle, 160f, 220f)),
        PhoneMapsforgeDisplayFrameCamera(
            MapPosition(origin, 3.0, Rotation(angle, 160f, 220f)),
            viewDimension,
            8.0,
            null,
            Point(0.5, 0.5),
        ),
        256,
    )

    private fun frameBuffer(matrix: RecordingMatrix): FrameBufferHA3 {
        val graphics =
            Proxy.newProxyInstance(
                GraphicFactory::class.java.classLoader,
                arrayOf(GraphicFactory::class.java),
            ) { _, method, _ ->
                check(method.name == "createMatrix")
                matrix
            } as GraphicFactory
        return FrameBufferHA3(FrameBufferModel(), DisplayModel(), graphics).apply { setDimension(bufferDimension) }
    }

    /** Run the real Mapsforge matrix operations without Android's stub Matrix. */
    private class RecordingMatrix : Matrix {
        private val operations = mutableListOf<(Point) -> Point>()

        fun map(point: Point): Point = operations.asReversed().fold(point) { p, operation -> operation(p) }

        override fun reset() = operations.clear()

        override fun translate(
            x: Float,
            y: Float,
        ) {
            operations.add { Point(it.x + x, it.y + y) }
        }

        override fun scale(
            x: Float,
            y: Float,
        ) = scale(x, y, 0f, 0f)

        override fun scale(
            x: Float,
            y: Float,
            pivotX: Float,
            pivotY: Float,
        ) {
            operations.add { Point(pivotX + (it.x - pivotX) * x, pivotY + (it.y - pivotY) * y) }
        }

        override fun rotate(theta: Float) = rotate(theta, 0f, 0f)

        override fun rotate(
            theta: Float,
            pivotX: Float,
            pivotY: Float,
        ) {
            operations.add {
                val x = it.x - pivotX
                val y = it.y - pivotY
                Point(pivotX + cos(theta) * x - sin(theta) * y, pivotY + sin(theta) * x + cos(theta) * y)
            }
        }
    }
}
