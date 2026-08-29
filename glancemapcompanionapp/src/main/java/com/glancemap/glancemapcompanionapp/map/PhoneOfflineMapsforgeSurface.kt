package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
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
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

private const val PHONE_OFFLINE_TILE_CACHE_CAPACITY = 64
private const val PHONE_OFFLINE_TILE_CACHE_ID = "phone-offline"
private const val PHONE_OFFLINE_MAP_TAG = "PhoneOfflineMap"

internal data class PhoneOfflineMapsforgeCallbacks(
    val onCameraChanged: (PhoneMapCameraSnapshot) -> Unit,
    val onViewportChanged: (PhoneMapViewport) -> Unit,
    val onPoiSelected: (PhoneMapPoi) -> Unit,
    val onCameraCommandHandled: (Long) -> Unit,
    val onMapError: (PhoneOfflineMapError) -> Unit,
)

internal data class PhoneOfflineMapSurfaceState(
    val map: PhoneOfflineMap,
    val themeConfig: PhoneOfflineThemeConfig,
    val initialCamera: PhoneMapCameraSnapshot,
    val gpxOverlays: List<PhoneMapGpxOverlay>,
    val pois: List<PhoneMapPoi>,
    val mapMode: PhoneMapMode,
    val cameraCommand: PhoneMapCameraCommand?,
)

@Composable
internal fun offlineMapSurface(
    state: PhoneOfflineMapSurfaceState,
    callbacks: PhoneOfflineMapsforgeCallbacks,
) {
    val currentCallbacks by rememberUpdatedState(callbacks)
    var view by remember(state.map.file.absolutePath) { mutableStateOf<PhoneOfflineMapsforgeView?>(null) }

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
                        onCameraCommandHandled = { currentCallbacks.onCameraCommandHandled(it) },
                        onMapError = { currentCallbacks.onMapError(it) },
                    ),
            ).also { view = it }
        },
        update = { activeView ->
            activeView.applyTheme(state.themeConfig)
            activeView.applyMapMode(state.mapMode)
            activeView.applyCameraCommand(state.cameraCommand)
            activeView.updateOverlays(gpxOverlays = state.gpxOverlays, pois = state.pois)
        },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(view) {
        onDispose { view?.dispose() }
    }
}

/** Phone-only Mapsforge holder for one base map plus renderer-adapted semantic overlays. */
private class PhoneOfflineMapsforgeView(
    context: Context,
    state: PhoneOfflineMapSurfaceState,
    private val callbacks: PhoneOfflineMapsforgeCallbacks,
) : FrameLayout(context) {
    private var mapView: MapView? = null
    private var tileCache: TileCache? = null
    private var mapFile: MapFile? = null
    private var tileLayer: TileRendererLayer? = null
    private var appliedThemeConfig: PhoneOfflineThemeConfig? = null
    private var appliedMapMode: PhoneMapMode? = null
    private var lastHandledCameraCommandId: Long? = null
    private var gpxOverlays: List<PhoneMapGpxOverlay> = emptyList()
    private var pois: List<PhoneMapPoi> = emptyList()
    private var overlayLayers: PhoneOfflineMapsforgeOverlayLayers? = null
    private var disposed = false
    private val cameraObserver = Observer { publishCamera() }

    init {
        if (!isPhoneOfflineMapCandidate(state.map.file)) {
            postMapError(PhoneOfflineMapError.MISSING)
        } else {
            runCatching {
                AndroidGraphicFactory.createInstance(context.applicationContext)
                val openedMapFile = MapFile(state.map.file)
                mapFile = openedMapFile
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
                val tileLayer =
                    TileRendererLayer(
                        tileCache,
                        openedMapFile,
                        mapView.model.mapViewPosition,
                        AndroidGraphicFactory.INSTANCE,
                    )
                this.tileLayer = tileLayer
                applyTheme(state.themeConfig)
                applyMapMode(state.mapMode)
                applyCameraCommand(state.cameraCommand)
                mapView.layerManager.layers.add(tileLayer)
                overlayLayers =
                    PhoneOfflineMapsforgeOverlayLayers(
                        mapView = mapView,
                        tileLayer = tileLayer,
                        onPoiSelected = { selected ->
                            post { if (!disposed) callbacks.onPoiSelected(selected) }
                        },
                    )
                mapView.model.mapViewPosition.addObserver(cameraObserver)
                addView(
                    mapView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                updateOverlays(gpxOverlays = state.gpxOverlays, pois = state.pois)
                publishCamera()
                post { publishCamera() }
            }.onFailure { error ->
                Log.e(
                    PHONE_OFFLINE_MAP_TAG,
                    "Unable to initialize Mapsforge renderer for ${state.map.file.name}.",
                    error,
                )
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
        overlayLayers?.dispose()
        overlayLayers = null
        tileLayer?.let { layer ->
            activeMapView?.layerManager?.layers?.remove(layer)
            runCatching { layer.onDestroy() }
        } ?: runCatching { mapFile?.close() }
        tileLayer = null
        mapFile = null
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
        runCatching {
            layer.setXmlRenderTheme(PhoneOfflineThemeCatalog.renderTheme(resolved, context))
        }.onFailure { error ->
            Log.e(
                PHONE_OFFLINE_MAP_TAG,
                "Unable to apply offline theme ${resolved.themeId}/${resolved.styleId}; using Mapsforge default.",
                error,
            )
            layer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
        }
        mapView?.layerManager?.redrawLayers()
        appliedThemeConfig = resolved
    }

    fun applyMapMode(mapMode: PhoneMapMode) {
        if (mapMode == appliedMapMode) return
        // Heading data is not available yet, so both orientation states safely keep North up.
        mapView?.rotate(Rotation.NULL_ROTATION)
        appliedMapMode = mapMode
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

    private fun publishCamera() {
        if (!disposed) {
            mapView?.model?.mapViewPosition?.mapPosition?.let { position ->
                runCatching {
                    PhoneMapCameraSnapshot(
                        latitude = position.latLong.latitude,
                        longitude = position.latLong.longitude,
                        zoom = position.zoomLevel.toDouble(),
                    )
                }.getOrNull()?.let { snapshot ->
                    post { if (!disposed) callbacks.onCameraChanged(snapshot) }
                }
                mapView?.phoneMapViewportOrNull()?.let { viewport ->
                    post { if (!disposed) callbacks.onViewportChanged(viewport) }
                }
            }
        }
    }

    private fun postMapError(error: PhoneOfflineMapError) {
        post { callbacks.onMapError(error) }
    }
}

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
