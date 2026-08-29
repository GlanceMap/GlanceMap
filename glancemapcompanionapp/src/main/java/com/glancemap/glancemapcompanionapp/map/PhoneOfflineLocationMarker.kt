package com.glancemap.glancemapcompanionapp.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.layer.overlay.Marker
import kotlin.math.floor

/** Mapsforge rotates the map by the inverse of the semantic MapLibre-style bearing. */
internal fun mapsforgeRotationDegreesFor(mapBearingDegrees: Float): Float =
    normalizePhoneHeadingDegrees(-mapBearingDegrees)
        .let { degrees -> if (degrees > 180f) degrees - 360f else degrees }

internal class PhoneOfflineLocationMarker(
    location: LatLong,
) : Marker(
        location,
        phoneLocationMarkerBitmap(),
        -PHONE_LOCATION_MARKER_SIZE_PX / 2,
        -PHONE_LOCATION_MARKER_SIZE_PX / 2,
    ) {
    var heading: Float = 0f
        set(value) {
            field = normalizePhoneHeadingDegrees(value)
        }

    @Volatile private var cachedZoom: Byte = (-1).toByte()

    @Volatile private var cachedTileSize = -1

    @Volatile private var cachedMapSize = 0L

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: org.mapsforge.core.graphics.Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) {
        if (isVisible) {
            val markerLocation = latLong
            val markerBitmap = bitmap
            if (markerLocation != null && markerBitmap != null && boundingBox.contains(markerLocation)) {
                val mapSize = cachedMapSize(zoomLevel, displayModel.tileSize)
                val pixelX = MercatorProjection.longitudeToPixelX(markerLocation.longitude, mapSize) - topLeft.x
                val pixelY = MercatorProjection.latitudeToPixelY(markerLocation.latitude, mapSize) - topLeft.y
                val drawX = floor(pixelX + horizontalOffset).toInt()
                val drawY = floor(pixelY + verticalOffset).toInt()
                val pivotX = drawX + markerBitmap.width / 2f
                val pivotY = drawY + markerBitmap.height / 2f
                canvas.save()
                canvas.rotate(normalizePhoneHeadingDegrees(heading - mapViewRotation.degrees), pivotX, pivotY)
                canvas.drawBitmap(markerBitmap, drawX, drawY)
                canvas.restore()
            }
        }
    }

    private fun cachedMapSize(
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

private fun phoneLocationMarkerBitmap(): AndroidBitmap {
    val bitmap =
        Bitmap.createBitmap(
            PHONE_LOCATION_MARKER_SIZE_PX,
            PHONE_LOCATION_MARKER_SIZE_PX,
            Bitmap.Config.ARGB_8888,
        )
    val center = PHONE_LOCATION_MARKER_SIZE_PX / 2f
    Canvas(bitmap).apply {
        drawCircle(
            center,
            center,
            PHONE_LOCATION_MARKER_RADIUS_PX,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 118, 210) },
        )
        drawLine(
            center,
            center,
            center,
            PHONE_LOCATION_MARKER_TIP_Y_PX,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = PHONE_LOCATION_MARKER_DIRECTION_WIDTH_PX
                strokeCap = Paint.Cap.ROUND
            },
        )
    }
    return AndroidBitmap(bitmap)
}

private const val PHONE_LOCATION_MARKER_SIZE_PX = 32
private const val PHONE_LOCATION_MARKER_RADIUS_PX = 11f
private const val PHONE_LOCATION_MARKER_TIP_Y_PX = 5f
private const val PHONE_LOCATION_MARKER_DIRECTION_WIDTH_PX = 3f
