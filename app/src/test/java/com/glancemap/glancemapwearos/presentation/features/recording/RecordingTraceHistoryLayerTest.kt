package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.FillRule
import org.mapsforge.core.graphics.GraphicFactory
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Path
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.model.DisplayModel
import java.lang.reflect.Proxy

class RecordingTraceHistoryLayerTest {
    @Test
    fun drawingMatchesMapsforgePolylinesWithoutCopyingCoordinates() {
        val drawing = RecordingDrawing()
        val segments =
            listOf(
                listOf(LatLong(45.0, 6.0), LatLong(45.0001, 6.0003), LatLong(45.0002, 6.0)),
                listOf(LatLong(45.001, 6.002), LatLong(45.002, 6.001)),
            )
        val display = drawing.display
        val bounds = BoundingBox(44.0, 5.0, 46.0, 7.0)
        val topLeft = Point(1_000.5, 2_000.75)
        val rotation = Rotation(45f, 100f, 100f)
        listOf(false, true).forEach { following ->
            val renderState = recordingTraceRenderState(segments, following)
            listOf(12, 16, 18).forEach { zoom ->
                drawing.paths.clear()
                drawing.drawPolylines(renderState.segments, zoom.toByte(), bounds, topLeft, rotation)
                val reference = drawing.paths.toList()
                drawing.paths.clear()
                RecordingTraceHistoryLayer(drawing.paint, drawing.factory).apply {
                    displayModel = display
                    this.segments = renderState.segments
                    draw(bounds, zoom.toByte(), drawing.canvas, topLeft, rotation)
                }
                assertEquals(reference, drawing.paths)
            }
        }
    }

    private class RecordingDrawing {
        val display = DisplayModel().apply { setFixedTileSize(256) }
        val paths = mutableListOf<List<Triple<String, Float, Float>>>()
        val paint: Paint = proxy { name, _ -> if (name == "getStrokeWidth") 5f else null }
        val factory: GraphicFactory = proxy { name, _ -> if (name == "createPath") RecordingPath() else null }
        val canvas: Canvas =
            proxy { name, arguments ->
                if (name == "drawPath") paths += (arguments?.first() as RecordingPath).commands.toList()
                null
            }

        fun drawPolylines(
            segments: List<List<LatLong>>,
            zoom: Byte,
            bounds: BoundingBox,
            topLeft: Point,
            rotation: Rotation,
        ) {
            segments.filter { it.size >= 2 }.forEach { points ->
                Polyline(paint, factory).apply {
                    displayModel = display
                    latLongs.addAll(points)
                    draw(bounds, zoom, canvas, topLeft, rotation)
                }
            }
        }
    }

    private class RecordingPath : Path {
        val commands = mutableListOf<Triple<String, Float, Float>>()

        override fun clear() = commands.clear()

        override fun close() = Unit

        override fun isEmpty(): Boolean = commands.isEmpty()

        override fun lineTo(
            x: Float,
            y: Float,
        ) {
            commands += Triple("line", x, y)
        }

        override fun moveTo(
            x: Float,
            y: Float,
        ) {
            commands += Triple("move", x, y)
        }

        override fun quadTo(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ) = Unit

        override fun setFillRule(fillRule: FillRule) = Unit
    }

    companion object {
        private inline fun <reified T : Any> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
            requireNotNull(
                T::class.java.cast(
                    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
                        call(method.name, arguments)
                    },
                ),
            )
    }
}
