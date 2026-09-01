@file:Suppress("TooManyFunctions") // Mapsforge touch helpers share the stable map surface lifecycle.

package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.glancemap.trailcore.poi.PoiType
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layers
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.view.InputListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val PHONE_OFFLINE_MAP_RENDER_APPLY_DELAY_MS = 16L

internal data class PhoneOfflineMapsforgeCallbacks(
    val onCameraChanged: (PhoneMapCameraSnapshot) -> Unit,
    val onViewportChanged: (PhoneMapViewport) -> Unit,
    val onPoiSelected: (PhoneMapPoi) -> Unit,
    val onMapTap: (PhoneMapCoordinate) -> Unit,
    val onTwoFingerTap: (PhoneMapCoordinate, PhoneMapCoordinate) -> Unit,
    val onUserPan: (Float) -> Unit,
    val onUserRotation: (Float) -> Unit,
    val onCameraCommandHandled: (Long) -> Unit,
    val onMapError: (PhoneOfflineMapError) -> Unit,
    val onLocationFollowUnavailable: () -> Unit,
)

internal data class PhoneOfflineMapSurfaceState(
    val map: PhoneOfflineMap,
    val themeConfig: PhoneOfflineThemeConfig,
    val initialCamera: PhoneMapCameraSnapshot,
    val mapSettings: PhoneMapSettings = PhoneMapSettings(),
    val terrainDataVersion: Long = 0L,
    val hasTerrainData: Boolean = false,
    val gpxOverlays: List<PhoneMapGpxOverlay>,
    val gpxSettings: PhoneMapGpxSettings = PhoneMapGpxSettings(),
    val pois: List<PhoneMapPoi>,
    val poiSettings: PhoneMapPoiSettings = PhoneMapPoiSettings(),
    val mapMode: PhoneMapMode,
    val cameraCommand: PhoneMapCameraCommand?,
    val compassPresentation: PhoneMapCompassPresentation = phoneMapCompassPresentation(mapMode, null),
    val location: PhoneMapLocation? = null,
    val hasLocationPermission: Boolean = false,
)

@Composable
internal fun offlineMapSurface(
    state: PhoneOfflineMapSurfaceState,
    callbacks: PhoneOfflineMapsforgeCallbacks,
) {
    val currentCallbacks by rememberUpdatedState(callbacks)
    var holder by remember { mutableStateOf<PhoneOfflineMapsforgeView?>(null) }

    AndroidView(
        factory = { context ->
            PhoneOfflineMapsforgeView(
                context = context,
                callbacks =
                    PhoneOfflineMapsforgeCallbacks(
                        onCameraChanged = { currentCallbacks.onCameraChanged(it) },
                        onViewportChanged = { currentCallbacks.onViewportChanged(it) },
                        onPoiSelected = { currentCallbacks.onPoiSelected(it) },
                        onMapTap = { currentCallbacks.onMapTap(it) },
                        onTwoFingerTap = { first, second -> currentCallbacks.onTwoFingerTap(first, second) },
                        onUserPan = { currentCallbacks.onUserPan(it) },
                        onUserRotation = { currentCallbacks.onUserRotation(it) },
                        onCameraCommandHandled = { currentCallbacks.onCameraCommandHandled(it) },
                        onMapError = { currentCallbacks.onMapError(it) },
                        onLocationFollowUnavailable = { currentCallbacks.onLocationFollowUnavailable() },
                    ),
            ).also { created ->
                holder = created
                created.render(state)
            }
        },
        update = { activeHolder -> activeHolder.render(state) },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(Unit) {
        onDispose { holder?.dispose() }
    }
}

/** Persistent Android host for one Mapsforge MapView and its delayed renderer lifecycle. */
@Suppress("TooManyFunctions") // Explicit callbacks make Mapsforge ownership easy to audit.
private class PhoneOfflineMapsforgeView(
    context: Context,
    private val callbacks: PhoneOfflineMapsforgeCallbacks,
) : FrameLayout(context) {
    private val mapView =
        PhoneOfflineMapsforgeMapView(
            context = context,
            onAndroidDrawObserved = ::publishRuntimeDiagnostics,
        ).apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setBuiltInZoomControls(false)
            mapScaleBar.isVisible = false
        }
    private val twoFingerTapDetector =
        PhoneTwoFingerTapDetector(context) { x1, y1, x2, y2 ->
            runCatching {
                val projection = mapView.mapViewProjection
                val first = projection.fromPixels(x1.toDouble(), y1.toDouble())
                val second = projection.fromPixels(x2.toDouble(), y2.toDouble())
                post {
                    if (!disposed) {
                        callbacks.onTwoFingerTap(
                            PhoneMapCoordinate(first.latitude, first.longitude),
                            PhoneMapCoordinate(second.latitude, second.longitude),
                        )
                    }
                }
            }
        }
    private val workGate = PhoneMapsforgeRenderWorkGate()
    private val renderer =
        PhoneMapsforgeRenderer(
            context = context.applicationContext,
            mapView = mapView,
            onMapBoundsChanged = { bounds ->
                mapBounds = bounds
                post { if (!disposed) applyLocationFromLatestState() }
            },
            onRendererFailure = { error -> post { if (!disposed) callbacks.onMapError(error) } },
            onRuntimeChanged = ::publishRuntimeDiagnostics,
        )
    private val overlayLayers =
        PhoneOfflineMapsforgeOverlayLayers(
            mapView = mapView,
            baseLayer = renderer::currentBaseLayer,
            onPoiSelected = { selected -> post { if (!disposed) callbacks.onPoiSelected(selected) } },
        )
    private val rotationGestureTracker = PhoneMapsforgeRotationGestureTracker()
    private val cameraObserver =
        Observer {
            publishUserRotationIfNeeded()
            publishCamera()
        }
    private val inputListener =
        object : InputListener {
            override fun onMoveEvent() {
                post { if (!disposed) callbacks.onUserPan(currentMapBearing()) }
            }

            override fun onZoomEvent() = Unit
        }
    private val mapViewLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onMapViewLifecycleChanged() }
    private val mapViewAttachListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                    event = "mapview_attached",
                    detail = "mapView=${System.identityHashCode(mapView)}",
                )
                onMapViewLifecycleChanged()
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        }

    private var latestState: PhoneOfflineMapSurfaceState? = null
    private var mapBounds: BoundingBox? = null
    private var appliedCompassPresentation: PhoneMapCompassPresentation? = null
    private var appliedMapBearingDegrees: Float? = null
    private var redrawPosted = false
    private var lastHandledCameraCommandId: Long? = null
    private var locationMarker: PhoneOfflineLocationMarker? = null
    private var pendingLocationMarkerRemoval: PhoneOfflineLocationMarker? = null
    private var currentLocation: PhoneMapLocation? = null
    private var currentMapMode = PhoneMapMode()
    private var hasLocationPermission = false
    private var locationFollowUnavailableReported = false
    private var renderReadyReported = false
    private var applyingProgrammaticRotation = false
    private var disposed = false
    private var tapCandidate = false
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTimeMs = 0L
    private val tapSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    init {
        PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
            event = "mapview_created",
            detail = "mapView=${System.identityHashCode(mapView)}",
        )
        mapView.addOnLayoutChangeListener(mapViewLayoutListener)
        mapView.addOnAttachStateChangeListener(mapViewAttachListener)
        mapView.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> onMapViewLifecycleChanged() }
        mapView.touchGestureHandler.setRotationEnabled(true)
        mapView.setOnTouchListener { _, event ->
            observeMapTap(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE,
                -> PhoneMapLayerMutationCoordinator.setGestureActive(mapView, true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> PhoneMapLayerMutationCoordinator.setGestureActive(mapView, false)
            }
            event.toPhoneMapsforgeTouchAction()?.let { action ->
                rotationGestureTracker.onTouch(action, event.pointerCount)
            }
            twoFingerTapDetector.onTouchEvent(event)
            false
        }
        mapView.model.mapViewPosition.addObserver(cameraObserver)
        mapView.addInputListener(inputListener)
        addView(
            mapView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun render(state: PhoneOfflineMapSurfaceState) {
        if (disposed) return
        latestState = state
        workGate.requestWork()
        schedulePendingRendererWorkIfReady()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        mapView.model.mapViewPosition.removeObserver(cameraObserver)
        mapView.removeInputListener(inputListener)
        mapView.removeOnLayoutChangeListener(mapViewLayoutListener)
        mapView.removeOnAttachStateChangeListener(mapViewAttachListener)
        mapView.onFocusChangeListener = null
        rotationGestureTracker.reset()
        twoFingerTapDetector.reset()
        overlayLayers.dispose()
        disposeLocationMarkers()
        renderer.destroy()
        runCatching { mapView.destroyAll() }
        removeAllViews()
    }

    private fun onMapViewLifecycleChanged() {
        if (disposed) return
        val readiness = currentReadiness()
        if (readiness.isReady && !renderReadyReported) {
            renderReadyReported = true
            PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                event = "mapview_render_ready",
                detail =
                    "mapView=${System.identityHashCode(mapView)} " +
                        "size=${mapView.width}x${mapView.height}",
            )
        }
        schedulePendingRendererWorkIfReady()
        latestState?.let { state -> applyMapSettings(state.mapSettings, state.initialCamera) }
        publishRuntimeDiagnostics()
    }

    private fun schedulePendingRendererWorkIfReady() {
        val generation = workGate.scheduleIfReady(currentReadiness()) ?: return
        PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
            event = "renderer_work_scheduled",
            detail =
                "mapView=${System.identityHashCode(mapView)} generation=$generation " +
                    "delayMs=$PHONE_OFFLINE_MAP_RENDER_APPLY_DELAY_MS",
        )
        mapView.postDelayed(
            {
                if (disposed || !workGate.consumeIfCurrent(generation, currentReadiness())) return@postDelayed
                latestState?.let(::applyRendererState)
            },
            PHONE_OFFLINE_MAP_RENDER_APPLY_DELAY_MS,
        )
    }

    private fun applyRendererState(state: PhoneOfflineMapSurfaceState) {
        renderer.updateBaseLayer(state.map, state.themeConfig, state.initialCamera)
        renderer.updateTerrain(state.mapSettings, state.terrainDataVersion, state.hasTerrainData)
        applyMapSettings(state.mapSettings, state.initialCamera)
        applyCompassPresentation(state.compassPresentation)
        overlayLayers.update(state)
        updateLocation(state.location, state.mapMode, state.hasLocationPermission, state.mapSettings)
        applyCameraCommand(state.cameraCommand)
        publishCamera()
        publishRuntimeDiagnostics()
    }

    private fun applyMapSettings(
        settings: PhoneMapSettings,
        initialCamera: PhoneMapCameraSnapshot,
    ) {
        applyMapMarkerAnchor(settings.markerAnchor)
        applyMapZoomSettings(settings, initialCamera)
    }

    private fun applyMapMarkerAnchor(anchor: PhoneMapMarkerAnchor) {
        val heightPx = mapView.height
        if (heightPx <= 0) return
        val density = resources.displayMetrics.density
        val desiredCenterY =
            if (anchor == PhoneMapMarkerAnchor.LOWER) {
                (0.82f - 10f * density / heightPx).coerceIn(0.74f, 0.86f)
            } else {
                0.5f
            }
        if (abs(mapView.mapViewCenterY - desiredCenterY) > 0.001f) {
            mapView.setMapViewCenterY(desiredCenterY)
        }
    }

    private fun applyMapZoomSettings(
        settings: PhoneMapSettings,
        initialCamera: PhoneMapCameraSnapshot,
    ) {
        val baseZoomMin = renderer.mapZoomLevelMin?.toInt()
        val baseZoomMax = renderer.mapZoomLevelMax?.toInt()
        val viewportWidthPx = mapView.width.toDouble()
        if (baseZoomMin != null && baseZoomMax != null && viewportWidthPx > 0.0) {
            val center = mapView.model.mapViewPosition.center
            val settingsZoomMin =
                phoneMapZoomForScale(
                    latitudeDegrees = center.latitude,
                    scaleMeters = settings.zoomMinScaleMeters,
                    viewportWidthPx = viewportWidthPx,
                )?.roundToInt()
            val settingsZoomMax =
                phoneMapZoomForScale(
                    latitudeDegrees = center.latitude,
                    scaleMeters = settings.zoomMaxScaleMeters,
                    viewportWidthPx = viewportWidthPx,
                )?.roundToInt()
            val zoomMin = maxOf(baseZoomMin, settingsZoomMin ?: baseZoomMin)
            val zoomMax = minOf(baseZoomMax, settingsZoomMax ?: baseZoomMax)
            val mapViewPosition = mapView.model.mapViewPosition
            if (zoomMin <= zoomMax) {
                if (mapViewPosition.zoomLevelMin.toInt() != zoomMin) {
                    mapView.setZoomLevelMin(zoomMin.toByte())
                }
                if (mapViewPosition.zoomLevelMax.toInt() != zoomMax) {
                    mapView.setZoomLevelMax(zoomMax.toByte())
                }
            }
            if (initialCamera == defaultPhoneMapCamera) {
                phoneMapZoomForScale(
                    latitudeDegrees = center.latitude,
                    scaleMeters = settings.zoomDefaultScaleMeters,
                    viewportWidthPx = viewportWidthPx,
                )?.let { defaultZoom ->
                    mapView.model.mapViewPosition.setZoom(defaultZoom, false)
                }
            }
        }
    }

    private fun applyCompassPresentation(presentation: PhoneMapCompassPresentation) {
        if (presentation == appliedCompassPresentation) return
        val appliedBearing = appliedMapBearingDegrees
        if (appliedBearing == null || phoneMapBearingNeedsSync(appliedBearing, presentation.mapBearingDegrees)) {
            applyMapBearing(presentation.mapBearingDegrees)
            appliedMapBearingDegrees = normalizePhoneHeadingDegrees(presentation.mapBearingDegrees)
        }
        locationMarker?.heading = presentation.markerScreenRotationDegrees ?: 0f
        requestMapRedraw()
        appliedCompassPresentation = presentation
    }

    private fun requestMapRedraw() {
        if (disposed || redrawPosted) return
        redrawPosted = true
        mapView.postOnAnimation {
            redrawPosted = false
            if (!disposed) PhoneMapLayerMutationCoordinator.redrawLayersSafely(mapView)
        }
    }

    private fun applyMapBearing(mapBearingDegrees: Float) {
        val degrees = mapsforgeRotationDegreesFor(mapBearingDegrees)
        val rotation =
            if (degrees == 0f) {
                Rotation.NULL_ROTATION
            } else {
                Rotation(degrees, mapView.mapViewCenterX, mapView.mapViewCenterY)
            }
        applyingProgrammaticRotation = true
        try {
            mapView.rotate(rotation)
        } finally {
            applyingProgrammaticRotation = false
        }
    }

    private fun updateLocation(
        location: PhoneMapLocation?,
        mapMode: PhoneMapMode,
        hasLocationPermission: Boolean,
        mapSettings: PhoneMapSettings,
    ) {
        currentLocation = location
        currentMapMode = mapMode
        this.hasLocationPermission = hasLocationPermission
        val current = location
        if (current == null) {
            removeLocationMarker()
            locationFollowUnavailableReported = false
            publishRuntimeDiagnostics()
            return
        }
        val latLong = LatLong(current.latitude, current.longitude)
        val followDecision = phoneOfflineLocationFollowDecision(current, mapBounds, mapMode.follow)
        PhoneMapLayerMutationCoordinator.mutateLayers(mapView, LOCATION_MUTATION_KEY) { layers ->
            pendingLocationMarkerRemoval?.let { previous ->
                layers.remove(previous)
                previous.onDestroy()
                pendingLocationMarkerRemoval = null
            }
            val marker =
                if (locationMarker?.markerStyle != mapSettings.markerStyle) {
                    locationMarker?.let { previous ->
                        layers.remove(previous)
                        previous.onDestroy()
                    }
                    null
                } else {
                    locationMarker
                } ?: PhoneOfflineLocationMarker(latLong, mapSettings.markerStyle) { observedMarker ->
                    post {
                        if (!disposed) {
                            PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
                                event = "location_marker_draw_call_observed",
                                detail =
                                    "mapView=${System.identityHashCode(mapView)} calls=${observedMarker.drawCalls} " +
                                        "bitmap=${observedMarker.bitmapDrawObserved} " +
                                        "result=${observedMarker.lastDrawResult}",
                            )
                            publishRuntimeDiagnostics()
                        }
                    }
                }.also { created ->
                    locationMarker = created
                    layers.add(created)
                }
            if (marker.latLong != latLong) marker.latLong = latLong
            marker.heading = appliedCompassPresentation?.markerScreenRotationDegrees ?: 0f
        }
        if (followDecision.shouldCenterOnLocation && mapView.model.mapViewPosition.center != latLong) {
            mapView.setCenter(latLong)
        }
        if (followDecision.locationInsideMapBounds == false && mapMode.follow == PhoneMapFollowMode.FOLLOW_LOCATION) {
            reportLocationFollowUnavailableOnce()
        } else {
            locationFollowUnavailableReported = false
        }
        publishRuntimeDiagnostics()
    }

    private fun applyLocationFromLatestState() {
        latestState?.let { state ->
            updateLocation(state.location, state.mapMode, state.hasLocationPermission, state.mapSettings)
        }
    }

    private fun removeLocationMarker() {
        val marker = locationMarker ?: return
        locationMarker = null
        pendingLocationMarkerRemoval = marker
        val remove = { layers: Layers ->
            if (pendingLocationMarkerRemoval === marker) {
                layers.remove(marker)
                marker.onDestroy()
                pendingLocationMarkerRemoval = null
            }
        }
        PhoneMapLayerMutationCoordinator.mutateLayers(mapView, LOCATION_MUTATION_KEY, remove)
    }

    private fun disposeLocationMarkers() {
        val markers = listOfNotNull(locationMarker, pendingLocationMarkerRemoval).distinct()
        if (markers.isEmpty()) return
        locationMarker = null
        pendingLocationMarkerRemoval = null
        PhoneMapLayerMutationCoordinator.mutateLayersImmediately(mapView) { layers ->
            markers.forEach { marker ->
                layers.remove(marker)
                marker.onDestroy()
            }
        }
    }

    private fun applyCameraCommand(command: PhoneMapCameraCommand?) {
        if (command == null || command.id == lastHandledCameraCommandId) return
        when (command.zoomDelta) {
            1 -> mapView.model.mapViewPosition.zoomIn(true)
            -1 -> mapView.model.mapViewPosition.zoomOut(true)
        }
        lastHandledCameraCommandId = command.id
        post { if (!disposed) callbacks.onCameraCommandHandled(command.id) }
    }

    private fun publishCamera(): Boolean {
        val snapshot =
            if (disposed) {
                null
            } else {
                mapView.model.mapViewPosition.mapPosition
                    .toPhoneMapCameraSnapshotOrNull()
            }
        snapshot?.let { camera ->
            post { if (!disposed) callbacks.onCameraChanged(camera) }
            mapView.phoneMapViewportOrNull()?.let { viewport ->
                post { if (!disposed) callbacks.onViewportChanged(viewport) }
            }
        }
        return snapshot != null
    }

    private fun reportLocationFollowUnavailableOnce() {
        if (locationFollowUnavailableReported) return
        locationFollowUnavailableReported = true
        post { callbacks.onLocationFollowUnavailable() }
    }

    private fun publishRuntimeDiagnostics() {
        if (disposed) return
        val rendererSnapshot = renderer.runtimeSnapshot()
        val frameBuffer = runCatching { mapView.frameBuffer }.getOrNull()
        val dimensions = runCatching { frameBuffer?.dimension }.getOrNull()
        val zoom =
            mapView.model.mapViewPosition.zoomLevel
                .toInt()
        PhoneOfflineMapRendererDiagnostics.recordRuntime(
            PhoneOfflineMapRuntimeDiagnostics(
                displayName = rendererSnapshot.displayName ?: latestState?.map?.displayName ?: "unknown",
                rendererId = rendererSnapshot.rendererId,
                mapViewId = System.identityHashCode(mapView),
                layerId = rendererSnapshot.layerId,
                cacheId = rendererSnapshot.cacheId,
                mapViewAttached = mapView.isAttachedToWindow,
                mapViewHasWindowFocus = mapView.hasWindowFocus(),
                mapViewWidth = mapView.width,
                mapViewHeight = mapView.height,
                mapViewRenderReady = currentReadiness().isReady,
                androidMapViewDrawObserved = mapView.hasAndroidDrawObserved,
                tileLayerDrawObserved = rendererSnapshot.tileLayerDrawObserved,
                firstVisibleBaseTileObserved = rendererSnapshot.firstVisibleBaseTileObserved,
                layerCount = mapView.layerManager.layers.size(),
                tileLayerPresent = rendererSnapshot.layerPresentIn(mapView.layerManager.layers),
                tileLayerVisible = renderer.currentBaseLayer?.isVisible,
                frameBufferDimensionAvailable = dimensions != null,
                frameBufferWidth = dimensions?.width,
                frameBufferHeight = dimensions?.height,
                // Mapsforge waits for its renderer here; never query it from the UI thread.
                frameBufferDrawingBitmapReady = null,
                zoom = zoom,
                cameraInsideMapBounds = mapView.currentCameraInside(mapBounds),
                visibleTileCount = rendererSnapshot.coverage.visibleTileCount,
                drawableVisibleTileCount = rendererSnapshot.coverage.drawableVisibleTiles,
                parentFallbackTileCount = rendererSnapshot.coverage.parentFallbackTiles,
                pendingTileJobCount = rendererSnapshot.coverage.pendingJobCount,
                locationPermissionGranted = hasLocationPermission,
                locationAvailable = currentLocation != null,
                locationAgeMillis = currentLocation?.ageMillis(android.os.SystemClock.elapsedRealtime()),
                locationAccuracyMeters = currentLocation?.accuracyMeters,
                locationInsideMapBounds = currentLocation?.let { mapBounds?.contains(it.latitude, it.longitude) },
                followMode = currentMapMode.follow,
                orientation = currentMapMode.orientation,
                locationMarkerAttached = locationMarker?.let(mapView.layerManager.layers::contains) == true,
                locationMarkerVisible = locationMarker?.isVisible,
                locationMarkerDrawCalls = locationMarker?.drawCalls ?: 0,
                locationMarkerBitmapDrawObserved = locationMarker?.bitmapDrawObserved == true,
                locationMarkerLastDrawResult = locationMarker?.lastDrawResult,
            ),
        )
    }

    private fun currentMapBearing(): Float = mapsforgeMapBearingDegrees(mapView.mapRotation.degrees)

    private fun observeMapTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapCandidate = true
                tapDownX = event.x
                tapDownY = event.y
                tapDownTimeMs = event.eventTime
            }

            MotionEvent.ACTION_POINTER_DOWN -> tapCandidate = false
            MotionEvent.ACTION_MOVE -> {
                if (
                    event.pointerCount != 1 ||
                    hypot(event.x - tapDownX, event.y - tapDownY) > tapSlopPx
                ) {
                    tapCandidate = false
                }
            }

            MotionEvent.ACTION_UP -> {
                val isTap =
                    tapCandidate &&
                        event.pointerCount == 1 &&
                        event.eventTime - tapDownTimeMs <= MAX_TAP_DURATION_MS
                tapCandidate = false
                if (isTap) {
                    runCatching {
                        val point = mapView.mapViewProjection.fromPixels(event.x.toDouble(), event.y.toDouble())
                        callbacks.onMapTap(PhoneMapCoordinate(point.latitude, point.longitude))
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }
    }

    private fun publishUserRotationIfNeeded() {
        val bearing =
            rotationGestureTracker.observeBearing(
                bearingDegrees = currentMapBearing(),
                reportUserRotation = !applyingProgrammaticRotation,
            ) ?: return
        post { if (!disposed) callbacks.onUserRotation(bearing) }
    }

    private fun currentReadiness(): PhoneMapViewRenderReadiness =
        PhoneMapViewRenderReadiness(
            attachedToWindow = mapView.isAttachedToWindow,
            width = mapView.width,
            height = mapView.height,
            hasWindowFocus = mapView.hasWindowFocus(),
        )

    private companion object {
        const val MAX_TAP_DURATION_MS = 500L
        const val LOCATION_MUTATION_KEY = "phone_mapsforge_location"
    }
}

/** Captures Android traversal separately from Mapsforge base-layer drawing. */
private class PhoneOfflineMapsforgeMapView(
    context: Context,
    private val onAndroidDrawObserved: () -> Unit,
) : MapView(context) {
    private val androidDrawObserved = AtomicBoolean(false)

    val hasAndroidDrawObserved: Boolean
        get() = androidDrawObserved.get()

    override fun onDraw(canvas: Canvas) {
        if (androidDrawObserved.compareAndSet(false, true)) post(onAndroidDrawObserved)
        super.onDraw(canvas)
    }
}

private fun MapView.currentCameraInside(bounds: BoundingBox?): Boolean? = bounds?.contains(model.mapViewPosition.center)

/** Keeps semantic GPX and POI overlays above the persistent base renderer layer. */
private class PhoneOfflineMapsforgeOverlayLayers(
    private val mapView: MapView,
    private val baseLayer: () -> TileRendererLayer?,
    private val onPoiSelected: (PhoneMapPoi) -> Unit,
) {
    private val gpxLayersById = mutableMapOf<String, MutableList<Polyline>>()
    private val poiMarkersById = mutableMapOf<String, PhoneOfflinePoiMarker>()
    private val poiMarkerBitmapCache =
        mutableMapOf<PhoneMapPoiMarkerAppearanceKey, Map<PoiType, AndroidBitmap>>()
    private var locationAccuracyCircle: Circle? = null
    private var appliedGpxOverlays: List<PhoneMapGpxOverlay> = emptyList()
    private var appliedGpxSettings = PhoneMapGpxSettings()
    private var appliedPois: List<PhoneMapPoi> = emptyList()
    private var appliedPoiSettings = PhoneMapPoiSettings()
    private var appliedMapSettings = PhoneMapSettings()
    private var appliedLocation: PhoneMapLocation? = null

    fun update(state: PhoneOfflineMapSurfaceState) {
        val gpxOverlays = state.gpxOverlays
        val gpxSettings = state.gpxSettings
        val pois = state.pois
        val poiSettings = state.poiSettings
        val mapSettings = state.mapSettings
        val location = state.location
        val isUnchanged =
            listOf(
                appliedGpxOverlays == gpxOverlays,
                appliedGpxSettings == gpxSettings,
                appliedPois == pois,
                appliedPoiSettings == poiSettings,
                appliedMapSettings == mapSettings,
                appliedLocation == location,
            ).all { it }
        if (isUnchanged) {
            return
        }
        appliedGpxOverlays = gpxOverlays
        appliedGpxSettings = gpxSettings
        appliedPois = pois
        appliedPoiSettings = poiSettings
        appliedMapSettings = mapSettings
        appliedLocation = location
        PhoneMapLayerMutationCoordinator.mutateLayers(mapView, OVERLAY_MUTATION_KEY) { layers ->
            syncGpxLayers(layers, gpxOverlays, gpxSettings)
            syncPoiMarkers(layers, pois, poiSettings)
            syncLocationAccuracyCircle(layers, mapSettings, location)
        }
    }

    fun dispose() {
        PhoneMapLayerMutationCoordinator.mutateLayersImmediately(mapView) { layers ->
            gpxLayersById.values.flatten().forEach { layer ->
                layers.remove(layer)
                layer.latLongs.clear()
            }
            gpxLayersById.clear()
            poiMarkersById.values.forEach { marker -> removePoiMarker(layers, marker) }
            poiMarkersById.clear()
            locationAccuracyCircle?.let { circle -> layers.remove(circle) }
            locationAccuracyCircle = null
        }
    }

    private fun syncGpxLayers(
        layers: Layers,
        gpxOverlays: List<PhoneMapGpxOverlay>,
        gpxSettings: PhoneMapGpxSettings,
    ) {
        val overlaysById = gpxOverlays.associateBy(PhoneMapGpxOverlay::id)
        (gpxLayersById.keys - overlaysById.keys).forEach { id ->
            gpxLayersById.remove(id)?.forEach { layer ->
                layers.remove(layer)
                layer.latLongs.clear()
            }
        }
        overlaysById.values.forEach { overlay ->
            val segments = overlay.segments.toPhoneMapGpxRenderSegments(gpxSettings)
            val current = gpxLayersById.getOrPut(overlay.id) { mutableListOf() }
            while (current.size > segments.size) {
                val layer = current.removeAt(current.lastIndex)
                layers.remove(layer)
                layer.latLongs.clear()
            }
            while (current.size < segments.size) {
                current += Polyline(createTrackPaint(gpxSettings), AndroidGraphicFactory.INSTANCE)
            }
            current.forEachIndexed { index, layer ->
                if (!layers.contains(layer)) layers.add(gpxLayerInsertionIndex(layers), layer)
                val segment = segments[index]
                layer.setPaintStroke(createTrackPaint(gpxSettings, segment.colorArgb))
                val points = segment.points.map { point -> LatLong(point.latitude, point.longitude) }
                if (layer.latLongs != points) {
                    layer.latLongs.clear()
                    layer.latLongs.addAll(points)
                }
            }
        }
    }

    private fun syncPoiMarkers(
        layers: Layers,
        pois: List<PhoneMapPoi>,
        settings: PhoneMapPoiSettings,
    ) {
        val appearanceKey = settings.markerAppearanceKey
        val markerBitmapByType =
            poiMarkerBitmapCache.getOrPut(appearanceKey) {
                PoiType.entries.associateWith { type ->
                    AndroidBitmap(mapView.context.phoneMapPoiMarkerBitmap(type, settings))
                }
            }
        val poisById = pois.associateBy(PhoneMapPoi::id)
        (poiMarkersById.keys - poisById.keys).forEach { id ->
            poiMarkersById.remove(id)?.let { marker -> removePoiMarker(layers, marker) }
        }
        poisById.values.forEach { poi ->
            val existing = poiMarkersById[poi.id]
            if (existing?.poi == poi && existing.appearanceKey == appearanceKey) return@forEach
            if (existing != null) removePoiMarker(layers, existing)
            poiMarkersById[poi.id] =
                PhoneOfflinePoiMarker(
                    poi = poi,
                    mapView = mapView,
                    appearanceKey = appearanceKey,
                    bitmap = requireNotNull(markerBitmapByType[poi.type]),
                    onPoiSelected = onPoiSelected,
                ).also(layers::add)
        }
    }

    private fun removePoiMarker(
        layers: Layers,
        marker: PhoneOfflinePoiMarker,
    ) {
        layers.remove(marker)
        marker.onDestroy()
    }

    private fun syncLocationAccuracyCircle(
        layers: Layers,
        mapSettings: PhoneMapSettings,
        location: PhoneMapLocation?,
    ) {
        val accuracyMeters = location?.accuracyMeters?.takeIf { it.isFinite() && it > 0f }
        if (!mapSettings.gpsAccuracyCircleEnabled || accuracyMeters == null) {
            locationAccuracyCircle?.let { circle -> layers.remove(circle) }
            locationAccuracyCircle = null
            return
        }
        val latLong = LatLong(location.latitude, location.longitude)
        val circle =
            locationAccuracyCircle
                ?: Circle(
                    latLong,
                    accuracyMeters,
                    AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        setColor(Color.argb(42, 25, 118, 210))
                        setStyle(org.mapsforge.core.graphics.Style.FILL)
                    },
                    AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        setColor(Color.argb(160, 25, 118, 210))
                        setStrokeWidth(2f)
                        setStyle(org.mapsforge.core.graphics.Style.STROKE)
                    },
                ).also { created ->
                    locationAccuracyCircle = created
                    layers.add(created)
                }
        circle.setLatLong(latLong)
        circle.setRadius(accuracyMeters)
    }

    private fun gpxLayerInsertionIndex(layers: Layers): Int {
        val baseIndex = baseLayer()?.let(layers::indexOf) ?: -1
        return if (baseIndex >= 0) baseIndex + 1 else layers.size()
    }

    private companion object {
        const val OVERLAY_MUTATION_KEY = "phone_mapsforge_overlays"
    }
}

internal fun List<PhoneMapRouteSegment>.toMapsforgeSegments(): List<List<LatLong>> =
    map { segment -> segment.points.map { point -> LatLong(point.latitude, point.longitude) } }
        .filter { points -> points.size >= MINIMUM_RENDERABLE_SEGMENT_POINTS }

internal fun mapsforgeViewportOrNull(
    bounds: BoundingBox?,
    zoom: Byte,
): PhoneMapViewport? =
    runCatching {
        val activeBounds = bounds ?: return null
        PhoneMapViewport(
            minLat = activeBounds.minLatitude,
            maxLat = activeBounds.maxLatitude,
            minLon = activeBounds.minLongitude,
            maxLon = activeBounds.maxLongitude,
            zoom = zoom.toInt().toDouble(),
        )
    }.getOrNull()

private class PhoneOfflinePoiMarker(
    val poi: PhoneMapPoi,
    private val mapView: MapView,
    val appearanceKey: PhoneMapPoiMarkerAppearanceKey,
    bitmap: AndroidBitmap,
    private val onPoiSelected: (PhoneMapPoi) -> Unit,
) : Marker(
        LatLong(poi.location.latitude, poi.location.longitude),
        bitmap,
        0,
        0,
    ) {
    override fun onTap(
        tapLatLong: LatLong,
        tapXY: Point,
        viewXY: Point,
    ): Boolean {
        if (!contains(tapXY, viewXY, mapView)) return false
        onPoiSelected(poi)
        return true
    }
}

private fun MotionEvent.toPhoneMapsforgeTouchAction(): PhoneMapsforgeTouchAction? =
    when (actionMasked) {
        MotionEvent.ACTION_DOWN -> PhoneMapsforgeTouchAction.DOWN
        MotionEvent.ACTION_POINTER_DOWN -> PhoneMapsforgeTouchAction.POINTER_DOWN
        MotionEvent.ACTION_MOVE -> PhoneMapsforgeTouchAction.MOVE
        MotionEvent.ACTION_POINTER_UP -> PhoneMapsforgeTouchAction.POINTER_UP
        MotionEvent.ACTION_UP -> PhoneMapsforgeTouchAction.UP
        MotionEvent.ACTION_CANCEL -> PhoneMapsforgeTouchAction.CANCEL
        else -> null
    }

private fun MapView.phoneMapViewportOrNull(): PhoneMapViewport? =
    mapsforgeViewportOrNull(
        bounds = runCatching { boundingBox }.getOrNull(),
        zoom = model.mapViewPosition.zoomLevel,
    )

private fun createTrackPaint(
    settings: PhoneMapGpxSettings,
    colorArgb: Int = settings.trackColorArgb,
): org.mapsforge.core.graphics.Paint =
    AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(colorArgb.withAlphaPercent(settings.trackOpacityPercent))
        setStrokeWidth(settings.trackWidth)
        setStyle(org.mapsforge.core.graphics.Style.STROKE)
    }

private fun Int.withAlphaPercent(percent: Int): Int =
    Color.argb(
        (Color.alpha(this) * percent.coerceIn(0, 100)) / 100,
        Color.red(this),
        Color.green(this),
        Color.blue(this),
    )

private fun MapPosition.toPhoneMapCameraSnapshotOrNull(): PhoneMapCameraSnapshot? =
    runCatching {
        PhoneMapCameraSnapshot(
            latitude = latLong.latitude,
            longitude = latLong.longitude,
            zoom = zoomLevel.toDouble(),
            bearingDegrees = mapsforgeMapBearingDegrees(rotation.degrees),
        )
    }.getOrNull()

private const val MINIMUM_RENDERABLE_SEGMENT_POINTS = 2

internal fun mapsforgeMapBearingDegrees(rotationDegrees: Float): Float = normalizePhoneHeadingDegrees(-rotationDegrees)
