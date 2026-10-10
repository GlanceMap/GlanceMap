package com.glancemap.glancemapwearos.presentation.features.maps

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

class DisplayFrameTransformTest {
    private val bufferDimension = Dimension(700, 700)
    private val viewDimension = Dimension(320, 440)
    private val origin = LatLong(45.0, 6.0)

    @Test
    fun foregroundMatchesTheActualMapsforgeBufferMatrixThroughTurnsPanZoomAndPivot() {
        val pivotPoint = LatLong(45.002, 6.003)
        val modes = listOf(1.0 to null, 1.0 to pivotPoint, 1.5 to null, 1.5 to pivotPoint)
        for (angle in listOf(0f, 90f, 180f, 270f, 359f, -1f)) {
            for ((zoomScale, pivot) in modes) {
                val transform =
                    transform(
                        angle = angle,
                        position = LatLong(45.001, 6.002),
                        zoomScale = zoomScale,
                        pivot = pivot,
                    )
                assertMatchesLibraryMatrix(transform)
            }
        }
    }

    private fun assertMatchesLibraryMatrix(transform: DisplayFrameTransform) {
        val libraryMatrix = RecordingMatrix()
        val buffer = frameBuffer(libraryMatrix)
        transform.applyTo(buffer)
        val values = transform.matrixValues()
        for (point in listOf(Point(350.0, 350.0), Point(123.0, 456.0), Point(650.0, 50.0))) {
            val expected = libraryMatrix.map(point)
            assertEquals(expected.x, values[0] * point.x + values[1] * point.y + values[2], 0.002)
            assertEquals(expected.y, values[3] * point.x + values[4] * point.y + values[5], 0.002)
        }
        buffer.destroy()
    }

    @Test
    fun aLiveTurnRotatesTheExistingImageWithoutWaitingForAnotherLayerFrame() {
        val matrix = RecordingMatrix()
        val buffer = frameBuffer(matrix)
        val point = Point(400.0, 350.0)
        transform(angle = 0f).applyTo(buffer)
        val before = matrix.map(point)
        transform(angle = 90f).applyTo(buffer)
        val after = matrix.map(point)
        assertEquals(210.0, before.x, 0.001)
        assertEquals(220.0, before.y, 0.001)
        assertEquals(160.0, after.x, 0.001)
        assertEquals(270.0, after.y, 0.001)
        assertNotEquals(before, after)
        buffer.destroy()
    }

    @Test
    fun aNewBackgroundImageDoesNotRestoreItsOlderHeading() {
        val first = transform(angle = 180f, bufferedAngle = 0f)
        val replacement = transform(angle = 180f, bufferedAngle = 90f)
        first.matrixValues().zip(replacement.matrixValues()).forEach { (a, b) ->
            assertEquals(a, b, 0f)
        }
        assertEquals(180f, replacement.rotation.degrees, 0f)
    }

    private fun transform(
        angle: Float,
        position: LatLong = origin,
        zoomScale: Double = 1.0,
        pivot: LatLong? = null,
        bufferedAngle: Float = 20f,
    ): DisplayFrameTransform =
        DisplayFrameTransform(
            MapPosition(origin, 3.0, Rotation(bufferedAngle, 160f, 220f)),
            bufferDimension,
            DisplayFrameCamera(
                MapPosition(position, 3.0, Rotation(angle, 160f, 220f)),
                viewDimension,
                8.0 * zoomScale,
                pivot,
                if (position == origin) Point(0.5, 0.5) else Point(0.5, 0.75),
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
        return FrameBufferHA3(FrameBufferModel(), DisplayModel(), graphics).apply {
            setDimension(bufferDimension)
        }
    }

    /** Execute the real library's pre-concatenated operations without Android's stub Matrix. */
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
