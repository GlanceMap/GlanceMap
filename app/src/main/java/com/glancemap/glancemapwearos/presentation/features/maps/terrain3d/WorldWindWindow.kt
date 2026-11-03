package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import com.glancemap.glancemapwearos.core.maps.sanitizeTerrain3dViewingAngle
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.frame.Frame
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.gesture.GestureRecognizer
import earth.worldwind.gesture.GestureState
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.RenderResourceCache
import org.mapsforge.core.model.LatLong
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

private const val MIB = 1024L * 1024L

internal fun worldWindCacheBudget(heapMax: Long): Long = (heapMax / 8).coerceAtMost(16 * MIB).coerceAtLeast(MIB)

internal fun worldWindInitialRange(): Double = INITIAL_TERRAIN_CAMERA_DISTANCE.toDouble()

internal fun worldWindViewingAngle(angle: Int): Double = sanitizeTerrain3dViewingAngle(angle).toDouble()

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
    val surfaceReadyAt = AtomicLong()
    val draws = AtomicLong()
    val idleDraws = AtomicLong()
    val redrawRequests = AtomicLong()
    val panCount = AtomicLong()
    val zoomCount = AtomicLong()
    val orbitCount = AtomicLong()
    val recenterCount = AtomicLong()
    private val lastInteractionMs = AtomicLong(SystemClock.elapsedRealtime())
    var orbitMode = false
    var pinchEnabled = true
    private var configuredViewingAngle: Int? = null
    private var overlayLayer: RenderableLayer? = null
    private var overlayValue: Pair<TerrainGpxOverlay, LatLong?>? = null
    val hasGpx: Boolean get() = overlayValue?.first?.tracks?.isNotEmpty() == true
    private var lastX = 0f
    private var lastY = 0f
    private var anchoredElevation = 0.0

    init {
        engine.camera.fieldOfView = TERRAIN_FIELD_OF_VIEW_DEGREES.toDouble().degrees
        controller =
            object : BasicWorldWindowController(this) {
                override fun applyChanges() {
                    super.applyChanges()
                    this@WatchWorldWindWindow.lookAt.copy(lookAt)
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
        val next = overlay to location
        if (overlayValue == next) return
        overlayLayer?.let(engine.layers::removeLayer)
        overlayLayer = worldWindGpxLayer(overlay, location).also(engine.layers::addLayer)
        overlayValue = next
        requestRedraw()
    }

    fun setViewingAngle(angle: Int) {
        if (configuredViewingAngle == angle) return
        configuredViewingAngle = angle
        val next = worldWindViewingAngle(angle)
        if (lookAt.tilt.inDegrees == next) return
        lookAt.tilt = next.degrees
        applyLookAt()
    }

    fun setBearing(degrees: Float) {
        if (!degrees.isFinite() || lookAt.heading.inDegrees == degrees.toDouble()) return
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
        val next = terrainZoomByStep(lookAt.range.toFloat(), step).toDouble()
        if (next == lookAt.range) return false
        zoomCount.incrementAndGet()
        lookAt.range = next
        applyLookAt()
        return true
    }

    private fun applyLookAt() {
        anchoredElevation = engine.globe.getElevation(lookAt.position.latitude, lookAt.position.longitude)
        engine.cameraFromLookAt(lookAt)
        requestRedraw()
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastInteractionMs.set(SystemClock.elapsedRealtime())
        if (!orbitMode) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                orbitCount.incrementAndGet()
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val heading = lookAt.heading.inDegrees + (event.x - lastX) * 0.5
                val tilt = (lookAt.tilt.inDegrees - (event.y - lastY) * 0.1).coerceIn(45.0, 70.0)
                lookAt.heading = heading.degrees
                lookAt.tilt = tilt.degrees
                lastX = event.x
                lastY = event.y
                applyLookAt()
            }
        }
        return true
    }
}
