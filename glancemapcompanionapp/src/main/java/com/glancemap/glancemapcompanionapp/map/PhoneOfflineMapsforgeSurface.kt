package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
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
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.view.InputListener
import java.util.concurrent.atomic.AtomicBoolean

private const val PHONE_OFFLINE_MAP_RENDER_APPLY_DELAY_MS = 16L

internal data class PhoneOfflineMapsforgeCallbacks(
    val onCameraChanged: (PhoneMapCameraSnapshot) -> Unit,
    val onViewportChanged: (PhoneMapViewport) -> Unit,
    val onPoiSelected: (PhoneMapPoi) -> Unit,
    val onUserPan: () -> Unit,
    val onCameraCommandHandled: (Long) -> Unit,
    val onMapError: (PhoneOfflineMapError) -> Unit,
    val onLocationFollowUnavailable: () -> Unit,
)

internal data class PhoneOfflineMapSurfaceState(
    val map: PhoneOfflineMap,
    val themeConfig: PhoneOfflineThemeConfig,
    val initialCamera: PhoneMapCameraSnapshot,
    val gpxOverlays: List<PhoneMapGpxOverlay>,
    val pois: List<PhoneMapPoi>,
    val mapMode: PhoneMapMode,
    val cameraCommand: PhoneMapCameraCommand?,
    val compassPresentation: PhoneMapCompassPresentation = phoneMapCompassPresentation(mapMode.orientation, null),
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
                        onUserPan = { currentCallbacks.onUserPan() },
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

    DisposableEffect(holder) {
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
    private val cameraObserver = Observer { publishCamera() }
    private val inputListener =
        object : InputListener {
            override fun onMoveEvent() {
                post { if (!disposed) callbacks.onUserPan() }
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
    private var lastHandledCameraCommandId: Long? = null
    private var locationMarker: PhoneOfflineLocationMarker? = null
    private var pendingLocationMarkerRemoval: PhoneOfflineLocationMarker? = null
    private var currentLocation: PhoneMapLocation? = null
    private var currentMapMode = PhoneMapMode()
    private var hasLocationPermission = false
    private var locationFollowUnavailableReported = false
    private var renderReadyReported = false
    private var disposed = false

    init {
        PhoneOfflineMapRendererDiagnostics.recordLifecycleEvent(
            event = "mapview_created",
            detail = "mapView=${System.identityHashCode(mapView)}",
        )
        mapView.addOnLayoutChangeListener(mapViewLayoutListener)
        mapView.addOnAttachStateChangeListener(mapViewAttachListener)
        mapView.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> onMapViewLifecycleChanged() }
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE,
                -> PhoneMapLayerMutationCoordinator.setGestureActive(mapView, true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> PhoneMapLayerMutationCoordinator.setGestureActive(mapView, false)
            }
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
        applyCompassPresentation(state.compassPresentation)
        updateOverlays(state.gpxOverlays, state.pois)
        updateLocation(state.location, state.mapMode, state.hasLocationPermission)
        applyCameraCommand(state.cameraCommand)
        publishCamera()
        publishRuntimeDiagnostics()
    }

    private fun applyCompassPresentation(presentation: PhoneMapCompassPresentation) {
        if (presentation == appliedCompassPresentation) return
        val degrees = mapsforgeRotationDegreesFor(presentation.mapBearingDegrees)
        val rotation =
            if (degrees == 0f) {
                Rotation.NULL_ROTATION
            } else {
                Rotation(degrees, mapView.mapViewCenterX, mapView.mapViewCenterY)
            }
        mapView.rotate(rotation)
        locationMarker?.heading = presentation.markerScreenRotationDegrees ?: 0f
        PhoneMapLayerMutationCoordinator.redrawLayersSafely(mapView)
        appliedCompassPresentation = presentation
    }

    private fun updateLocation(
        location: PhoneMapLocation?,
        mapMode: PhoneMapMode,
        hasLocationPermission: Boolean,
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
                locationMarker ?: PhoneOfflineLocationMarker(latLong).also { created ->
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
            updateLocation(state.location, state.mapMode, state.hasLocationPermission)
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

    private fun updateOverlays(
        gpxOverlays: List<PhoneMapGpxOverlay>,
        pois: List<PhoneMapPoi>,
    ) {
        overlayLayers.update(gpxOverlays, pois)
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
                frameBufferDrawingBitmapReady =
                    runCatching { frameBuffer?.drawingBitmap != null }
                        .getOrNull(),
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
            ),
        )
    }

    private fun currentReadiness(): PhoneMapViewRenderReadiness =
        PhoneMapViewRenderReadiness(
            attachedToWindow = mapView.isAttachedToWindow,
            width = mapView.width,
            height = mapView.height,
            hasWindowFocus = mapView.hasWindowFocus(),
        )

    private companion object {
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
        if (androidDrawObserved.compareAndSet(false, true)) onAndroidDrawObserved()
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
    private var appliedGpxOverlays: List<PhoneMapGpxOverlay> = emptyList()
    private var appliedPois: List<PhoneMapPoi> = emptyList()

    fun update(
        gpxOverlays: List<PhoneMapGpxOverlay>,
        pois: List<PhoneMapPoi>,
    ) {
        if (appliedGpxOverlays == gpxOverlays && appliedPois == pois) return
        appliedGpxOverlays = gpxOverlays
        appliedPois = pois
        PhoneMapLayerMutationCoordinator.mutateLayers(mapView, OVERLAY_MUTATION_KEY) { layers ->
            syncGpxLayers(layers, gpxOverlays)
            syncPoiMarkers(layers, pois)
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
        }
    }

    private fun syncGpxLayers(
        layers: Layers,
        gpxOverlays: List<PhoneMapGpxOverlay>,
    ) {
        val overlaysById = gpxOverlays.associateBy(PhoneMapGpxOverlay::id)
        (gpxLayersById.keys - overlaysById.keys).forEach { id ->
            gpxLayersById.remove(id)?.forEach { layer ->
                layers.remove(layer)
                layer.latLongs.clear()
            }
        }
        overlaysById.values.forEach { overlay ->
            val segments = overlay.segments.toMapsforgeSegments()
            val current = gpxLayersById.getOrPut(overlay.id) { mutableListOf() }
            while (current.size > segments.size) {
                val layer = current.removeAt(current.lastIndex)
                layers.remove(layer)
                layer.latLongs.clear()
            }
            while (current.size < segments.size) current += Polyline(createTrackPaint(), AndroidGraphicFactory.INSTANCE)
            current.forEachIndexed { index, layer ->
                if (!layers.contains(layer)) layers.add(gpxLayerInsertionIndex(layers), layer)
                val points = segments[index]
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
    ) {
        val poisById = pois.associateBy(PhoneMapPoi::id)
        (poiMarkersById.keys - poisById.keys).forEach { id ->
            poiMarkersById.remove(id)?.let { marker -> removePoiMarker(layers, marker) }
        }
        poisById.values.forEach { poi ->
            val existing = poiMarkersById[poi.id]
            if (existing?.poi == poi) return@forEach
            if (existing != null) removePoiMarker(layers, existing)
            poiMarkersById[poi.id] =
                PhoneOfflinePoiMarker(
                    poi = poi,
                    mapView = mapView,
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
    private val onPoiSelected: (PhoneMapPoi) -> Unit,
) : Marker(
        LatLong(poi.location.latitude, poi.location.longitude),
        poi.toMapsforgeMarkerBitmap(),
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

private fun PhoneMapPoi.toMapsforgeMarkerBitmap(): AndroidBitmap {
    val bitmap =
        Bitmap.createBitmap(POI_MARKER_SIZE_PX, POI_MARKER_SIZE_PX, Bitmap.Config.ARGB_8888)
    val center = POI_MARKER_SIZE_PX / 2f
    Canvas(bitmap).apply {
        drawCircle(
            center,
            center,
            POI_MARKER_RADIUS_PX,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = type.mapsforgeMarkerColor() },
        )
        drawCircle(
            center,
            center,
            POI_MARKER_RADIUS_PX,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = POI_MARKER_STROKE_WIDTH_PX
            },
        )
    }
    return AndroidBitmap(bitmap)
}

internal fun PoiType.mapsforgeMarkerColor(): Int =
    when (this) {
        PoiType.PEAK, PoiType.VIEWPOINT -> Color.rgb(121, 85, 72)
        PoiType.WATER -> Color.rgb(25, 118, 210)
        PoiType.HUT, PoiType.CAMP -> Color.rgb(46, 125, 50)
        PoiType.FOOD, PoiType.SHOP -> Color.rgb(239, 108, 0)
        PoiType.TOILET, PoiType.TRANSPORT, PoiType.PARKING -> Color.rgb(97, 97, 97)
        PoiType.BIKE, PoiType.GENERIC, PoiType.CUSTOM -> Color.rgb(123, 31, 162)
    }

private fun MapView.phoneMapViewportOrNull(): PhoneMapViewport? =
    mapsforgeViewportOrNull(
        bounds = runCatching { boundingBox }.getOrNull(),
        zoom = model.mapViewPosition.zoomLevel,
    )

private fun createTrackPaint(): org.mapsforge.core.graphics.Paint =
    AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(Color.rgb(0, 102, 204))
        setStrokeWidth(GPX_TRACK_STROKE_WIDTH_PX)
        setStyle(org.mapsforge.core.graphics.Style.STROKE)
    }

private fun MapPosition.toPhoneMapCameraSnapshotOrNull(): PhoneMapCameraSnapshot? =
    runCatching {
        PhoneMapCameraSnapshot(
            latitude = latLong.latitude,
            longitude = latLong.longitude,
            zoom = zoomLevel.toDouble(),
        )
    }.getOrNull()

private const val GPX_TRACK_STROKE_WIDTH_PX = 5f
private const val MINIMUM_RENDERABLE_SEGMENT_POINTS = 2
private const val POI_MARKER_RADIUS_PX = 8f
private const val POI_MARKER_SIZE_PX = 20
private const val POI_MARKER_STROKE_WIDTH_PX = 2f
