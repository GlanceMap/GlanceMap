package com.glancemap.glancemapcompanionapp.map

import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.view.FrameBuffer
import kotlin.math.pow

internal data class PhoneMapsforgeDisplayFrameCamera(
    val position: MapPosition,
    val dimension: Dimension,
    val scaleFactor: Double,
    val pivot: LatLong?,
    val center: Point,
)

/** Same live-image transform as the watch: keep the rendered tiles, use the current camera bearing. */
internal class PhoneMapsforgeDisplayFrameTransform(
    bufferPosition: MapPosition,
    private val camera: PhoneMapsforgeDisplayFrameCamera,
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
}
