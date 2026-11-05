package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.core.maps.sanitizeTerrain3dViewingAngle
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.frame.Frame
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.gesture.GestureRecognizer
import earth.worldwind.gesture.GestureState
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.render.Renderable
import org.mapsforge.core.model.LatLong
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min

private const val MIB = 1024L * 1024L

internal fun worldWindCacheBudget(heapMax: Long): Long = (heapMax / 8).coerceAtMost(16 * MIB).coerceAtLeast(MIB)

internal fun worldWindInitialRange(): Double = INITIAL_TERRAIN_CAMERA_DISTANCE.toDouble()

internal fun worldWindZoomByStep(
    range: Double,
    step: Int,
): Double =
    when {
        step > 0 -> (range / 1.4).coerceAtLeast(1.0)
        step < 0 -> (range * 1.4).coerceAtMost(Double.MAX_VALUE)
        else -> range
    }

internal fun worldWindViewingAngle(angle: Int): Double = sanitizeTerrain3dViewingAngle(angle).toDouble()

internal fun worldWindShouldApplyBearing(
    degrees: Float,
    previous: Float?,
    manualOrbit: Boolean,
): Boolean = !manualOrbit && degrees.isFinite() && previous != degrees

internal fun worldWindDisplayedTilt(
    preferred: Double,
    range: Double,
): Double {
    val progress = (ln(range / 50_000.0) / ln(2_000_000.0 / 50_000.0)).coerceIn(0.0, 1.0)
    return min(preferred.coerceIn(0.0, 85.0), 85.0 * (1.0 - progress))
}

internal fun worldWindApproximateZoom(
    latitude: Double,
    range: Double,
): Int =
    log2(40_075_016.686 * cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) * 1.4 / range.coerceAtLeast(1.0))
        .toInt()
        .coerceIn(1, 22)

/** A watch-owned view; provider and lifecycle ownership remain with the session. */
@Suppress("TooManyFunctions")
internal class WatchWorldWindWindow(
    context: Context,
    private val onCenterChanged: (LatLong) -> Unit,
    private val onPanStarted: () -> Unit,
) : WorldWindow(
        context,
        renderResourceCache = RenderResourceCache(context, worldWindCacheBudget(Runtime.getRuntime().maxMemory())),
    ) {
    val lookAt = LookAt()
    val globeBackground = BackgroundLayer().apply { isEnabled = false }
    val surfaceReadyAt = AtomicLong()
    val draws = AtomicLong()
    val idleDraws = AtomicLong()
    val redrawRequests = AtomicLong()
    val panCount = AtomicLong()
    val zoomCount = AtomicLong()
    val orbitCount = AtomicLong()
    val recenterCount = AtomicLong()
    val trackRebuildCount = AtomicLong()
    val markerUpdateCount = AtomicLong()
    private val lastInteractionMs = AtomicLong(SystemClock.elapsedRealtime())
    var longPressOrbit by mutableStateOf(false)
        private set
    var cameraRange by mutableDoubleStateOf(worldWindInitialRange())
        private set
    private var externalBearing: Float? = null
    private val longPressDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onLongPress(event: MotionEvent) {
                    if (orbitMode) return
                    longPressOrbit = true
                    lastX = event.x
                    lastY = event.y
                    orbitCount.incrementAndGet()
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    superTouch(cancel)
                    cancel.recycle()
                }
            },
        )
    var orbitMode = false
        set(value) {
            if (field && !value) externalBearing = null
            field = value
        }
    var pinchEnabled = true
    private var configuredViewingAngle: Int? = null
    private var preferredTilt = 60.0
    private var overlayLayer: RenderableLayer? = null
    private var overlayValue: TerrainGpxOverlay? = null
    private var locationValue: LatLong? = null
    private var locationMarker: Renderable? = null
    val hasGpx: Boolean get() = overlayValue?.tracks?.isNotEmpty() == true
    private var lastX = 0f
    private var lastY = 0f
    private var anchoredElevation = 0.0

    init {
        engine.camera.fieldOfView = TERRAIN_FIELD_OF_VIEW_DEGREES.toDouble().degrees
        controller =
            object : BasicWorldWindowController(this) {
                override fun applyChanges() {
                    placeCamera(lookAt)
                    requestRedraw()
                    this@WatchWorldWindWindow.lookAt.copy(lookAt)
                    cameraRange = lookAt.range
                    onCenterChanged(LatLong(lookAt.position.latitude.inDegrees, lookAt.position.longitude.inDegrees))
                }

                override fun handlePan(recognizer: GestureRecognizer) {
                    if (recognizer.state == GestureState.BEGAN) {
                        panCount.incrementAndGet()
                        onPanStarted()
                    }
                    super.handlePan(recognizer)
                }

                override fun handlePinch(recognizer: GestureRecognizer) {
                    if (pinchEnabled) {
                        if (recognizer.state == GestureState.BEGAN) zoomCount.incrementAndGet()
                        super.handlePinch(recognizer)
                    }
                }
            }
    }

    fun initialize(
        center: LatLong,
        angle: Int,
        bearing: Float,
    ) {
        configuredViewingAngle = angle
        preferredTilt = worldWindViewingAngle(angle)
        lookAt.set(
            center.latitude.degrees,
            center.longitude.degrees,
            0.0,
            AltitudeMode.CLAMP_TO_GROUND,
            worldWindInitialRange(),
            bearing.toDouble().degrees,
            worldWindViewingAngle(angle).degrees,
            0.0.degrees,
        )
        applyLookAt()
    }

    fun updateOverlay(
        overlay: TerrainGpxOverlay,
        location: LatLong?,
    ) {
        val tracksChanged = overlayValue != overlay
        if (!tracksChanged && locationValue == location) return
        if (tracksChanged) {
            trackRebuildCount.incrementAndGet()
            overlayLayer?.let(engine.layers::removeLayer)
            overlayLayer = worldWindGpxLayer(overlay, null).also(engine.layers::addLayer)
            overlayValue = overlay
            locationMarker = null
        }
        overlayLayer?.let { layer ->
            locationMarker = updateWorldWindLocation(layer, locationMarker, worldWindLocationMarker(location))
            markerUpdateCount.incrementAndGet()
        }
        locationValue = location
        requestRedraw()
    }

    fun setViewingAngle(angle: Int) {
        if (configuredViewingAngle == angle) return
        configuredViewingAngle = angle
        val next = worldWindViewingAngle(angle)
        if (preferredTilt == next) return
        preferredTilt = next
        applyLookAt()
    }

    fun setPresetViewingAngle(angle: Int) {
        if (!isTerrainAnglePreset(angle) || preferredTilt == angle.toDouble()) return
        preferredTilt = angle.toDouble()
        applyLookAt()
    }

    fun setBearing(degrees: Float) {
        if (!worldWindShouldApplyBearing(degrees, externalBearing, orbitMode || longPressOrbit)) return
        externalBearing = degrees
        if (lookAt.heading.inDegrees == degrees.toDouble()) return
        lookAt.heading = degrees.toDouble().degrees
        applyLookAt()
    }

    fun recenter(center: LatLong) {
        if (lookAt.position.latitude.inDegrees == center.latitude &&
            lookAt.position.longitude.inDegrees == center.longitude
        ) {
            return
        }
        recenterCount.incrementAndGet()
        lastInteractionMs.set(SystemClock.elapsedRealtime())
        lookAt.position.latitude = center.latitude.degrees
        lookAt.position.longitude = center.longitude.degrees
        onCenterChanged(center)
        applyLookAt()
    }

    fun zoomByStep(step: Int): Boolean {
        lastInteractionMs.set(SystemClock.elapsedRealtime())
        val next = worldWindZoomByStep(lookAt.range, step)
        if (next == lookAt.range) return false
        zoomCount.incrementAndGet()
        lookAt.range = next
        cameraRange = next
        applyLookAt()
        return true
    }

    private fun applyLookAt() {
        anchoredElevation = engine.globe.getElevation(lookAt.position.latitude, lookAt.position.longitude)
        placeCamera(lookAt)
        requestRedraw()
    }

    private fun placeCamera(cameraLookAt: LookAt) {
        globeBackground.isEnabled = cameraLookAt.range >= 50_000.0
        cameraLookAt.tilt = worldWindDisplayedTilt(preferredTilt, cameraLookAt.range).degrees
        val wanted = cameraLookAt.range
        engine.cameraFromLookAt(cameraLookAt)
        val remainder = wanted - cameraLookAt.range
        if (remainder > 0.0) {
            engine.camera.position.altitude += remainder
            cameraLookAt.range = wanted
        }
    }

    override fun renderFrame(frame: Frame) {
        super.renderFrame(frame)
        val elevation = engine.globe.getElevation(lookAt.position.latitude, lookAt.position.longitude)
        val idle = SystemClock.elapsedRealtime() - lastInteractionMs.get() > 2_000
        if (idle && abs(elevation - anchoredElevation) >= 5.0) applyLookAt()
    }

    override fun requestRedraw() {
        redrawRequests.incrementAndGet()
        super.requestRedraw()
    }

    override fun onSurfaceCreated(
        unused: GL10,
        config: EGLConfig,
    ) {
        super.onSurfaceCreated(unused, config)
        surfaceReadyAt.compareAndSet(0L, SystemClock.elapsedRealtime())
    }

    override fun onDrawFrame(unused: GL10) {
        draws.incrementAndGet()
        if (SystemClock.elapsedRealtime() - lastInteractionMs.get() > 2_000) idleDraws.incrementAndGet()
        super.onDrawFrame(unused)
    }

    private fun superTouch(event: MotionEvent): Boolean = super.onTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastInteractionMs.set(SystemClock.elapsedRealtime())
        if (!orbitMode && !longPressOrbit) longPressDetector.onTouchEvent(event)
        if (!orbitMode && !longPressOrbit) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                orbitCount.incrementAndGet()
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val heading = lookAt.heading.inDegrees + (event.x - lastX) * 0.5
                preferredTilt = (preferredTilt - (event.y - lastY) * 0.1).coerceIn(0.0, 85.0)
                lookAt.heading = heading.degrees
                lastX = event.x
                lastY = event.y
                applyLookAt()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            longPressOrbit = false
        }
        return true
    }
}
