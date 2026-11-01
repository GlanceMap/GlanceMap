package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.geo.GeoPoint
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWind
import earth.worldwind.WorldWindow
import earth.worldwind.frame.Frame
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.gesture.GestureRecognizer
import earth.worldwind.gesture.GestureState
import earth.worldwind.globe.terrain.BasicTessellator
import earth.worldwind.layer.Layer
import earth.worldwind.layer.RenderableLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.BoundingBox
import java.io.Closeable
import java.util.Locale
import javax.microedition.khronos.opengles.GL10

private class PhoneWorldWindWindow(
    context: Context,
    private val diagnostics: PhoneWorldWindDiagnostics,
    private val onCameraChanged: (PhoneMapCameraSnapshot, Double) -> Unit,
    private val onUserPan: () -> Unit,
    private val onUserRotation: (Float) -> Unit,
    private val onGestureActive: (Boolean) -> Unit,
    private val onViewportReady: () -> Unit,
    private val onFirstGlFrame: (Long) -> Unit,
) : WorldWindow(context) {
    private val started = SystemClock.elapsedRealtime()
    private var firstFrame = false

    init {
        controller =
            object : BasicWorldWindowController(this) {
                override fun applyChanges() {
                    lookAt.tilt = phoneWorldWindClampedTilt(lookAt.tilt.inDegrees).degrees
                    super.applyChanges()
                    currentLookAt.copy(lookAt)
                    publishCamera(lookAt)
                }

                override fun handlePan(recognizer: GestureRecognizer) {
                    if (recognizer.state == GestureState.BEGAN) onUserPan()
                    super.handlePan(recognizer)
                }

                override fun handleRotate(recognizer: GestureRecognizer) {
                    super.handleRotate(recognizer)
                    if (recognizer.state == GestureState.CHANGED) onUserRotation(lookAt.heading.inDegrees.toFloat())
                }
            }
    }

    val currentLookAt = LookAt()
    val tracksLayer = RenderableLayer("Tracks")
    val positionLayer = RenderableLayer("Position")

    @Volatile private var touching = false
    private var anchoredGround = 0.0
    private var lastGestureDrawNanos = 0L

    private fun groundElevation(at: LookAt) = engine.globe.getElevation(at.position.latitude, at.position.longitude)

    fun applyCamera(lookAt: LookAt) {
        engine.cameraFromLookAt(lookAt)
        currentLookAt.copy(lookAt)
        anchoredGround = groundElevation(lookAt)
    }

    /** Coordinates are rounded: the capture needs to compare cameras, not record an exact position. */
    fun cameraSummary(): String {
        val position = currentLookAt.position
        return String.format(
            Locale.ROOT,
            "lat=%.3f lon=%.3f range=%.0f tilt=%.1f heading=%.1f mode=%s",
            position.latitude.inDegrees,
            position.longitude.inDegrees,
            currentLookAt.range,
            currentLookAt.tilt.inDegrees,
            currentLookAt.heading.inDegrees,
            currentLookAt.altitudeMode,
        )
    }

    override fun renderFrame(frame: Frame) {
        val started = System.nanoTime()
        super.renderFrame(frame)
        diagnostics.renderFrames.record(System.nanoTime() - started)
        if (touching || currentLookAt.altitudeMode != AltitudeMode.CLAMP_TO_GROUND) return
        val anchored = anchoredGround
        val ground = groundElevation(currentLookAt)
        if (phoneWorldWindGroundMoved(anchored, ground)) {
            applyCamera(currentLookAt)
            val altitude = engine.camera.position.altitude
            diagnostics.cameraReanchored("ground_before=$anchored ground_after=$ground camera_altitude=$altitude")
            WorldWind.requestRedraw()
        }
    }

    fun publishCamera(lookAt: LookAt = currentLookAt) {
        val latitude =
            lookAt.position.latitude.inDegrees
                .coerceIn(-90.0, 90.0)
        val longitude =
            lookAt.position.longitude.inDegrees
                .coerceIn(-180.0, 180.0)
        onCameraChanged(
            PhoneMapCameraSnapshot(
                latitude = latitude,
                longitude = longitude,
                zoom = phoneWorldWindZoomForRange(latitude, lookAt.range),
                bearingDegrees = normalizePhoneHeadingDegrees(lookAt.heading.inDegrees.toFloat()),
            ),
            lookAt.tilt.inDegrees,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touching = true
            onGestureActive(true)
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            onGestureActive(false)
        }
        return handled
    }

    override fun onSurfaceChanged(
        gl: GL10,
        width: Int,
        height: Int,
    ) {
        super.onSurfaceChanged(gl, width, height)
        if (width > 0 && height > 0) post { onViewportReady() }
    }

    override fun onDrawFrame(gl: GL10) {
        val started = System.nanoTime()
        super.onDrawFrame(gl)
        val finished = System.nanoTime()
        diagnostics.glFrames.record(finished - started)
        if (touching && lastGestureDrawNanos != 0L) diagnostics.gestureFrameGaps.record(started - lastGestureDrawNanos)
        lastGestureDrawNanos = if (touching) finished else 0L
        if (!firstFrame) {
            firstFrame = true
            val elapsed = SystemClock.elapsedRealtime() - started
            post { onFirstGlFrame(elapsed) }
        }
    }
}

internal data class PhoneWorldWindPreview(
    val map: PhoneOfflineMap?,
    val onlineProvider: OnlineMapProvider?,
    val onlineElevationEnabled: Boolean,
    val theme: PhoneOfflineThemeConfig,
    val demSource: PhoneOfflineDemSource,
    val terrainDataVersion: Long,
    val camera: PhoneMapCameraSnapshot,
    val tiltDegrees: Double = PHONE_WORLDWIND_DEFAULT_TILT_DEGREES,
)

internal data class PhoneWorldWindLiveState(
    val tracks: List<PhoneMapRouteSegment>,
    val trackColor: Int,
    val location: GeoPoint?,
    val locationFresh: Boolean,
    val mode: PhoneMapMode,
    val compassBearingDegrees: Float,
    val cameraCommand: PhoneMapCameraCommand?,
    val mapSettings: PhoneMapSettings,
)

internal data class PhoneWorldWindCallbacks(
    val onCameraChanged: (PhoneMapCameraSnapshot, Double) -> Unit,
    val onUserPan: () -> Unit,
    val onUserRotation: (Float) -> Unit,
    val onCommandHandled: (Long) -> Unit,
)

private class PhoneWorldWindResources(
    val imagery: Layer,
    val imageryOwner: Closeable?,
    val elevation: PhoneWorldWindElevation?,
    val elevationStatus: Int?,
    val online: PhoneOnlineElevationRepository?,
    val centerHasLocalElevation: Boolean,
    val diagnostics: PhoneWorldWindDiagnostics,
) : AutoCloseable {
    override fun close() {
        elevation?.close()
        imageryOwner?.close()
        diagnostics.close()
    }
}

@Composable
internal fun phoneWorldWindSurface(
    preview: PhoneWorldWindPreview,
    onlineElevation: PhoneOnlineElevationRepository,
    live: PhoneWorldWindLiveState,
    callbacks: PhoneWorldWindCallbacks,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val latestCallbacks by rememberUpdatedState(callbacks)
    val activity = remember(preview) { PhoneWorldWindActivity() }
    val processing by activity.isActive.collectAsState()
    var gestureActive by remember(preview) { mutableStateOf(false) }
    var viewportReady by remember(preview) { mutableStateOf(false) }
    var onlineUnavailable by remember(preview) { mutableStateOf(false) }
    var resources by remember(preview) { mutableStateOf<PhoneWorldWindResources?>(null) }
    var failure by remember(preview) { mutableStateOf(false) }
    var window by remember(preview) { mutableStateOf<PhoneWorldWindWindow?>(null) }
    var firstFrame by remember(preview) { mutableStateOf(false) }
    LaunchedEffect(preview) {
        val map = preview.map
        val provider = preview.onlineProvider
        if (map == null && (provider == null || provider.rasterTileUrlTemplate.isBlank())) {
            failure = true
            return@LaunchedEffect
        }
        val started = SystemClock.elapsedRealtime()
        var prepared: PhoneWorldWindResources? = null
        try {
            withContext(Dispatchers.IO) {
                prepared =
                    runCatching {
                        val diagnostics = PhoneWorldWindDiagnostics()
                        var offlineImagery: PhoneWorldWindMapsforgeLayer? = null
                        var onlineImagery: PhoneWorldWindOnlineImagery? = null
                        try {
                            offlineImagery =
                                map?.let {
                                    PhoneWorldWindMapsforgeLayer(
                                        context.applicationContext,
                                        it,
                                        preview.theme,
                                        diagnostics,
                                        activity,
                                    )
                                }
                            if (map == null) {
                                onlineImagery =
                                    PhoneWorldWindOnlineImagery(context.applicationContext, requireNotNull(provider), diagnostics)
                            }
                            val imagery = offlineImagery?.layer ?: requireNotNull(onlineImagery).layer
                            val bounds = offlineImagery?.bounds ?: BoundingBox(-85.0, -180.0, 85.0, 180.0)
                            val repository = PhoneElevationRepository(context.applicationContext)
                            val hasData = repository.hasData()
                            repository.onTileAccess = { hit, tile -> diagnostics.demAccess(hit, tile?.axisLength) }
                            repository.onTileFailure = diagnostics::demFailure
                            val centerSample = if (hasData) repository.elevationSampleAt(preview.camera.latitude, preview.camera.longitude) else null
                            val centerHasData = centerSample != null
                            val online = onlineElevation.takeIf { provider != null && preview.onlineElevationEnabled }
                            val resolution = phoneWorldWindDemResolutionSeconds(centerSample?.axisLen, online != null)
                            val elevation =
                                if (hasData || online != null) {
                                    PhoneWorldWindElevation(
                                        repository.takeIf { hasData },
                                        bounds,
                                        online,
                                        resolution,
                                        diagnostics,
                                        activity,
                                    )
                                } else {
                                    null
                                }
                            val status =
                                when {
                                    online != null -> null
                                    !hasData && provider != null -> R.string.phone_worldwind_online_dem_disabled
                                    !hasData -> R.string.phone_worldwind_missing_dem
                                    !centerHasData -> R.string.phone_worldwind_no_dem_here
                                    else -> null
                                }
                            val imageryOwner = offlineImagery ?: onlineImagery
                            PhoneWorldWindResources(
                                imagery,
                                imageryOwner,
                                elevation,
                                status,
                                online,
                                centerHasData,
                                diagnostics,
                            )
                        } catch (error: Exception) {
                            offlineImagery?.close()
                            onlineImagery?.close()
                            diagnostics.close()
                            throw error
                        }
                    }.onFailure { Log.e("PhoneWorldWind", "Unable to prepare terrain", it) }.getOrNull()
            }
            resources = prepared
            failure = prepared == null
            prepared?.diagnostics?.event("surface_preparation", "elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { prepared?.close() }
        }
    }
    DisposableEffect(window, lifecycle) {
        val current = window
        val captured = resources?.diagnostics
        if (current != null && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            current.onResume()
            captured?.event("surface_resume", current.cameraSummary())
        }
        onDispose {
            current?.onPause()
            if (current != null) captured?.event("surface_pause", current.cameraSummary())
        }
    }

    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(resources, resumed) {
        val prepared = resources ?: return@LaunchedEffect
        val online = prepared.online ?: return@LaunchedEffect
        if (resumed && !prepared.centerHasLocalElevation) {
            val center = PhoneMapCoordinate(preview.camera.latitude, preview.camera.longitude)
            do {
                onlineUnavailable = online.elevationAt(center) == null
                // A loaded tile with no data here will not improve, so only failed loads are retried.
                val retry = onlineUnavailable && online.isTileUnavailable(center)
                if (retry) delay(PHONE_ONLINE_DEM_FAILURE_RETRY_MS)
            } while (retry)
        }
    }
    phoneWorldWindOverlayEffects(window, live)
    phoneWorldWindCameraEffects(window, viewportReady, gestureActive, live) { commandId ->
        latestCallbacks.onCommandHandled(commandId)
    }
    Box(Modifier.fillMaxSize()) {
        val prepared = resources
        if (prepared != null) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                runCatching {
                    PhoneWorldWindWindow(
                        context = viewContext,
                        diagnostics = prepared.diagnostics,
                        onCameraChanged = { camera, tilt -> latestCallbacks.onCameraChanged(camera, tilt) },
                        onUserPan = { latestCallbacks.onUserPan() },
                        onUserRotation = { latestCallbacks.onUserRotation(it) },
                        onGestureActive = { gestureActive = it },
                        onViewportReady = { viewportReady = true },
                        onFirstGlFrame = { ms ->
                            firstFrame = true
                            prepared.diagnostics.event("first_gl_frame", "elapsed_ms=$ms")
                        },
                    ).also { view ->
                        view.engine.layers.clearLayers()
                        view.engine.globe.elevationModel
                            .clearCoverages()
                        (view.engine.tessellator as? BasicTessellator)?.detailControl = 12.0
                        view.engine.globe.verticalExaggeration = phoneWorldWindExaggeration(1.0)
                        phoneWorldWindEnableRelief(view, offline = preview.map != null)
                        view.engine.layers.addLayer(prepared.imagery)
                        prepared.elevation?.let {
                            view.engine.globe.elevationModel
                                .addCoverage(it.coverage)
                        }
                        view.engine.layers.addLayer(view.tracksLayer)
                        view.engine.layers.addLayer(view.positionLayer)
                        phoneWorldWindSetCamera(view, preview.camera, preview.tiltDegrees)
                        prepared.diagnostics.event(
                            "surface_start",
                            "imagery=${if (preview.map != null) "offline" else "online"} ${view.cameraSummary()}",
                        )
                        window = view
                        WorldWind.requestRedraw()
                    }
                }.getOrElse { error ->
                    Log.e("PhoneWorldWind", "Unable to initialize WorldWind", error)
                    failure = true
                    View(viewContext)
                }
            })
        }
        val initializing = prepared == null || !firstFrame
        if (!failure && (initializing || processing)) {
            phoneWorldWindActivityIndicator(Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 120.dp)) {
            if (failure) {
                val message =
                    if (preview.onlineProvider?.styleUrl != null) {
                        R.string.phone_worldwind_vector_unavailable
                    } else {
                        R.string.phone_worldwind_unavailable
                    }
                Text(stringResource(message), color = MaterialTheme.colorScheme.error)
            }
            prepared?.elevationStatus?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (onlineUnavailable) {
                Text(stringResource(R.string.phone_worldwind_online_dem_unavailable), color = MaterialTheme.colorScheme.error)
            }
        }
        preview.onlineProvider?.takeIf { prepared != null }?.let { provider ->
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 72.dp)
                        .background(Color.White.copy(alpha = 0.8f))
                        .padding(4.dp),
            ) {
                Text(
                    text =
                        android.text.Html
                            .fromHtml(provider.attribution, android.text.Html.FROM_HTML_MODE_LEGACY)
                            .toString(),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (prepared?.online != null) {
                    Text(
                        text = stringResource(R.string.map_tools_maps_terrain_attribution),
                        modifier = Modifier.clickable { uriHandler.openUri(PHONE_ONLINE_DEM_ATTRIBUTION_URL) },
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun phoneWorldWindActivityIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(36.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(32.dp), color = Color(0xFFFFB300), strokeWidth = 2.dp)
        Icon(
            Icons.Filled.Landscape,
            contentDescription = stringResource(R.string.phone_worldwind_loading),
            modifier = Modifier.size(18.dp),
            tint = Color(0xFFFFB300),
        )
    }
}

@Composable
private fun phoneWorldWindOverlayEffects(
    window: PhoneWorldWindWindow?,
    live: PhoneWorldWindLiveState,
) {
    LaunchedEffect(window, live.tracks, live.trackColor) {
        val view = window ?: return@LaunchedEffect
        view.tracksLayer.clearRenderables()
        view.tracksLayer.addAllRenderables(phoneWorldWindGpxLayer(live.tracks, live.trackColor, null))
        WorldWind.requestRedraw()
    }
    LaunchedEffect(window, live.location, live.locationFresh) {
        val view = window ?: return@LaunchedEffect
        view.positionLayer.clearRenderables()
        view.positionLayer.addAllRenderables(phoneWorldWindGpxLayer(emptyList(), live.trackColor, live.location, live.locationFresh))
        WorldWind.requestRedraw()
    }
}

@Composable
private fun phoneWorldWindCameraEffects(
    window: PhoneWorldWindWindow?,
    viewportReady: Boolean,
    gestureActive: Boolean,
    live: PhoneWorldWindLiveState,
    onCommandHandled: (Long) -> Unit,
) {
    LaunchedEffect(window, viewportReady, live.cameraCommand?.id) {
        val view = window ?: return@LaunchedEffect
        if (!viewportReady) return@LaunchedEffect
        val command = live.cameraCommand ?: return@LaunchedEffect
        val lookAt = phoneWorldWindCommandCamera(view.currentLookAt, command, live.mapSettings, view.width.toDouble())
        view.applyCamera(lookAt)
        view.publishCamera(lookAt)
        WorldWind.requestRedraw()
        onCommandHandled(command.id)
    }
    LaunchedEffect(window, viewportReady, live.location, live.mode, live.compassBearingDegrees, gestureActive) {
        val view = window ?: return@LaunchedEffect
        if (!viewportReady || gestureActive) return@LaunchedEffect
        val lookAt =
            phoneWorldWindFollowCamera(view.currentLookAt, live.mode, live.location, live.compassBearingDegrees)
                ?: return@LaunchedEffect
        view.applyCamera(lookAt)
        view.publishCamera(lookAt)
        WorldWind.requestRedraw()
    }
}

private fun phoneWorldWindSetCamera(
    window: PhoneWorldWindWindow,
    camera: PhoneMapCameraSnapshot,
    tilt: Double,
) {
    val lookAt =
        LookAt().set(
            camera.latitude.degrees,
            camera.longitude.degrees,
            0.0,
            AltitudeMode.CLAMP_TO_GROUND,
            phoneWorldWindRangeMeters(camera.latitude, camera.zoom),
            camera.bearingDegrees.toDouble().degrees,
            phoneWorldWindClampedTilt(tilt).degrees,
            0.0.degrees,
        )
    window.applyCamera(lookAt)
    WorldWind.requestRedraw()
}

private fun phoneWorldWindEnableRelief(
    window: WorldWindow,
    offline: Boolean,
) {
    window.engine.lightDirectionProvider = { context ->
        phoneWorldWindReliefDirection(
            context.camera.position.latitude.inRadians,
            context.camera.position.longitude.inRadians,
            context.lightDirection,
        )
    }
    window.engine.layers.addLayer(
        PhoneWorldWindReliefLayer(if (offline) 0.28f else 0.12f),
    )
}
