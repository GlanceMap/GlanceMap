package com.glancemap.glancemapwearos.presentation.features.navigate

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.glancemap.glancemapwearos.data.repository.PoiType
import com.glancemap.glancemapwearos.data.repository.PoiViewport
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxInspectionUiState
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import com.glancemap.glancemapwearos.presentation.features.gpx.InspectionABUiState
import com.glancemap.glancemapwearos.presentation.features.gpx.InspectionAUiState
import com.glancemap.glancemapwearos.presentation.features.maps.GpxInspectionPopupA
import com.glancemap.glancemapwearos.presentation.features.maps.GpxInspectionPopupAB
import com.glancemap.glancemapwearos.presentation.features.maps.MapHolder
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import com.glancemap.glancemapwearos.presentation.features.maps.mutateLayers
import com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlaySource
import com.glancemap.glancemapwearos.presentation.features.poi.PoiViewModel
import com.glancemap.glancemapwearos.presentation.features.recording.RecordingTraceOverlayEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.model.common.Observer

private const val ELEVATION_TRACK_OUTLINE_ALPHA = 176
private const val ELEVATION_TRACK_OUTLINE_WIDTH_EXTRA_PX = 3f
private const val ROUTE_TOOL_PREVIEW_ALPHA = 228
private const val ROUTE_TOOL_CREATE_PREVIEW_ALPHA = 238
private const val ROUTE_TOOL_DRAFT_ALPHA = 170
private const val ROUTE_TOOL_PREVIEW_RED = 0
private const val ROUTE_TOOL_PREVIEW_GREEN = 217
private const val ROUTE_TOOL_PREVIEW_BLUE = 232

@Composable
@OptIn(FlowPreview::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun MapOverlays(
    mapHolder: MapHolder,
    activeGpxDetails: List<GpxTrackDetails>,
    routeToolPreviewPoints: List<LatLong>,
    recordingTraceSegments: List<List<LatLong>>,
    recordingTraceFollowsMarker: Boolean,
    routeToolCreatePreviewActive: Boolean,
    routeToolDraftPoints: List<LatLong>,
    poiViewModel: PoiViewModel,
    activePoiOverlaySources: List<PoiOverlaySource>,
    poiMarkerSizePx: Int,
    poiMarkerStyle: String,
    gpxTrackColor: Int,
    gpxTrackColorMode: String,
    gpxTrackWidth: Float,
    gpxTrackOpacityPercent: Int,
    gpxTrackDirectionArrowsEnabled: Boolean,
    compassRenderStateFlow: StateFlow<CompassRenderState>,
    navMode: NavMode,
    forceNorthUpInPanning: Boolean,
    showRealMarkerInCompassMode: Boolean,
    showCompassConeOverlay: Boolean,
    compassConeBaseSizePx: Int,
    compassQuality: CompassMarkerQuality,
    compassHeadingErrorDeg: Float?,
    gpsAccuracyCircleEnabled: Boolean,
    gpsFixAccuracyM: Float,
    gpsFixFresh: Boolean,
    gpsFixSpeedMps: Float,
    gpsFixBearingDeg: Float?,
    renderedHeadingDeg: Float,
    locationMarker: RotatableMarker?,
    navigationMarkerAnchorMode: String,
    inspectionUiState: GpxInspectionUiState?,
    selectedPointA: LatLong?,
    selectedPointB: LatLong?,
    onDismissInspection: () -> Unit,
    onStartSelectB: () -> Unit,
    isMetric: Boolean,
    onRenderedHeadingChanged: (Float) -> Unit,
    onRenderedMapRotationChanged: (Float) -> Unit,
    onPoiMarkersSnapshotChanged: (List<com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlayMarker>) -> Unit,
) {
    val mapView = mapHolder.mapView
    val gpsAccuracyCircleLayer =
        remember(mapView) {
            val fill =
                AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    setStyle(Style.FILL)
                    color = Color.argb(54, 66, 153, 245)
                }
            val stroke =
                AndroidGraphicFactory.INSTANCE.createPaint().apply {
                    setStyle(Style.STROKE)
                    color = Color.argb(176, 21, 101, 192)
                    strokeWidth = 2f
                }
            GpsAccuracyCircleLayer(
                fillPaint = fill,
                strokePaint = stroke,
            )
        }
    val compassConeLayer =
        remember(mapView) {
            findExistingCompassConeLayer(mapView) ?: CompassConeLayer()
        }
    val markerAHolder = remember(mapView) { arrayOfNulls<Marker>(1) }
    val markerBHolder = remember(mapView) { arrayOfNulls<Marker>(1) }
    val topOverlayCoordinator =
        remember(
            mapView,
            gpsAccuracyCircleLayer,
            compassConeLayer,
            markerAHolder,
            markerBHolder,
        ) {
            MapTopOverlayCoordinator(
                layers = mapView.layerManager.layers,
                accuracyCircleLayer = gpsAccuracyCircleLayer,
                coneLayer = compassConeLayer,
                markerAHolder = markerAHolder,
                markerBHolder = markerBHolder,
            )
        }
    val redrawSignals =
        remember(mapView) {
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
    val requestMapRedraw =
        remember(redrawSignals) {
            {
                redrawSignals.tryEmit(Unit)
                Unit
            }
        }

    LaunchedEffect(mapView, redrawSignals, navMode) {
        val frameBudgetMs =
            when (navMode) {
                NavMode.COMPASS_FOLLOW -> 40L
                NavMode.NORTH_UP_FOLLOW -> 40L
                NavMode.PANNING -> 50L
            }
        redrawSignals
            .sample(frameBudgetMs)
            .collect { mapView.requestLayerRedrawSafely() }
    }

    LaunchedEffect(locationMarker) {
        topOverlayCoordinator.updateLocationMarker(locationMarker)
    }

    NavigationOrientationEffect(
        isCompassMode = navMode == NavMode.COMPASS_FOLLOW,
        isAutoCentering = navMode != NavMode.PANNING,
        forceNorthUpInPanning = forceNorthUpInPanning,
        renderStateFlow = compassRenderStateFlow,
        mapView = mapView,
        showRealMarkerInCompassMode = showRealMarkerInCompassMode,
        locationMarker = locationMarker,
        navigationMarkerAnchorMode = navigationMarkerAnchorMode,
        onRenderedHeadingChanged = onRenderedHeadingChanged,
        onRenderedMapRotationChanged = onRenderedMapRotationChanged,
        requestMapRedraw = requestMapRedraw,
    )

    GpsAccuracyCircleLayerEffect(
        mapView = mapView,
        gpsAccuracyCircleEnabled = gpsAccuracyCircleEnabled,
        gpsFixAccuracyM = gpsFixAccuracyM,
        gpsFixFresh = gpsFixFresh,
        locationMarker = locationMarker,
        accuracyCircleLayer = gpsAccuracyCircleLayer,
        topOverlayCoordinator = topOverlayCoordinator,
        requestMapRedraw = requestMapRedraw,
    )

    CompassConeLayerEffect(
        mapView = mapView,
        navMode = navMode,
        showCompassConeOverlay = showCompassConeOverlay,
        compassConeBaseSizePx = compassConeBaseSizePx,
        compassQuality = compassQuality,
        compassHeadingErrorDeg = compassHeadingErrorDeg,
        compassRenderStateFlow = compassRenderStateFlow,
        gpsFixAccuracyM = gpsFixAccuracyM,
        gpsFixFresh = gpsFixFresh,
        gpsFixSpeedMps = gpsFixSpeedMps,
        gpsFixBearingDeg = gpsFixBearingDeg,
        renderedHeadingDeg = renderedHeadingDeg,
        locationMarker = locationMarker,
        topOverlayCoordinator = topOverlayCoordinator,
        coneLayer = compassConeLayer,
        requestMapRedraw = requestMapRedraw,
    )

    PoiOverlayEffect(
        mapView = mapView,
        poiViewModel = poiViewModel,
        activePoiOverlaySources = activePoiOverlaySources,
        poiMarkerSizePx = poiMarkerSizePx,
        poiMarkerStyle = poiMarkerStyle,
        requestMapRedraw = requestMapRedraw,
        onPoiMarkersSnapshotChanged = onPoiMarkersSnapshotChanged,
        locationMarker = locationMarker,
        topOverlayCoordinator = topOverlayCoordinator,
    )

    GpxAndInspectionOverlayEffect(
        mapView = mapView,
        activeGpxDetails = activeGpxDetails,
        routeToolPreviewPoints = routeToolPreviewPoints,
        routeToolCreatePreviewActive = routeToolCreatePreviewActive,
        routeToolDraftPoints = routeToolDraftPoints,
        gpxTrackColor = gpxTrackColor,
        gpxTrackColorMode = gpxTrackColorMode,
        gpxTrackWidth = gpxTrackWidth,
        gpxTrackOpacityPercent = gpxTrackOpacityPercent,
        gpxTrackDirectionArrowsEnabled = gpxTrackDirectionArrowsEnabled,
        locationMarker = locationMarker,
        selectedPointA = selectedPointA,
        selectedPointB = selectedPointB,
        markerAHolder = markerAHolder,
        markerBHolder = markerBHolder,
        topOverlayCoordinator = topOverlayCoordinator,
        requestMapRedraw = requestMapRedraw,
    )

    RecordingTraceOverlayEffect(
        mapView = mapView,
        segments = recordingTraceSegments,
        followLocationMarker = recordingTraceFollowsMarker,
        locationMarker = locationMarker,
        topOverlayCoordinator = topOverlayCoordinator,
    )

    inspectionUiState?.let { ui ->
        when (ui) {
            is InspectionAUiState ->
                GpxInspectionPopupA(
                    state = ui,
                    onDismiss = onDismissInspection,
                    onSelectB = onStartSelectB,
                    isMetric = isMetric,
                )

            is InspectionABUiState ->
                GpxInspectionPopupAB(
                    state = ui,
                    onDismiss = onDismissInspection,
                    isMetric = isMetric,
                )
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun GpsAccuracyCircleLayerEffect(
    mapView: MapView,
    gpsAccuracyCircleEnabled: Boolean,
    gpsFixAccuracyM: Float,
    gpsFixFresh: Boolean,
    locationMarker: RotatableMarker?,
    accuracyCircleLayer: GpsAccuracyCircleLayer,
    topOverlayCoordinator: MapTopOverlayCoordinator,
    requestMapRedraw: () -> Unit,
) {
    val clampedAccuracyMeters = sanitizeGpsAccuracyMeters(gpsFixAccuracyM)
    val shouldShow =
        gpsAccuracyCircleEnabled &&
            gpsFixFresh &&
            clampedAccuracyMeters != null &&
            locationMarker != null

    LaunchedEffect(
        mapView,
        gpsAccuracyCircleEnabled,
        gpsFixAccuracyM,
        gpsFixFresh,
        locationMarker,
    ) {
        mapView.mutateLayers { layers ->
            val hasLayer = layers.contains(accuracyCircleLayer)
            if (!hasLayer) {
                layers.add(accuracyCircleLayer)
            }
            accuracyCircleLayer.anchorMarker = locationMarker
            clampedAccuracyMeters?.let { safeRadius ->
                accuracyCircleLayer.radiusMeters = safeRadius
            }
            accuracyCircleLayer.isVisible = shouldShow
            val reordered = topOverlayCoordinator.sync(layers)
            if (!hasLayer || reordered) {
                requestMapRedraw()
            } else {
                mapView.requestLayerRedrawSafely()
            }
        }
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.mutateLayers { layers ->
                layers.remove(accuracyCircleLayer)
                mapView.requestLayerRedrawSafely()
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
private fun CompassConeLayerEffect(
    mapView: MapView,
    navMode: NavMode,
    showCompassConeOverlay: Boolean,
    compassConeBaseSizePx: Int,
    compassQuality: CompassMarkerQuality,
    compassHeadingErrorDeg: Float?,
    compassRenderStateFlow: StateFlow<CompassRenderState>,
    gpsFixAccuracyM: Float,
    gpsFixFresh: Boolean,
    gpsFixSpeedMps: Float,
    gpsFixBearingDeg: Float?,
    renderedHeadingDeg: Float,
    locationMarker: RotatableMarker?,
    topOverlayCoordinator: MapTopOverlayCoordinator,
    coneLayer: CompassConeLayer,
    requestMapRedraw: () -> Unit,
) {
    val coneTelemetryLogger = remember { ConeTelemetryLogger() }
    val shouldShow =
        showCompassConeOverlay &&
            locationMarker != null &&
            (navMode == NavMode.COMPASS_FOLLOW || navMode == NavMode.NORTH_UP_FOLLOW)
    val coneHeadingDeg =
        when (navMode) {
            NavMode.COMPASS_FOLLOW -> 0f
            NavMode.NORTH_UP_FOLLOW -> renderedHeadingDeg
            NavMode.PANNING -> 0f
        }

    SideEffect {
        coneTelemetryLogger.log(
            ConeTelemetryDecision(
                navMode = navMode,
                overlayEnabled = showCompassConeOverlay,
                shouldShow = shouldShow,
                compass =
                    ConeTelemetryCompass(
                        quality = compassQuality,
                        headingErrorDeg = compassHeadingErrorDeg,
                        renderState = compassRenderStateFlow.value,
                        renderedHeadingDeg = renderedHeadingDeg,
                    ),
                gps =
                    ConeTelemetryGps(
                        accuracyM = gpsFixAccuracyM,
                        fresh = gpsFixFresh,
                        speedMps = gpsFixSpeedMps,
                        bearingDeg = gpsFixBearingDeg,
                    ),
                marker =
                    ConeTelemetryMarker(
                        present = locationMarker != null,
                        headingDeg = locationMarker?.heading,
                    ),
            ),
        )
    }

    LaunchedEffect(
        mapView,
        shouldShow,
        compassConeBaseSizePx,
        compassQuality,
        compassHeadingErrorDeg,
        coneHeadingDeg,
        locationMarker,
    ) {
        mapView.mutateLayers { layers ->
            val hasLayer = layers.contains(coneLayer)
            if (!hasLayer) {
                layers.add(coneLayer)
            }
            coneLayer.anchorMarker = locationMarker
            coneLayer.baseMarkerSizePx = compassConeBaseSizePx
            coneLayer.quality = compassQuality
            coneLayer.headingErrorDeg = compassHeadingErrorDeg
            coneLayer.headingDeg = coneHeadingDeg
            coneLayer.isVisible = shouldShow
            topOverlayCoordinator.sync(layers)
            requestMapRedraw()
        }
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.mutateLayers {
                coneLayer.anchorMarker = null
                coneLayer.isVisible = false
                requestMapRedraw()
            }
        }
    }
}

private fun findExistingCompassConeLayer(mapView: MapView): CompassConeLayer? =
    mapView.layerManager.layers
        .firstOrNull { it is CompassConeLayer } as? CompassConeLayer

private fun effectivePoiMarkerSizePx(
    baseSizePx: Int,
    zoomLevel: Int,
): Int =
    baseSizePx +
        when {
            zoomLevel >= 17 -> 8
            zoomLevel >= 15 -> 4
            else -> 0
        }

@Composable
@OptIn(FlowPreview::class)
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")
private fun PoiOverlayEffect(
    mapView: MapView,
    poiViewModel: PoiViewModel,
    activePoiOverlaySources: List<PoiOverlaySource>,
    poiMarkerSizePx: Int,
    poiMarkerStyle: String,
    requestMapRedraw: () -> Unit,
    onPoiMarkersSnapshotChanged: (List<com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlayMarker>) -> Unit,
    locationMarker: RotatableMarker?,
    topOverlayCoordinator: MapTopOverlayCoordinator,
) {
    val markersByKey = remember(mapView) { mutableMapOf<String, PoiMarkerEntry>() }
    val viewportQueryCache = remember(mapView) { PoiOverlayViewportQueryCache() }
    val markerBitmapCache =
        remember(mapView, poiMarkerStyle) {
            mutableMapOf<Pair<Int, String>, Map<PoiType, AndroidBitmap>>()
        }
    val latestSources = rememberUpdatedState(activePoiOverlaySources)
    val querySignals =
        remember(mapView) {
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
    val requestQuery =
        remember(querySignals) {
            {
                querySignals.tryEmit(Unit)
                Unit
            }
        }

    fun clearAllMarkers() {
        mapView.mutateLayers { layers ->
            if (markersByKey.isEmpty()) return@mutateLayers
            markersByKey.values.forEach { entry -> layers.remove(entry.marker) }
            markersByKey.clear()
            requestMapRedraw()
        }
        onPoiMarkersSnapshotChanged(emptyList())
    }

    LaunchedEffect(activePoiOverlaySources, poiMarkerSizePx, poiMarkerStyle) {
        viewportQueryCache.invalidate()
        if (activePoiOverlaySources.isEmpty()) {
            clearAllMarkers()
        } else {
            requestQuery()
        }
    }

    LaunchedEffect(locationMarker) {
        mapView.mutateLayers { layers ->
            val reordered = topOverlayCoordinator.sync(layers)
            if (reordered) {
                requestMapRedraw()
            }
        }
    }

    DisposableEffect(mapView) {
        val observer = Observer { requestQuery() }
        mapView.model.mapViewPosition.addObserver(observer)
        requestQuery()
        onDispose { mapView.model.mapViewPosition.removeObserver(observer) }
    }

    LaunchedEffect(mapView, querySignals) {
        querySignals
            .sample(320L)
            .collect {
                if (latestSources.value.isEmpty()) {
                    clearAllMarkers()
                    return@collect
                }

                val width = mapView.width
                val height = mapView.height
                if (width <= 0 || height <= 0) return@collect

                val zoom =
                    mapView.model.mapViewPosition.zoomLevel
                        .toInt()
                val center = mapView.model.mapViewPosition.center
                if (!viewportQueryCache.shouldQuery(center, zoom)) return@collect

                val corners =
                    listOf(
                        runCatching { mapView.mapViewProjection.fromPixels(0.0, 0.0) }.getOrNull(),
                        runCatching { mapView.mapViewProjection.fromPixels(width.toDouble(), 0.0) }.getOrNull(),
                        runCatching { mapView.mapViewProjection.fromPixels(0.0, height.toDouble()) }.getOrNull(),
                        runCatching {
                            mapView.mapViewProjection.fromPixels(width.toDouble(), height.toDouble())
                        }.getOrNull(),
                    ).filterNotNull()
                if (corners.isEmpty()) return@collect

                val minLat = corners.minOf { it.latitude }
                val maxLat = corners.maxOf { it.latitude }
                val minLon = corners.minOf { it.longitude }
                val maxLon = corners.maxOf { it.longitude }
                val viewport =
                    PoiViewport(
                        minLat = minLat,
                        maxLat = maxLat,
                        minLon = minLon,
                        maxLon = maxLon,
                    )
                val effectiveMarkerSizePx = effectivePoiMarkerSizePx(poiMarkerSizePx, zoom)
                val markerBitmapByType =
                    markerBitmapCache.getOrPut(effectiveMarkerSizePx to poiMarkerStyle) {
                        val iconSizePx = (effectiveMarkerSizePx * 0.72f).toInt().coerceAtLeast(12)
                        PoiType.entries.associateWith { type ->
                            val osmIcon = loadOsmPoiIconBitmapOrNull(mapView, type, sizePx = iconSizePx)
                            AndroidBitmap(
                                if (poiMarkerStyle == SettingsRepository.POI_MARKER_STYLE_THEME_ICON) {
                                    createPoiThemeIconMarkerBitmap(osmIcon, effectiveMarkerSizePx, fallbackType = type)
                                } else {
                                    createPoiTypeMarkerBitmap(type, osmIcon, sizePx = effectiveMarkerSizePx)
                                },
                            )
                        }
                    }

                val markers =
                    withContext(Dispatchers.IO) {
                        poiViewModel.queryVisibleMarkers(
                            viewport = viewport,
                            zoomLevel = zoom,
                        )
                    }
                viewportQueryCache.recordSuccessfulQuery(
                    center = center,
                    zoomLevel = zoom,
                    viewport = viewport,
                )
                onPoiMarkersSnapshotChanged(markers)

                mapView.mutateLayers { layers ->
                    val wantedKeys = markers.map { it.key }.toSet()
                    var changed = false

                    (markersByKey.keys - wantedKeys).forEach { key ->
                        markersByKey.remove(key)?.let { entry ->
                            layers.remove(entry.marker)
                            changed = true
                        }
                    }

                    markers.forEach { point ->
                        val latLong = LatLong(point.lat, point.lon)
                        val existing = markersByKey[point.key]
                        val bitmap =
                            markerBitmapByType[point.type]
                                ?: markerBitmapByType[PoiType.GENERIC]
                                ?: return@forEach
                        if (existing == null) {
                            val marker = Marker(latLong, bitmap, 0, 0)
                            markersByKey[point.key] =
                                PoiMarkerEntry(
                                    marker = marker,
                                    type = point.type,
                                    markerSizePx = effectiveMarkerSizePx,
                                    markerStyle = poiMarkerStyle,
                                )
                            layers.add(marker)
                            changed = true
                        } else {
                            if (
                                existing.type != point.type ||
                                existing.markerSizePx != effectiveMarkerSizePx ||
                                existing.markerStyle != poiMarkerStyle
                            ) {
                                layers.remove(existing.marker)
                                val marker = Marker(latLong, bitmap, 0, 0)
                                markersByKey[point.key] =
                                    PoiMarkerEntry(
                                        marker = marker,
                                        type = point.type,
                                        markerSizePx = effectiveMarkerSizePx,
                                        markerStyle = poiMarkerStyle,
                                    )
                                layers.add(marker)
                                changed = true
                            } else if (setMarkerLatLongIfChanged(existing.marker, latLong)) {
                                changed = true
                            }
                        }
                    }

                    val reordered = topOverlayCoordinator.sync(layers)

                    if (changed || reordered) {
                        requestMapRedraw()
                    }
                }
            }
    }

    DisposableEffect(mapView) {
        onDispose {
            onPoiMarkersSnapshotChanged(emptyList())
            mapView.mutateLayers { layers ->
                markersByKey.values.forEach { entry -> layers.remove(entry.marker) }
                markersByKey.clear()
                mapView.requestLayerRedrawSafely()
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")
private fun GpxAndInspectionOverlayEffect(
    mapView: MapView,
    activeGpxDetails: List<GpxTrackDetails>,
    routeToolPreviewPoints: List<LatLong>,
    routeToolCreatePreviewActive: Boolean,
    routeToolDraftPoints: List<LatLong>,
    gpxTrackColor: Int,
    gpxTrackColorMode: String,
    gpxTrackWidth: Float,
    gpxTrackOpacityPercent: Int,
    gpxTrackDirectionArrowsEnabled: Boolean,
    locationMarker: RotatableMarker?,
    selectedPointA: LatLong?,
    selectedPointB: LatLong?,
    markerAHolder: Array<Marker?>,
    markerBHolder: Array<Marker?>,
    topOverlayCoordinator: MapTopOverlayCoordinator,
    requestMapRedraw: () -> Unit,
) {
    val useElevationTrackColors = gpxTrackColorMode == SettingsRepository.GPX_TRACK_COLOR_MODE_ELEVATION

    val trackPaint =
        remember {
            AndroidGraphicFactory.INSTANCE.createPaint().apply { setStyle(Style.STROKE) }
        }
    val previewPaint =
        remember {
            AndroidGraphicFactory.INSTANCE.createPaint().apply { setStyle(Style.STROKE) }
        }
    val draftPaint =
        remember {
            AndroidGraphicFactory.INSTANCE.createPaint().apply { setStyle(Style.STROKE) }
        }

    // Stable caches
    val polylinesById = remember(mapView) { mutableMapOf<String, List<Polyline>>() }
    val elevationPolylinesById = remember(mapView) { mutableMapOf<String, List<Polyline>>() }
    val startMarkersById = remember(mapView) { mutableMapOf<String, Marker>() }
    val endMarkersById = remember(mapView) { mutableMapOf<String, Marker>() }
    val lodById = remember(mapView) { mutableMapOf<String, TrackLodLevels>() }
    val displayedLodBucketById = remember(mapView) { mutableMapOf<String, Int>() }
    val previewPolyline =
        remember(mapView) {
            Polyline(previewPaint, AndroidGraphicFactory.INSTANCE)
        }
    val draftPolyline =
        remember(mapView) {
            Polyline(draftPaint, AndroidGraphicFactory.INSTANCE)
        }

    // Bitmaps
    val markerBitmapA = remember { AndroidBitmap(makeLabeledYellowDotBitmap("A", 28, 3, 235)) }
    val markerBitmapB = remember { AndroidBitmap(makeLabeledYellowDotBitmap("B", 28, 3, 190)) }
    val startBitmap =
        remember {
            AndroidBitmap(
                makeLabeledDotBitmap(
                    label = "S",
                    sizePx = 20,
                    strokePx = 2,
                    fillColorArgb = android.graphics.Color.rgb(76, 175, 80),
                ),
            )
        }
    val endBitmap =
        remember {
            AndroidBitmap(
                makeLabeledDotBitmap(
                    label = "E",
                    sizePx = 20,
                    strokePx = 2,
                    fillColorArgb = android.graphics.Color.rgb(244, 67, 54),
                ),
            )
        }
    val directionArrowLayer =
        remember(mapView) {
            GpxDirectionArrowLayer(AndroidBitmap(createGpxDirectionArrowBitmap()))
        }

    LaunchedEffect(gpxTrackColor, gpxTrackWidth, gpxTrackOpacityPercent, routeToolCreatePreviewActive) {
        trackPaint.color =
            applyOpacityToColor(
                color = gpxTrackColor,
                opacityPercent = gpxTrackOpacityPercent,
            )
        trackPaint.strokeWidth = gpxTrackWidth
        previewPaint.color =
            if (routeToolCreatePreviewActive) {
                Color.argb(
                    ROUTE_TOOL_CREATE_PREVIEW_ALPHA,
                    ROUTE_TOOL_PREVIEW_RED,
                    ROUTE_TOOL_PREVIEW_GREEN,
                    ROUTE_TOOL_PREVIEW_BLUE,
                )
            } else {
                Color.argb(
                    ROUTE_TOOL_PREVIEW_ALPHA,
                    ROUTE_TOOL_PREVIEW_RED,
                    ROUTE_TOOL_PREVIEW_GREEN,
                    ROUTE_TOOL_PREVIEW_BLUE,
                )
            }
        previewPaint.strokeWidth = maxOf(gpxTrackWidth + 2f, 6f)
        draftPaint.color =
            Color.argb(
                ROUTE_TOOL_DRAFT_ALPHA,
                ROUTE_TOOL_PREVIEW_RED,
                ROUTE_TOOL_PREVIEW_GREEN,
                ROUTE_TOOL_PREVIEW_BLUE,
            )
        draftPaint.strokeWidth = maxOf(gpxTrackWidth, 4f)
        requestMapRedraw()
    }

    DisposableEffect(
        mapView,
        useElevationTrackColors,
        gpxTrackWidth,
        gpxTrackOpacityPercent,
        gpxTrackDirectionArrowsEnabled,
    ) {
        val observer =
            Observer {
                val zoomNow =
                    mapView.model.mapViewPosition.zoomLevel
                        .toInt()
                val newBucket = zoomBucketFor(zoomNow)
                mapView.mutateLayers(coalescingKey = directionArrowLayer) { layers ->
                    var changed = false

                    lodById.forEach { (id, lod) ->
                        val bucketChanged = displayedLodBucketById[id] != newBucket
                        if (!bucketChanged) {
                            return@forEach
                        }
                        val renderPoints = lod.pointsForZoom(zoomNow)

                        if (bucketChanged) {
                            if (useElevationTrackColors) {
                                elevationPolylinesById.remove(id)?.forEach { layers.remove(it) }
                                val segments =
                                    buildElevationTrackSegments(
                                        points = renderPoints,
                                        opacityPercent = gpxTrackOpacityPercent,
                                    )
                                elevationPolylinesById[id] =
                                    createElevationTrackPolylines(
                                        segments = segments,
                                        strokeWidth = gpxTrackWidth,
                                    ).also { polylines ->
                                        polylines.forEach(layers::add)
                                    }
                                changed = true
                            } else {
                                val syncResult =
                                    syncSolidTrackPolylines(
                                        layers = layers,
                                        current = polylinesById[id].orEmpty(),
                                        segments = renderPoints.latLongSegments(),
                                        paint = trackPaint,
                                    )
                                polylinesById[id] = syncResult.polylines
                                changed = syncResult.changed || changed
                            }
                            displayedLodBucketById[id] = newBucket
                        }
                    }

                    if (changed && layers.contains(directionArrowLayer)) {
                        layers.remove(directionArrowLayer)
                        layers.add(directionArrowLayer)
                    }

                    val reordered = if (changed) topOverlayCoordinator.sync(layers) else false
                    if (changed || reordered) requestMapRedraw()
                }
            }

        mapView.model.mapViewPosition.addObserver(observer)
        onDispose { mapView.model.mapViewPosition.removeObserver(observer) }
    }

    // Update polylines + S/E markers
    LaunchedEffect(
        activeGpxDetails,
        useElevationTrackColors,
        gpxTrackWidth,
        gpxTrackOpacityPercent,
        gpxTrackDirectionArrowsEnabled,
    ) {
        val wantedIds = activeGpxDetails.map { it.id }.toSet()
        val computedLodById =
            withContext(Dispatchers.Default) {
                activeGpxDetails.associate { details ->
                    details.id to buildTrackLodLevels(details.trackPoints)
                }
            }

        mapView.mutateLayers { layers ->
            var changed = false
            // remove old layers
            val staleOverlayIds =
                trackedGpxOverlayIds(
                    polylinesById = polylinesById,
                    elevationPolylinesById = elevationPolylinesById,
                    startMarkersById = startMarkersById,
                    endMarkersById = endMarkersById,
                    lodById = lodById,
                    displayedLodBucketById = displayedLodBucketById,
                ) - wantedIds
            staleOverlayIds.forEach { id ->
                polylinesById.remove(id)?.forEach { polyline ->
                    changed = layers.remove(polyline) || changed
                }
                elevationPolylinesById.remove(id)?.forEach { polyline ->
                    layers.remove(polyline)
                    changed = true
                }
                if (startMarkersById.remove(id)?.let {
                        layers.remove(it)
                        true
                    } == true
                ) {
                    changed = true
                }
                if (endMarkersById.remove(id)?.let {
                        layers.remove(it)
                        true
                    } == true
                ) {
                    changed = true
                }
                lodById.remove(id)
                displayedLodBucketById.remove(id)
            }

            val arrowsVisible = gpxTrackDirectionArrowsEnabled && computedLodById.isNotEmpty()
            directionArrowLayer.trackLods = if (arrowsVisible) computedLodById else emptyMap()
            directionArrowLayer.isVisible = arrowsVisible
            val arrowLayerAttached = layers.contains(directionArrowLayer)
            if (arrowsVisible && !arrowLayerAttached) {
                layers.add(directionArrowLayer)
                changed = true
            } else if (!arrowsVisible && arrowLayerAttached) {
                layers.remove(directionArrowLayer)
                changed = true
            }

            // add/update polylines and S/E markers
            val zoomNow =
                mapView.model.mapViewPosition.zoomLevel
                    .toInt()
            val currentBucket = zoomBucketFor(zoomNow)
            activeGpxDetails.forEach { details ->
                val lod = computedLodById[details.id] ?: return@forEach
                lodById[details.id] = lod
                val renderPoints = lod.pointsForZoom(zoomNow)

                if (useElevationTrackColors) {
                    polylinesById.remove(details.id)?.forEach { solidPolyline ->
                        changed = layers.remove(solidPolyline) || changed
                    }
                    elevationPolylinesById.remove(details.id)?.forEach { polyline ->
                        layers.remove(polyline)
                        changed = true
                    }
                    val segments =
                        buildElevationTrackSegments(
                            points = renderPoints,
                            opacityPercent = gpxTrackOpacityPercent,
                        )
                    elevationPolylinesById[details.id] =
                        createElevationTrackPolylines(
                            segments = segments,
                            strokeWidth = gpxTrackWidth,
                        ).also { polylines ->
                            polylines.forEach(layers::add)
                            if (polylines.isNotEmpty()) {
                                changed = true
                            }
                        }
                } else {
                    elevationPolylinesById.remove(details.id)?.forEach { polyline ->
                        layers.remove(polyline)
                        changed = true
                    }
                    val syncResult =
                        syncSolidTrackPolylines(
                            layers = layers,
                            current = polylinesById[details.id].orEmpty(),
                            segments = renderPoints.latLongSegments(),
                            paint = trackPaint,
                        )
                    polylinesById[details.id] = syncResult.polylines
                    changed = syncResult.changed || changed
                }
                displayedLodBucketById[details.id] = currentBucket

                // Start marker
                details.startPoint?.let { start ->
                    val existing = startMarkersById[details.id]
                    if (existing == null) {
                        val m = Marker(start, startBitmap, 0, 0)
                        startMarkersById[details.id] = m
                        layers.add(m)
                        changed = true
                    } else {
                        if (setMarkerLatLongIfChanged(existing, start)) changed = true
                    }
                }

                // End marker
                details.endPoint?.let { end ->
                    val existing = endMarkersById[details.id]
                    if (existing == null) {
                        val m = Marker(end, endBitmap, 0, 0)
                        endMarkersById[details.id] = m
                        layers.add(m)
                        changed = true
                    } else {
                        if (setMarkerLatLongIfChanged(existing, end)) changed = true
                    }
                }
            }

            val reordered = topOverlayCoordinator.sync(layers)

            if (changed || reordered) requestMapRedraw()
        }
    }

    LaunchedEffect(routeToolPreviewPoints) {
        mapView.mutateLayers { layers ->
            var changed = false
            val hasPreview = routeToolPreviewPoints.size >= 2
            val previewAttached = layers.contains(previewPolyline)

            if (hasPreview) {
                if (!previewAttached) {
                    layers.add(previewPolyline)
                    changed = true
                }
                if (!hasSameLatLongs(previewPolyline.latLongs, routeToolPreviewPoints)) {
                    previewPolyline.latLongs.clear()
                    previewPolyline.latLongs.addAll(routeToolPreviewPoints)
                    changed = true
                }
            } else if (previewAttached) {
                layers.remove(previewPolyline)
                previewPolyline.latLongs.clear()
                changed = true
            }

            val reordered = topOverlayCoordinator.sync(layers)
            if (changed || reordered) requestMapRedraw()
        }
    }

    LaunchedEffect(routeToolDraftPoints) {
        mapView.mutateLayers { layers ->
            var changed = false
            val hasDraft = routeToolDraftPoints.size >= 2
            val draftAttached = layers.contains(draftPolyline)

            if (hasDraft) {
                if (!draftAttached) {
                    layers.add(draftPolyline)
                    changed = true
                }
                if (!hasSameLatLongs(draftPolyline.latLongs, routeToolDraftPoints)) {
                    draftPolyline.latLongs.clear()
                    draftPolyline.latLongs.addAll(routeToolDraftPoints)
                    changed = true
                }
            } else if (draftAttached) {
                layers.remove(draftPolyline)
                draftPolyline.latLongs.clear()
                changed = true
            }

            val reordered = topOverlayCoordinator.sync(layers)
            if (changed || reordered) requestMapRedraw()
        }
    }

    // Re-apply desired z-order when top overlays change.
    LaunchedEffect(locationMarker, selectedPointA, selectedPointB) {
        mapView.mutateLayers { layers ->
            val changed = topOverlayCoordinator.sync(layers)
            if (changed) requestMapRedraw()
        }
    }

    // ✅ Marker A (NO re-snap: GpxViewModel already provides a point ON the track)
    LaunchedEffect(selectedPointA, activeGpxDetails) {
        mapView.mutateLayers { layers ->
            var changed = false
            markerAHolder[0]?.let {
                layers.remove(it)
                changed = true
            }
            markerAHolder[0] =
                selectedPointA?.let { ll ->
                    val snapped = snapToRenderedTrackOrNull(ll, activeGpxDetails) ?: ll
                    Marker(snapped, markerBitmapA, 0, 0)
                        .also {
                            layers.add(it)
                            changed = true
                        }
                }
            val reordered = topOverlayCoordinator.sync(layers)
            if (changed || reordered) requestMapRedraw()
        }
    }

    // ✅ Marker B (NO re-snap: GpxViewModel already provides a point ON the track)
    LaunchedEffect(selectedPointB, activeGpxDetails) {
        mapView.mutateLayers { layers ->
            var changed = false
            markerBHolder[0]?.let {
                layers.remove(it)
                changed = true
            }
            markerBHolder[0] =
                selectedPointB?.let { ll ->
                    val snapped = snapToRenderedTrackOrNull(ll, activeGpxDetails) ?: ll
                    Marker(snapped, markerBitmapB, 0, 0)
                        .also {
                            layers.add(it)
                            changed = true
                        }
                }
            val reordered = topOverlayCoordinator.sync(layers)
            if (changed || reordered) requestMapRedraw()
        }
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.mutateLayers { layers ->
                polylinesById.values.flatten().forEach(layers::remove)
                elevationPolylinesById.values.flatten().forEach(layers::remove)
                startMarkersById.values.forEach(layers::remove)
                endMarkersById.values.forEach(layers::remove)
                layers.remove(directionArrowLayer)
                markerAHolder[0]?.let(layers::remove)
                markerBHolder[0]?.let(layers::remove)
                layers.remove(previewPolyline)

                polylinesById.clear()
                elevationPolylinesById.clear()
                startMarkersById.clear()
                endMarkersById.clear()
                directionArrowLayer.trackLods = emptyMap()
                lodById.clear()
                displayedLodBucketById.clear()
                previewPolyline.latLongs.clear()
                markerAHolder[0] = null
                markerBHolder[0] = null

                mapView.requestLayerRedrawSafely()
            }
        }
    }
}

@Suppress("LongParameterList")
private fun trackedGpxOverlayIds(
    polylinesById: Map<String, List<Polyline>>,
    elevationPolylinesById: Map<String, List<Polyline>>,
    startMarkersById: Map<String, Marker>,
    endMarkersById: Map<String, Marker>,
    lodById: Map<String, TrackLodLevels>,
    displayedLodBucketById: Map<String, Int>,
): Set<String> =
    buildSet {
        addAll(polylinesById.keys)
        addAll(elevationPolylinesById.keys)
        addAll(startMarkersById.keys)
        addAll(endMarkersById.keys)
        addAll(lodById.keys)
        addAll(displayedLodBucketById.keys)
    }

private fun createElevationTrackPolylines(
    segments: List<ElevationTrackSegment>,
    strokeWidth: Float,
): List<Polyline> {
    val outlineWidth = strokeWidth + ELEVATION_TRACK_OUTLINE_WIDTH_EXTRA_PX
    val outlineColor = Color.argb(ELEVATION_TRACK_OUTLINE_ALPHA, 18, 24, 32)
    val outlines =
        segments.map { segment ->
            createElevationTrackPolyline(
                points = segment.points,
                color = outlineColor,
                strokeWidth = outlineWidth,
            )
        }
    val coloredSegments =
        segments.map { segment ->
            createElevationTrackPolyline(
                points = segment.points,
                color = segment.color,
                strokeWidth = strokeWidth,
            )
        }
    return outlines + coloredSegments
}

private fun createElevationTrackPolyline(
    points: List<LatLong>,
    color: Int,
    strokeWidth: Float,
): Polyline =
    Polyline(
        createGpxTrackPaint(
            color = color,
            strokeWidth = strokeWidth,
        ),
        AndroidGraphicFactory.INSTANCE,
    ).also { polyline ->
        polyline.latLongs.addAll(points)
    }
