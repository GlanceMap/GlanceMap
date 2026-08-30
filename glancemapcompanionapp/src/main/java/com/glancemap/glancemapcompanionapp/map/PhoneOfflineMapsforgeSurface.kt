package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.util.LayerUtil
import org.mapsforge.map.view.InputListener
import java.util.concurrent.atomic.AtomicBoolean

private const val PHONE_OFFLINE_TILE_CACHE_CAPACITY = 64
private const val PHONE_OFFLINE_TILE_CACHE_ID = "phone-offline"
private const val PHONE_OFFLINE_MAP_TAG = "PhoneOfflineMap"

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
    key(state.map.rendererIdentity) {
        var view by remember { mutableStateOf<PhoneOfflineMapsforgeView?>(null) }

        AndroidView(
            factory = { context ->
                PhoneOfflineMapsforgeView(
                    context = context,
                    state = state,
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
                ).also { view = it }
            },
            update = { activeView ->
                activeView.applyTheme(state.themeConfig)
                activeView.applyCompassPresentation(state.compassPresentation)
                activeView.updateLocation(
                    location = state.location,
                    mapMode = state.mapMode,
                    hasLocationPermission = state.hasLocationPermission,
                )
                activeView.applyCameraCommand(state.cameraCommand)
                activeView.updateOverlays(gpxOverlays = state.gpxOverlays, pois = state.pois)
            },
            modifier = Modifier.fillMaxSize(),
        )

        DisposableEffect(view) {
            onDispose { view?.dispose() }
        }
    }
}

/** Phone-only Mapsforge holder for one base map plus renderer-adapted semantic overlays. */
@Suppress("TooManyFunctions") // Explicit lifecycle methods keep validated Mapsforge resources together.
private class PhoneOfflineMapsforgeView(
    context: Context,
    state: PhoneOfflineMapSurfaceState,
    private val callbacks: PhoneOfflineMapsforgeCallbacks,
) : FrameLayout(context) {
    private val rendererTrace = PhoneOfflineMapRendererTrace(state.map.file.name, state.themeConfig)
    private var mapView: MapView? = null
    private var tileCache: TileCache? = null
    private var mapFile: MapFile? = null
    private var mapBounds: BoundingBox? = null
    private var tileLayer: PhoneOfflineTileRendererLayer? = null
    private var appliedThemeConfig: PhoneOfflineThemeConfig? = null
    private var appliedCompassPresentation: PhoneMapCompassPresentation? = null
    private var lastHandledCameraCommandId: Long? = null
    private var gpxOverlays: List<PhoneMapGpxOverlay> = emptyList()
    private var pois: List<PhoneMapPoi> = emptyList()
    private var overlayLayers: PhoneOfflineMapsforgeOverlayLayers? = null
    private var locationMarker: PhoneOfflineLocationMarker? = null
    private var currentLocation: PhoneMapLocation? = null
    private var currentMapMode = state.mapMode
    private var hasLocationPermission = state.hasLocationPermission
    private var locationFollowUnavailableReported = false
    private var disposed = false
    private val runtimeLayoutChangeListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!disposed) publishRuntimeDiagnostics()
        }
    private val cameraObserver = Observer { publishCamera() }
    private val inputListener =
        object : InputListener {
            override fun onMoveEvent() {
                post { if (!disposed) callbacks.onUserPan() }
            }

            override fun onZoomEvent() = Unit
        }

    init {
        rendererTrace.complete(PhoneOfflineMapRendererStage.MAP_SELECTED)
        if (!isPhoneOfflineMapCandidate(state.map.file)) {
            reportRendererFailure(IllegalStateException("Selected map is unavailable."))
            postMapError(PhoneOfflineMapError.MISSING)
        } else {
            rendererTrace.begin(PhoneOfflineMapRendererStage.VIEW_CREATE)
            runCatching {
                rendererTrace.complete(PhoneOfflineMapRendererStage.VIEW_CREATE)
                rendererTrace.begin(PhoneOfflineMapRendererStage.GRAPHICS_FACTORY)
                AndroidGraphicFactory.createInstance(context.applicationContext)
                rendererTrace.complete(PhoneOfflineMapRendererStage.GRAPHICS_FACTORY)

                rendererTrace.begin(PhoneOfflineMapRendererStage.MAPFILE_OPEN)
                val openedMapFile = MapFile(state.map.file)
                mapFile = openedMapFile
                val bounds = openedMapFile.boundingBox()
                mapBounds = bounds
                val cameraInsideBounds = bounds.contains(state.initialCamera.latitude, state.initialCamera.longitude)
                rendererTrace.mapFileOpened(
                    boundsAvailable = true,
                    cameraInsideBounds = cameraInsideBounds,
                )
                rendererTrace.complete(PhoneOfflineMapRendererStage.MAPFILE_OPEN)

                rendererTrace.begin(PhoneOfflineMapRendererStage.MAPVIEW_CREATE)
                val mapView =
                    MapView(context).apply {
                        isClickable = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setBuiltInZoomControls(false)
                        mapScaleBar.isVisible = false
                        setZoomLevelMin(openedMapFile.mapFileInfo.zoomLevelMin)
                        setZoomLevelMax(openedMapFile.mapFileInfo.zoomLevelMax)
                        model.mapViewPosition.setMapPosition(
                            state.initialCamera.forMap(openedMapFile).toMapsforgeMapPosition(),
                            false,
                        )
                    }
                this.mapView = mapView
                mapView.addOnLayoutChangeListener(runtimeLayoutChangeListener)
                rendererTrace.complete(PhoneOfflineMapRendererStage.MAPVIEW_CREATE)

                rendererTrace.begin(PhoneOfflineMapRendererStage.TILE_CACHE_CREATE)
                val tileCache =
                    AndroidUtil.createTileCache(
                        context.applicationContext,
                        PHONE_OFFLINE_TILE_CACHE_ID,
                        PHONE_OFFLINE_TILE_CACHE_CAPACITY,
                        1f,
                        mapView.model.frameBufferModel.overdrawFactor,
                        false,
                    )
                this.tileCache = tileCache
                rendererTrace.tileCacheCreated()
                rendererTrace.complete(PhoneOfflineMapRendererStage.TILE_CACHE_CREATE)

                rendererTrace.begin(PhoneOfflineMapRendererStage.TILE_LAYER_CREATE)
                val tileLayer =
                    PhoneOfflineTileRendererLayer(
                        tileCache,
                        openedMapFile,
                        mapView.model.mapViewPosition,
                        AndroidGraphicFactory.INSTANCE,
                        onDrawObserved = { post { if (!disposed) publishRuntimeDiagnostics() } },
                        onFirstVisibleBaseTile = { post { if (!disposed) publishRuntimeDiagnostics() } },
                    )
                this.tileLayer = tileLayer
                rendererTrace.complete(PhoneOfflineMapRendererStage.TILE_LAYER_CREATE)
                applyTheme(state.themeConfig)
                applyCompassPresentation(state.compassPresentation)
                applyCameraCommand(state.cameraCommand)

                rendererTrace.begin(PhoneOfflineMapRendererStage.LAYER_ATTACH)
                mapView.layerManager.layers.add(tileLayer)
                rendererTrace.tileLayerAttached()
                rendererTrace.complete(PhoneOfflineMapRendererStage.LAYER_ATTACH)

                overlayLayers =
                    PhoneOfflineMapsforgeOverlayLayers(
                        mapView = mapView,
                        tileLayer = tileLayer,
                        onPoiSelected = { selected ->
                            post { if (!disposed) callbacks.onPoiSelected(selected) }
                        },
                    )
                mapView.model.mapViewPosition.addObserver(cameraObserver)
                mapView.addInputListener(inputListener)

                rendererTrace.begin(PhoneOfflineMapRendererStage.VIEW_ATTACH)
                addView(
                    mapView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                rendererTrace.viewAttached()
                rendererTrace.complete(PhoneOfflineMapRendererStage.VIEW_ATTACH)

                rendererTrace.begin(PhoneOfflineMapRendererStage.OVERLAYS_ATTACH)
                updateOverlays(gpxOverlays = state.gpxOverlays, pois = state.pois)
                updateLocation(
                    location = state.location,
                    mapMode = state.mapMode,
                    hasLocationPermission = state.hasLocationPermission,
                )
                post {
                    if (!disposed) {
                        appliedCompassPresentation = null
                        applyCompassPresentation(state.compassPresentation)
                    }
                }
                rendererTrace.complete(PhoneOfflineMapRendererStage.OVERLAYS_ATTACH)

                rendererTrace.begin(PhoneOfflineMapRendererStage.FIRST_CAMERA)
                rendererTrace.firstCameraPublished(publishCamera())
                rendererTrace.complete(PhoneOfflineMapRendererStage.FIRST_CAMERA)
                post { publishCamera() }
                val attempt = rendererTrace.ready()
                PhoneOfflineMapRendererDiagnostics.record(attempt)
                publishRuntimeDiagnostics()
                Log.i(PhoneOfflineMapRendererDiagnostics.TAG, attempt.toCaptureLine())
            }.onFailure { error ->
                reportRendererFailure(error)
                dispose()
                postMapError(PhoneOfflineMapError.INVALID)
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val activeMapView = mapView
        activeMapView?.model?.mapViewPosition?.removeObserver(cameraObserver)
        activeMapView?.removeInputListener(inputListener)
        activeMapView?.removeOnLayoutChangeListener(runtimeLayoutChangeListener)
        overlayLayers?.dispose()
        overlayLayers = null
        locationMarker?.let { marker ->
            activeMapView?.layerManager?.layers?.remove(marker)
            marker.onDestroy()
        }
        locationMarker = null
        tileLayer?.let { layer ->
            activeMapView?.layerManager?.layers?.remove(layer)
            runCatching { layer.onDestroy() }
        } ?: runCatching { mapFile?.close() }
        tileLayer = null
        mapFile = null
        mapBounds = null
        runCatching { tileCache?.destroy() }
        tileCache = null
        runCatching { activeMapView?.destroyAll() }
        mapView = null
        removeAllViews()
    }

    fun applyTheme(config: PhoneOfflineThemeConfig) {
        val resolved = PhoneOfflineThemeCatalog.resolve(config.themeId, config.styleId)
        if (resolved == appliedThemeConfig) return
        val layer = tileLayer ?: return
        tileCache?.purge()
        rendererTrace.begin(PhoneOfflineMapRendererStage.THEME_CREATE)
        val renderTheme =
            PhoneOfflineThemeCatalog.renderTheme(
                config = resolved,
                context = context,
                onResourceProviderFailure = rendererTrace::resourceProviderFailed,
            )
        rendererTrace.themeCreated(resolved, renderTheme.fallbackUsed)
        rendererTrace.complete(PhoneOfflineMapRendererStage.THEME_CREATE)
        rendererTrace.begin(PhoneOfflineMapRendererStage.THEME_APPLY)
        runCatching {
            layer.setXmlRenderTheme(renderTheme.theme)
        }.onFailure { error ->
            Log.e(
                PHONE_OFFLINE_MAP_TAG,
                "Unable to apply offline theme ${resolved.themeId}/${resolved.styleId}; using Mapsforge default.",
                error,
            )
            layer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
            rendererTrace.themeApplied(fallbackUsed = true)
        }.onSuccess {
            rendererTrace.themeApplied(fallbackUsed = renderTheme.fallbackUsed)
        }
        rendererTrace.complete(PhoneOfflineMapRendererStage.THEME_APPLY)
        mapView?.layerManager?.redrawLayers()
        appliedThemeConfig = resolved
    }

    fun applyCompassPresentation(presentation: PhoneMapCompassPresentation) {
        if (presentation == appliedCompassPresentation) return
        val activeMapView = mapView ?: return
        val rotationDegrees = mapsforgeRotationDegreesFor(presentation.mapBearingDegrees)
        val rotation =
            if (rotationDegrees == 0f) {
                Rotation.NULL_ROTATION
            } else {
                Rotation(rotationDegrees, activeMapView.mapViewCenterX, activeMapView.mapViewCenterY)
            }
        activeMapView.rotate(rotation)
        locationMarker?.heading = presentation.markerScreenRotationDegrees ?: 0f
        activeMapView.layerManager.redrawLayers()
        appliedCompassPresentation = presentation
    }

    fun updateLocation(
        location: PhoneMapLocation?,
        mapMode: PhoneMapMode,
        hasLocationPermission: Boolean,
    ) {
        val activeMapView = mapView ?: return
        currentLocation = location
        currentMapMode = mapMode
        this.hasLocationPermission = hasLocationPermission
        val currentLocation =
            location ?: run {
                locationMarker?.let { marker ->
                    activeMapView.layerManager.layers.remove(marker)
                    marker.onDestroy()
                    activeMapView.layerManager.redrawLayers()
                }
                locationMarker = null
                locationFollowUnavailableReported = false
                publishRuntimeDiagnostics()
                return
            }
        val latLong = LatLong(currentLocation.latitude, currentLocation.longitude)
        var markerChanged = false
        val marker =
            locationMarker ?: PhoneOfflineLocationMarker(latLong).also { created ->
                locationMarker = created
                activeMapView.layerManager.layers.add(created)
                markerChanged = true
            }
        if (marker.latLong != latLong) {
            marker.latLong = latLong
            markerChanged = true
        }
        marker.heading = appliedCompassPresentation?.markerScreenRotationDegrees ?: 0f
        val followDecision =
            phoneOfflineLocationFollowDecision(
                location = currentLocation,
                mapBounds = mapBounds,
                followMode = mapMode.follow,
            )
        if (followDecision.shouldCenterOnLocation && activeMapView.model.mapViewPosition.center != latLong) {
            activeMapView.setCenter(latLong)
        }
        if (followDecision.locationInsideMapBounds == false && mapMode.follow == PhoneMapFollowMode.FOLLOW_LOCATION) {
            reportLocationFollowUnavailableOnce()
        } else {
            locationFollowUnavailableReported = false
        }
        if (markerChanged) activeMapView.layerManager.redrawLayers()
        publishRuntimeDiagnostics()
    }

    fun applyCameraCommand(command: PhoneMapCameraCommand?) {
        if (command == null || command.id == lastHandledCameraCommandId) return
        val position = mapView?.model?.mapViewPosition ?: return
        when (command.zoomDelta) {
            1 -> position.zoomIn(true)
            -1 -> position.zoomOut(true)
        }
        lastHandledCameraCommandId = command.id
        post { if (!disposed) callbacks.onCameraCommandHandled(command.id) }
    }

    fun updateOverlays(
        gpxOverlays: List<PhoneMapGpxOverlay>,
        pois: List<PhoneMapPoi>,
    ) {
        if (disposed || (this.gpxOverlays == gpxOverlays && this.pois == pois)) return
        this.gpxOverlays = gpxOverlays
        this.pois = pois
        overlayLayers?.update(gpxOverlays = gpxOverlays, pois = pois)
    }

    private fun publishCamera(): Boolean {
        var published = false
        if (!disposed) {
            mapView?.model?.mapViewPosition?.mapPosition?.let { position ->
                runCatching {
                    PhoneMapCameraSnapshot(
                        latitude = position.latLong.latitude,
                        longitude = position.latLong.longitude,
                        zoom = position.zoomLevel.toDouble(),
                    )
                }.getOrNull()?.let { snapshot ->
                    published = true
                    post { if (!disposed) callbacks.onCameraChanged(snapshot) }
                }
                mapView?.phoneMapViewportOrNull()?.let { viewport ->
                    post { if (!disposed) callbacks.onViewportChanged(viewport) }
                }
            }
        }
        publishRuntimeDiagnostics()
        return published
    }

    private fun reportLocationFollowUnavailableOnce() {
        if (locationFollowUnavailableReported) return
        locationFollowUnavailableReported = true
        post { if (!disposed) callbacks.onLocationFollowUnavailable() }
    }

    private fun publishRuntimeDiagnostics() {
        val activeMapView = mapView ?: return
        val location = currentLocation
        PhoneOfflineMapRendererDiagnostics.recordRuntime(
            PhoneOfflineMapRuntimeDiagnostics(
                displayName = rendererTrace.mapDisplayName,
                mapViewAttached = activeMapView.isAttachedToWindow,
                mapViewWidth = activeMapView.width,
                mapViewHeight = activeMapView.height,
                drawObserved = tileLayer?.hasDrawObserved == true,
                firstVisibleBaseTileObserved = tileLayer?.hasFirstVisibleBaseTileObserved == true,
                zoom =
                    activeMapView.model.mapViewPosition.zoomLevel
                        .toInt(),
                cameraInsideMapBounds = activeMapView.currentCameraInside(mapBounds),
                locationPermissionGranted = hasLocationPermission,
                locationAvailable = location != null,
                locationAgeMillis = location?.ageMillis(android.os.SystemClock.elapsedRealtime()),
                locationAccuracyMeters = location?.accuracyMeters,
                locationInsideMapBounds =
                    location?.let { fix -> mapBounds?.contains(fix.latitude, fix.longitude) },
                followMode = currentMapMode.follow,
                orientation = currentMapMode.orientation,
                locationMarkerAttached =
                    locationMarker?.let { marker -> activeMapView.layerManager.layers.contains(marker) } == true,
            ),
        )
    }

    private fun postMapError(error: PhoneOfflineMapError) {
        post { callbacks.onMapError(error) }
    }

    private fun reportRendererFailure(error: Throwable) {
        val attempt = rendererTrace.failed(error)
        PhoneOfflineMapRendererDiagnostics.record(attempt)
        Log.e(PhoneOfflineMapRendererDiagnostics.TAG, attempt.toCaptureLine())
    }
}

/** Minimal phone adaptation of the Wear first-visible-tile seam, without its telemetry framework. */
private class PhoneOfflineTileRendererLayer(
    tileCache: TileCache,
    mapFile: MapFile,
    mapViewPosition: org.mapsforge.map.model.MapViewPosition,
    graphicFactory: org.mapsforge.core.graphics.GraphicFactory,
    private val onDrawObserved: () -> Unit,
    private val onFirstVisibleBaseTile: () -> Unit,
) : TileRendererLayer(
        tileCache,
        mapFile,
        mapViewPosition,
        false,
        true,
        false,
        graphicFactory,
        null,
    ) {
    private val drawObserved = AtomicBoolean(false)
    private val firstVisibleBaseTileObserved = AtomicBoolean(false)

    val hasDrawObserved: Boolean
        get() = drawObserved.get()

    val hasFirstVisibleBaseTileObserved: Boolean
        get() = firstVisibleBaseTileObserved.get()

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: org.mapsforge.core.graphics.Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        if (drawObserved.compareAndSet(false, true)) onDrawObserved()
        super.draw(boundingBox, zoomLevel, canvas, topLeftPoint, rotation)
        if (
            hasCachedVisibleBaseTile(boundingBox, zoomLevel) &&
            firstVisibleBaseTileObserved.compareAndSet(false, true)
        ) {
            onFirstVisibleBaseTile()
        }
    }

    private fun hasCachedVisibleBaseTile(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
    ): Boolean =
        runCatching {
            val tileSize = displayModel?.tileSize ?: return@runCatching false
            if (renderThemeFuture == null) return@runCatching false
            LayerUtil.getTiles(boundingBox, zoomLevel, tileSize).any { tile ->
                tileCache.containsKey(createJob(tile))
            }
        }.getOrDefault(false)
}

private fun MapView.currentCameraInside(bounds: BoundingBox?): Boolean? = bounds?.contains(model.mapViewPosition.center)

/** Keeps Mapsforge overlay layers independent from the base tile renderer and theme lifecycle. */
private class PhoneOfflineMapsforgeOverlayLayers(
    private val mapView: MapView,
    private val tileLayer: TileRendererLayer,
    private val onPoiSelected: (PhoneMapPoi) -> Unit,
) {
    private val gpxLayersById = mutableMapOf<String, MutableList<Polyline>>()
    private val poiMarkersById = mutableMapOf<String, PhoneOfflinePoiMarker>()

    fun update(
        gpxOverlays: List<PhoneMapGpxOverlay>,
        pois: List<PhoneMapPoi>,
    ) {
        val layers = mapView.layerManager.layers
        syncGpxLayers(layers, gpxOverlays)
        syncPoiMarkers(layers, pois)
        mapView.layerManager.redrawLayers()
    }

    fun dispose() {
        val layers = mapView.layerManager.layers
        gpxLayersById.values.flatten().forEach { layer ->
            layers.remove(layer)
            layer.latLongs.clear()
        }
        gpxLayersById.clear()
        poiMarkersById.values.forEach { marker -> removePoiMarker(layers, marker) }
        poiMarkersById.clear()
    }

    private fun syncGpxLayers(
        layers: org.mapsforge.map.layer.Layers,
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
            while (current.size < segments.size) {
                current += Polyline(createTrackPaint(), AndroidGraphicFactory.INSTANCE)
            }
            current.forEachIndexed { index, layer ->
                if (!layers.contains(layer)) {
                    layers.add(gpxLayerInsertionIndex(layers), layer)
                }
                val points = segments[index]
                if (layer.latLongs != points) {
                    layer.latLongs.clear()
                    layer.latLongs.addAll(points)
                }
            }
        }
    }

    private fun syncPoiMarkers(
        layers: org.mapsforge.map.layer.Layers,
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
        layers: org.mapsforge.map.layer.Layers,
        marker: PhoneOfflinePoiMarker,
    ) {
        layers.remove(marker)
        marker.onDestroy()
    }

    private fun gpxLayerInsertionIndex(layers: org.mapsforge.map.layer.Layers): Int {
        val tileIndex = layers.indexOf(tileLayer)
        return if (tileIndex >= 0) tileIndex + 1 else layers.size()
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
    val bitmap = Bitmap.createBitmap(POI_MARKER_SIZE_PX, POI_MARKER_SIZE_PX, Bitmap.Config.ARGB_8888)
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
        PoiType.PEAK,
        PoiType.VIEWPOINT,
        -> Color.rgb(121, 85, 72)
        PoiType.WATER -> Color.rgb(25, 118, 210)
        PoiType.HUT,
        PoiType.CAMP,
        -> Color.rgb(46, 125, 50)
        PoiType.FOOD,
        PoiType.SHOP,
        -> Color.rgb(239, 108, 0)
        PoiType.TOILET,
        PoiType.TRANSPORT,
        PoiType.PARKING,
        -> Color.rgb(97, 97, 97)
        PoiType.BIKE,
        PoiType.GENERIC,
        PoiType.CUSTOM,
        -> Color.rgb(123, 31, 162)
    }

private fun MapView.phoneMapViewportOrNull(): PhoneMapViewport? =
    mapsforgeViewportOrNull(
        bounds = runCatching { boundingBox }.getOrNull(),
        zoom = model.mapViewPosition.zoomLevel,
    )

private fun createTrackPaint(): org.mapsforge.core.graphics.Paint =
    AndroidGraphicFactory.INSTANCE
        .createPaint()
        .apply {
            setColor(Color.rgb(0, 102, 204))
            setStrokeWidth(GPX_TRACK_STROKE_WIDTH_PX)
            setStyle(org.mapsforge.core.graphics.Style.STROKE)
        }

private const val GPX_TRACK_STROKE_WIDTH_PX = 5f
private const val MINIMUM_RENDERABLE_SEGMENT_POINTS = 2
private const val POI_MARKER_RADIUS_PX = 8f
private const val POI_MARKER_SIZE_PX = 20
private const val POI_MARKER_STROKE_WIDTH_PX = 2f

private fun PhoneMapCameraSnapshot.toMapsforgeMapPosition(): MapPosition =
    MapPosition(
        LatLong(latitude, longitude),
        zoom.toInt().coerceIn(0, Byte.MAX_VALUE.toInt()).toByte(),
    )

private fun PhoneMapCameraSnapshot.forMap(mapFile: MapFile): PhoneMapCameraSnapshot {
    val bounds = mapFile.boundingBox()
    if (bounds.contains(latitude, longitude)) return this
    val start = mapFile.startPosition() ?: bounds.centerPoint
    return PhoneMapCameraSnapshot(
        latitude = start.latitude,
        longitude = start.longitude,
        zoom = mapFile.startZoomLevel()?.toDouble() ?: mapFile.mapFileInfo.zoomLevelMin.toDouble(),
    )
}
