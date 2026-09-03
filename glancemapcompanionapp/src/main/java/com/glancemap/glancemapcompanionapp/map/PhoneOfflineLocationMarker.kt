package com.glancemap.glancemapcompanionapp.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.layer.overlay.Marker
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/** Mapsforge rotates the map by the inverse of the semantic MapLibre-style bearing. */
internal fun mapsforgeRotationDegreesFor(mapBearingDegrees: Float): Float =
    normalizePhoneHeadingDegrees(-mapBearingDegrees)
        .let { degrees -> if (degrees > 180f) degrees - 360f else degrees }

/** Keeps the marker's semantic heading stable while Mapsforge rotates the map canvas. */
internal fun phoneOfflineMarkerScreenRotationDegrees(
    markerHeadingDegrees: Float,
    mapRotationDegrees: Float,
): Float = normalizePhoneHeadingDegrees(markerHeadingDegrees - mapRotationDegrees)

internal class PhoneOfflineLocationMarker(
    location: LatLong,
    val markerStyle: PhoneMapMarkerStyle,
    private val onFirstDrawCall: (PhoneOfflineLocationMarker) -> Unit = {},
) : Marker(
        location,
        phoneLocationMarkerBitmap(markerStyle),
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

    private val drawCallCount = AtomicInteger()

    private val bitmapDrawCount = AtomicInteger()

    private val firstDrawCallObserved = AtomicBoolean(false)

    @Volatile private var lastDrawOutcome = "not_called"

    val drawCalls: Int
        get() = drawCallCount.get()

    val bitmapDrawObserved: Boolean
        get() = bitmapDrawCount.get() > 0

    val lastDrawResult: String
        get() = lastDrawOutcome

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: org.mapsforge.core.graphics.Canvas,
        topLeft: Point,
        mapViewRotation: Rotation,
    ) {
        drawCallCount.incrementAndGet()
        val markerLocation = latLong
        val markerBitmap = bitmap
        when {
            !isVisible -> lastDrawOutcome = "hidden"
            markerLocation == null || markerBitmap == null -> lastDrawOutcome = "missing_geometry"
            !boundingBox.contains(markerLocation) -> lastDrawOutcome = "outside_viewport"
            else -> {
                val mapSize = cachedMapSize(zoomLevel, displayModel.tileSize)
                val pixelX = MercatorProjection.longitudeToPixelX(markerLocation.longitude, mapSize) - topLeft.x
                val pixelY = MercatorProjection.latitudeToPixelY(markerLocation.latitude, mapSize) - topLeft.y
                val drawX = floor(pixelX + horizontalOffset).toInt()
                val drawY = floor(pixelY + verticalOffset).toInt()
                val pivotX = drawX + markerBitmap.width / 2f
                val pivotY = drawY + markerBitmap.height / 2f
                val effectiveRotation = phoneOfflineMarkerScreenRotationDegrees(heading, mapViewRotation.degrees)
                canvas.save()
                if (effectiveRotation != 0f) {
                    canvas.rotate(effectiveRotation, pivotX, pivotY)
                }
                canvas.drawBitmap(markerBitmap, drawX, drawY)
                canvas.restore()
                bitmapDrawCount.incrementAndGet()
                lastDrawOutcome = "bitmap_drawn"
            }
        }
        notifyFirstDrawCallIfNeeded()
    }

    private fun notifyFirstDrawCallIfNeeded() {
        if (firstDrawCallObserved.compareAndSet(false, true)) onFirstDrawCall(this)
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

private fun phoneLocationMarkerBitmap(style: PhoneMapMarkerStyle): AndroidBitmap {
    val bitmap =
        Bitmap.createBitmap(
            PHONE_LOCATION_MARKER_SIZE_PX,
            PHONE_LOCATION_MARKER_SIZE_PX,
            Bitmap.Config.ARGB_8888,
        )
    val canvas = Canvas(bitmap)
    val size = PHONE_LOCATION_MARKER_SIZE_PX.toFloat()
    val center = size / 2f
    if (style == PhoneMapMarkerStyle.TRIANGLE) {
        val path =
            Path().apply {
                moveTo(center, size * 0.05f)
                lineTo(size * 0.20f, size * 0.88f)
                lineTo(center, size * 0.70f)
                lineTo(size * 0.80f, size * 0.88f)
                close()
            }
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PHONE_NAVIGATION_MARKER_BLUE_ARGB
                this.style = Paint.Style.FILL
            },
        )
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAA001A33.toInt()
                this.style = Paint.Style.STROKE
                strokeWidth = size * 0.04f
            },
        )
    } else {
        val radius = size * 0.23f
        canvas.drawCircle(
            center,
            center,
            radius + size * 0.01f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xE6FFFFFF.toInt()
                this.style = Paint.Style.STROKE
                strokeWidth = size * 0.06f
            },
        )
        canvas.drawCircle(
            center,
            center,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PHONE_NAVIGATION_MARKER_BLUE_ARGB
                this.style = Paint.Style.FILL
            },
        )
        canvas.drawCircle(
            center,
            center,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAA000000.toInt()
                this.style = Paint.Style.STROKE
                strokeWidth = size * 0.03f
            },
        )
    }
    return AndroidBitmap(bitmap)
}

private const val PHONE_LOCATION_MARKER_SIZE_PX = 32
private const val PHONE_NAVIGATION_MARKER_BLUE_ARGB = 0xFF007AFF.toInt()
