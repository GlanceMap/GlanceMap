package com.glancemap.glancemapwearos.presentation.features.navigate

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.glancemap.glancemapwearos.presentation.features.maps.DisplayFrameLayer
import com.glancemap.glancemapwearos.presentation.features.maps.DisplayFrameLayerOwner
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.layer.Layer
import kotlin.math.floor
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas

/**
 * Compass quality cone rendered as a real Mapsforge layer so it can sit below the location marker.
 */
internal class CompassConeLayer(
    override val displayFrameOwner: DisplayFrameLayerOwner? = null,
) : Layer(),
    DisplayFrameLayer {
    var anchorMarker: com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker? = null
    var headingDeg: Float = 0f
        set(value) {
            field = normalize360(value)
        }
    var headingErrorDeg: Float? = null
    var quality: CompassMarkerQuality = CompassMarkerQuality.GOOD
    var baseMarkerSizePx: Int = 0

    @Volatile private var cachedZoom: Byte = (-1).toByte()

    @Volatile private var cachedTileSize: Int = -1

    @Volatile private var cachedMapSize: Long = 0L

    private var cachedConeSizePx: Int = -1
    private var cachedConeBitmapKey: ConeBitmapKey? = null
    private var cachedConeBitmap: org.mapsforge.core.graphics.Bitmap? = null

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
        val ll = anchorMarker?.latLong ?: return
        if (!boundingBox.contains(ll)) return

        val bitmap =
            coneBitmapForAppearance(
                targetQuality = quality,
                targetHeadingErrorDeg = headingErrorDeg,
            ) ?: return
        val tileSize = displayModel.tileSize
        val mapSize = getCachedMapSize(zoomLevel, tileSize)

        val pixelX = MercatorProjection.longitudeToPixelX(ll.longitude, mapSize) - topLeft.x
        val pixelY = MercatorProjection.latitudeToPixelY(ll.latitude, mapSize) - topLeft.y

        val drawX = floor(pixelX - bitmap.width / 2f).toInt()
        val drawY = floor(pixelY - bitmap.height / 2f).toInt()
        val pivotX = drawX + bitmap.width / 2f
        val pivotY = drawY + bitmap.height / 2f

        val mapDeg = mapViewRotation.degrees
        val effective = normalize360(headingDeg - mapDeg)

        canvas.save()
        if (effective != 0f) {
            canvas.rotate(effective, pivotX, pivotY)
        }
        canvas.drawBitmap(bitmap, drawX, drawY)
        canvas.restore()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearConeBitmaps()
    }

    private fun coneBitmapForAppearance(
        targetQuality: CompassMarkerQuality,
        targetHeadingErrorDeg: Float?,
    ): org.mapsforge.core.graphics.Bitmap? {
        val coneSizePx = computeConeBitmapSizePx()
        if (coneSizePx <= 0) return null
        val targetKey =
            ConeBitmapKey(
                quality = targetQuality,
                headingErrorBucket = quantizeHeadingErrorBucket(targetHeadingErrorDeg),
            )
        if (cachedConeSizePx != coneSizePx || cachedConeBitmapKey != targetKey || cachedConeBitmap == null) {
            rebuildConeBitmap(
                sizePx = coneSizePx,
                key = targetKey,
                headingErrorDeg = targetHeadingErrorDeg,
            )
        }
        return cachedConeBitmap
    }

    private fun rebuildConeBitmap(
        sizePx: Int,
        key: ConeBitmapKey,
        headingErrorDeg: Float?,
    ) {
        clearConeBitmaps()
        val androidBitmap =
            buildConeBitmap(
                sizePx = sizePx,
                quality = key.quality,
                headingErrorDeg = headingErrorDeg,
            )
        cachedConeBitmap = AndroidBitmap(androidBitmap)
        cachedConeBitmapKey = key
        cachedConeSizePx = sizePx
    }

    private fun clearConeBitmaps() {
        cachedConeBitmap?.decrementRefCount()
        cachedConeBitmap = null
        cachedConeBitmapKey = null
        cachedConeSizePx = -1
    }

    private fun computeConeBitmapSizePx(): Int {
        if (baseMarkerSizePx > 0) {
            return baseMarkerSizePx
        }
        val scaleFactor = displayModel.scaleFactor
        val fallback = (58f * scaleFactor).roundToInt()
        return fallback.coerceIn(72, 180)
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

private fun buildConeBitmap(
    sizePx: Int,
    quality: CompassMarkerQuality,
    headingErrorDeg: Float?,
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val coneColor = coneColorArgbForQuality(quality)
    val widthScale = coneWidthScaleForHeadingError(headingErrorDeg, quality)

    val center = sizePx * 0.5f
    val pivotY = sizePx * 0.5f
    val coneStartY = pivotY
    val baseConeLength = sizePx * 0.43f
    val coneLength = baseConeLength
    val wideY = (coneStartY - coneLength).coerceAtLeast(sizePx * 0.02f)
    val baseHalfWidth = sizePx * 0.30f
    val halfWidth = (baseHalfWidth * widthScale).coerceAtMost(sizePx * 0.48f)
    val leftBaseX = center - halfWidth
    val rightBaseX = center + halfWidth
    val shoulderHalfWidth = (sizePx * 0.10f * widthScale).coerceAtMost(sizePx * 0.30f)
    val shoulderY = coneStartY - sizePx * 0.05f
    val leftShoulderX = center - shoulderHalfWidth
    val rightShoulderX = center + shoulderHalfWidth

    val conePath =
        Path().apply {
            moveTo(center, coneStartY)
            lineTo(leftShoulderX, shoulderY)
            lineTo(leftBaseX, wideY)
            lineTo(rightBaseX, wideY)
            lineTo(rightShoulderX, shoulderY)
            close()
        }

    val gradientPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader =
                LinearGradient(
                    center,
                    coneStartY,
                    center,
                    wideY,
                    intArrayOf(
                        withAlpha(coneColor, 0f),
                        withAlpha(coneColor, 0.74f),
                        withAlpha(coneColor, 0.50f),
                        withAlpha(coneColor, 0.18f),
                    ),
                    floatArrayOf(0f, 0.14f, 0.62f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
    val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = withAlpha(coneColor, 0.34f)
            strokeWidth = sizePx * 0.010f
        }

    canvas.drawPath(conePath, gradientPaint)
    canvas.drawPath(conePath, strokePaint)
    return bitmap
}

private fun withAlpha(
    argb: Int,
    alpha: Float,
): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    return (argb and 0x00FFFFFF) or (a shl 24)
}

private fun normalize360(deg: Float): Float = (deg % 360f + 360f) % 360f

private data class ConeBitmapKey(
    val quality: CompassMarkerQuality,
    val headingErrorBucket: Int?,
)

private fun quantizeHeadingErrorBucket(headingErrorDeg: Float?): Int? {
    val safeHeadingErrorDeg = headingErrorDeg?.takeIf { it.isFinite() && it >= 0f } ?: return null
    return (safeHeadingErrorDeg.coerceIn(0f, 60f) / 2f).roundToInt()
}
