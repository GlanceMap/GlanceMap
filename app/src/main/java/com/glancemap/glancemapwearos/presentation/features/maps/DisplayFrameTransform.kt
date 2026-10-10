package com.glancemap.glancemapwearos.presentation.features.maps

import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.view.FrameBuffer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal data class DisplayFrameCamera(
    val position: MapPosition,
    val dimension: Dimension,
    val scaleFactor: Double,
    val pivot: LatLong?,
    val center: Point,
)

/** Mapsforge 0.30 buffer camera semantics, with the live rotation rather than the buffered one. */
internal class DisplayFrameTransform(
    val bufferPosition: MapPosition,
    val bufferDimension: Dimension,
    private val camera: DisplayFrameCamera,
    tileSize: Int,
) {
    val rotation: Rotation = camera.position.rotation

    private val mapSize = MercatorProjection.getMapSize(bufferPosition.zoomLevel, tileSize)
    private val bufferCenter = MercatorProjection.getPixel(bufferPosition.latLong, mapSize)
    private val currentCenter = MercatorProjection.getPixel(camera.position.latLong, mapSize)
    private val shift = Point(bufferCenter.x - currentCenter.x, bufferCenter.y - currentCenter.y)
    private val pivotDistance =
        camera.pivot?.let {
            val pixel = MercatorProjection.getPixel(it, mapSize)
            Point(pixel.x - bufferCenter.x, pixel.y - bufferCenter.y)
        } ?: Point(0.0, 0.0)
    private val scale = (camera.scaleFactor / 2.0.pow(bufferPosition.zoomLevel.toInt())).toFloat()
    private val offset =
        Point(
            camera.dimension.width * (camera.center.x - 0.5),
            camera.dimension.height * (camera.center.y - 0.5),
        )

    fun applyTo(frameBuffer: FrameBuffer) {
        frameBuffer.adjustMatrix(
            shift.x.toFloat(),
            shift.y.toFloat(),
            scale,
            camera.dimension,
            pivotDistance.x.toFloat(),
            pivotDistance.y.toFloat(),
            rotation,
            camera.center.x.toFloat(),
            camera.center.y.toFloat(),
        )
    }

    fun matrixValues(): FloatArray {
        // Match FrameBufferHA3: center, scale about the zoom pivot, offset, rotate, then pan.
        val centerX = bufferDimension.width * 0.5
        val centerY = bufferDimension.height * 0.5
        val hasPivot = pivotDistance.x != 0.0 || pivotDistance.y != 0.0
        val scalePivot = if (hasPivot) pivotDistance else offset
        val radians = rotation.radians
        val cos = cos(radians)
        val sin = sin(radians)
        val a = scale * cos
        val b = -scale * sin
        val d = scale * sin
        val e = scale * cos
        val panX = if (hasPivot) 0.0 else shift.x
        val panY = if (hasPivot) 0.0 else shift.y
        val x =
            (camera.dimension.width - bufferDimension.width) * 0.5 +
                (1 - scale) * (centerX + scalePivot.x) +
                scale * (offset.x + centerX - cos * centerX + sin * centerY) +
                a * panX + b * panY
        val y =
            (camera.dimension.height - bufferDimension.height) * 0.5 +
                (1 - scale) * (centerY + scalePivot.y) +
                scale * (offset.y + centerY - sin * centerX - cos * centerY) +
                d * panX + e * panY
        return floatArrayOf(a.toFloat(), b.toFloat(), x.toFloat(), d.toFloat(), e.toFloat(), y.toFloat(), 0f, 0f, 1f)
    }
}
