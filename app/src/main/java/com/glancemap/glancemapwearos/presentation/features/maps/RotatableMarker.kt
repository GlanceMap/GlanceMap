package com.glancemap.glancemapwearos.presentation.features.maps

import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.layer.overlay.Marker
import kotlin.math.floor

class RotatableMarker(
    latLong: LatLong,
    bitmap: Bitmap,
    horizontalOffset: Int,
    verticalOffset: Int,
) : Marker(latLong, bitmap, horizontalOffset, verticalOffset) {
    init {
        // Marker releases its bitmap but does not acquire it. Keep a separate reference from
        // the Compose cache so switching between cached marker appearances stays safe.
        bitmap.incrementRefCount()
    }

    var heading: Float = 0f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
        }

    @Volatile private var cachedZoom: Byte = (-1).toByte()

    @Volatile private var cachedTileSize: Int = -1

    @Volatile private var cachedMapSize: Long = 0L

    @Synchronized
    override fun setBitmap(bitmap: Bitmap) {
        if (this.bitmap === bitmap) return
        bitmap.incrementRefCount()
        super.setBitmap(bitmap)
    }

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) {
        if (!isVisible) return

        val ll = this.latLong ?: return
        val bmp = this.bitmap ?: return
        if (bmp.isDestroyed) return
        if (!boundingBox.contains(ll)) return

        val tileSize = displayModel.tileSize
        val mapSize = getCachedMapSize(zoomLevel, tileSize)

        val pixelX = MercatorProjection.longitudeToPixelX(ll.longitude, mapSize) - topLeft.x
        val pixelY = MercatorProjection.latitudeToPixelY(ll.latitude, mapSize) - topLeft.y

        val drawX = floor(pixelX + horizontalOffset).toInt()
        val drawY = floor(pixelY + verticalOffset).toInt()

        val pivotX = drawX + bmp.width / 2f
        val pivotY = drawY + bmp.height / 2f

        val mapDeg = mapViewRotation.degrees
        val effective = normalize360(heading - mapDeg)

        canvas.save()
        if (effective != 0f) {
            canvas.rotate(effective, pivotX, pivotY)
        }
        canvas.drawBitmap(bmp, drawX, drawY)
        canvas.restore()
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

    private fun normalize360(deg: Float): Float = (deg % 360f + 360f) % 360f
}
