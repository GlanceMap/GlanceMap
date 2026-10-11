package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.maps.DisplayFrameLayer
import com.glancemap.glancemapwearos.presentation.features.maps.DisplayFrameLayerOwner
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.layer.Layer
import kotlin.math.roundToInt

internal class GpsAccuracyCircleLayer(
    private val fillPaint: Paint,
    private val strokePaint: Paint,
    override val displayFrameOwner: DisplayFrameLayerOwner? = null,
) : Layer(),
    DisplayFrameLayer {
    var anchorMarker: RotatableMarker? = null
    var radiusMeters: Float = 0f

    @Volatile private var cachedZoom: Byte = (-1).toByte()

    @Volatile private var cachedTileSize: Int = -1

    @Volatile private var cachedMapSize: Long = 0L

    override fun onAdd() {
        super.onAdd()
        displayFrameOwner?.addDisplayFrameLayer(this)
    }

    override fun onRemove() {
        displayFrameOwner?.removeDisplayFrameLayer(this)
        super.onRemove()
    }

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) {
        if (!displayOnFrame) drawDisplayFrame(boundingBox, zoomLevel, canvas, topLeft, mapViewRotation)
    }

    // Preserve the existing layer's unavailable/offscreen guards in the foreground path.
    @Suppress("ReturnCount")
    override fun drawDisplayFrame(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) {
        if (!isVisible) return

        val markerLatLong = anchorMarker?.latLong ?: return
        if (!boundingBox.contains(markerLatLong)) return

        val safeRadiusMeters = radiusMeters
        if (!safeRadiusMeters.isFinite() || safeRadiusMeters <= 0f) return

        val mapSize = getCachedMapSize(zoomLevel, displayModel.tileSize)
        val centerX =
            (MercatorProjection.longitudeToPixelX(markerLatLong.longitude, mapSize) - topLeft.x)
                .roundToInt()
        val centerY =
            (MercatorProjection.latitudeToPixelY(markerLatLong.latitude, mapSize) - topLeft.y)
                .roundToInt()

        val radiusPx =
            MercatorProjection
                .metersToPixels(
                    safeRadiusMeters,
                    markerLatLong.latitude,
                    mapSize,
                ).roundToInt()
                .coerceAtLeast(1)

        canvas.drawCircle(centerX, centerY, radiusPx, fillPaint)
        canvas.drawCircle(centerX, centerY, radiusPx, strokePaint)
    }

    private fun getCachedMapSize(
        zoomLevel: Byte,
        tileSize: Int,
    ): Long {
        if (cachedZoom != zoomLevel || cachedTileSize != tileSize) {
            cachedZoom = zoomLevel
            cachedTileSize = tileSize
            cachedMapSize = MercatorProjection.getMapSize(zoomLevel, tileSize)
        }
        return cachedMapSize
    }
}
