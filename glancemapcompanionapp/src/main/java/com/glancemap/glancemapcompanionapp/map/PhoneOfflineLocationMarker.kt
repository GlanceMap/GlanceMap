package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.model.MapViewPosition
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

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
    private val context: Context,
    private val mapViewPosition: MapViewPosition? = null,
    private val onFirstDrawCall: (PhoneOfflineLocationMarker) -> Unit = {},
) : Marker(
        location,
        phoneLocationMarkerBitmap(context, markerStyle, compassRenderable = false, locationFresh = true),
        0,
        0,
    ) {
    private var hasCompassCone = false
    private var locationFresh = true
    private val bitmapMatrix = AndroidGraphicFactory.INSTANCE.createMatrix()

    init {
        centerBitmap()
    }

    fun updateCompassAppearance(compassRenderable: Boolean) {
        updateAppearance(compassConeRenderable = compassRenderable, locationFresh = true)
    }

    fun updateAppearance(
        compassConeRenderable: Boolean,
        locationFresh: Boolean,
    ) {
        val showCone = compassConeRenderable && locationFresh && markerStyle == PhoneMapMarkerStyle.DOT
        if (hasCompassCone == showCone && this.locationFresh == locationFresh) return
        bitmap = phoneLocationMarkerBitmap(context, markerStyle, showCone, locationFresh)
        hasCompassCone = showCone
        this.locationFresh = locationFresh
        centerBitmap()
    }

    private fun centerBitmap() {
        val markerBitmap = requireNotNull(bitmap)
        horizontalOffset = -markerBitmap.width / 2
        verticalOffset = -markerBitmap.height / 2
    }

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

    @Volatile private var orientationObservation: Pair<Long, PhoneMapOrientationMarker>? = null

    fun orientationDiagnosticSample(sessionId: Long): PhoneMapOrientationMarker? =
        orientationObservation
            ?.takeIf {
                it.first ==
                    sessionId
            }?.second

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
                val bitmapScale =
                    mapViewPosition?.let { phoneOfflineMarkerBitmapScale(zoomLevel, it.scaleFactor) } ?: 1f
                val pivotX = pixelX.toFloat()
                val pivotY = pixelY.toFloat()
                bitmapMatrix.reset()
                bitmapMatrix.translate(pivotX, pivotY)
                bitmapMatrix.scale(bitmapScale, bitmapScale)
                bitmapMatrix.translate(-markerBitmap.width / 2f, -markerBitmap.height / 2f)
                val effectiveRotation = phoneOfflineMarkerScreenRotationDegrees(heading, mapViewRotation.degrees)
                canvas.save()
                if (effectiveRotation != 0f) {
                    canvas.rotate(effectiveRotation, pivotX, pivotY)
                }
                canvas.drawBitmap(markerBitmap, bitmapMatrix)
                canvas.restore()
                bitmapDrawCount.incrementAndGet()
                lastDrawOutcome = "bitmap_drawn"
                if (PhoneDebugCapture.isActive()) {
                    PhoneDebugCapture.activeSessionId()?.let { sessionId ->
                        orientationObservation =
                            sessionId to
                            PhoneMapOrientationMarker(
                                screenDegrees =
                                    normalizePhoneHeadingDegrees(
                                        effectiveRotation + mapViewRotation.degrees,
                                    ),
                                observedVia = "mapsforge_draw",
                                animationEnabled = false,
                                sampleElapsedMs = SystemClock.elapsedRealtime(),
                            )
                    }
                }
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

private fun phoneLocationMarkerBitmap(
    context: Context,
    style: PhoneMapMarkerStyle,
    compassRenderable: Boolean,
    locationFresh: Boolean,
): AndroidBitmap {
    val drawable =
        requireNotNull(ContextCompat.getDrawable(context, phoneLocationMarkerDrawable(style, compassRenderable)))
            .mutate()
    if (!locationFresh) drawable.setTint(PHONE_LOCATION_MARKER_HISTORICAL_ARGB)
    return AndroidBitmap(
        drawable.toBitmap(
            width = (drawable.intrinsicWidth * PHONE_LOCATION_MARKER_SCALE).roundToInt().coerceAtLeast(1),
            height = (drawable.intrinsicHeight * PHONE_LOCATION_MARKER_SCALE).roundToInt().coerceAtLeast(1),
        ),
    )
}
