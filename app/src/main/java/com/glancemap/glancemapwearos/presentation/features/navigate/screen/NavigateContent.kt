package com.glancemap.glancemapwearos.presentation.features.navigate

import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.core.maps.MAP_ZOOM_MAX_LEVEL
import com.glancemap.glancemapwearos.core.maps.MAP_ZOOM_MIN_LEVEL
import com.glancemap.glancemapwearos.core.service.diagnostics.BenchmarkTrace
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import com.glancemap.glancemapwearos.presentation.features.maps.MapHolder
import com.glancemap.glancemapwearos.presentation.features.maps.MapLayerMutationCoordinator
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.poi.PoiNavigateTarget
import com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlayMarker
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.LocalFullscreenPopupTimeFormat
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolCreatePreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSession
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import kotlinx.coroutines.delay
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.model.common.Observer

@Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "LongParameterList",
)
@Composable
internal fun NavigateContent(
    hasLocationPermission: Boolean,
    focusRequester: FocusRequester,
    mapHolder: MapHolder?,
    onMapViewReadyForRendering: () -> Unit,
    onNavigateTimeSuppressedChange: (Boolean) -> Unit,
    showNavigateTime: Boolean,
    navigateTimeFormat: String,
    mapAppearanceApplyInProgress: Boolean,
    slopeOverlayToggleEnabled: Boolean,
    slopeOverlayEnabled: Boolean,
    slopeOverlayProcessing: Boolean,
    slopeOverlayProgressPercent: Int?,
    zoomMin: Int,
    zoomMax: Int,
    zoomMinScaleMeters: Int,
    zoomMaxScaleMeters: Int,
    crownZoomEnabled: Boolean,
    crownZoomInverted: Boolean,
    mapZoomButtonsMode: String,
    northIndicatorMode: String,
    currentZoomLevel: Int,
    onZoomLevelChange: (Int) -> Unit,
    onViewportChanged: (LatLong, Int) -> Unit,
    isMetric: Boolean,
    navMode: NavMode,
    locationMarker: RotatableMarker?,
    lastKnownLocation: LatLong?,
    onToggleOrientation: () -> Unit,
    onUserPanStarted: () -> Unit,
    onRecenter: () -> Unit,
    onRecenterRequested: () -> Unit,
    triggerHaptic: () -> Unit,
    onMenuClick: () -> Unit,
    onPermissionLaunch: () -> Unit,
    mapRotationDeg: Float,
    navigationMarkerAnchorMode: String,
    compassHeadingDeg: Float,
    liveElevationEnabled: Boolean,
    liveDistanceEnabled: Boolean,
    keepAppOpen: Boolean,
    onKeepAppOpenToggle: () -> Unit,
    backButtonExitsNavigation: Boolean,
    traceRecordingState: TraceRecordingUiState,
    recordingStatusMessage: String?,
    recordingDashboardMetricSlots: List<String>,
    turnByTurnDashboardMetricSlots: List<String>,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float,
    recordingDashboardExpandRequestToken: Long,
    recordingActionPromptRequestToken: Long,
    onRecordingTimeTap: () -> Unit,
    onRecordingTimeLongPress: () -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onFinishRecording: (String?) -> Unit,
    onDiscardRecording: () -> Unit,
    onRecordingMetricSelected: (Int, String) -> Unit,
    onTurnByTurnMetricSelected: (Int, String) -> Unit,
    shortcutTrayExpanded: Boolean,
    onShortcutTrayToggle: () -> Unit,
    onShortcutTrayDismiss: () -> Unit,
    onOpenGpxTools: () -> Unit,
    onStartPoiCreation: () -> Unit,
    gpsIndicatorState: GpsFixIndicatorState,
    gpsEnvironmentWarning: GpsEnvironmentWarning,
    watchGpsDegradedWarning: Boolean,
    isOfflineMode: Boolean,
    isGpxInspectionEnabled: Boolean,
    selectingGpxPointB: Boolean,
    onCancelSelectingGpxPointB: () -> Unit,
    turnByTurnGuidanceState: TurnByTurnGuidanceState,
    turnByTurnGuidancePaused: Boolean,
    turnByTurnVoiceGuidanceEnabled: Boolean,
    turnByTurnCompactPopupEnabled: Boolean,
    onTurnByTurnVoiceGuidanceChange: (Boolean) -> Unit,
    guideBackToRouteActive: Boolean,
    showGuideBackPrompt: Boolean,
    startDecisionPrompt: GuidanceDecisionPrompt?,
    onPauseTurnByTurnGuidance: () -> Unit,
    onResumeTurnByTurnGuidance: () -> Unit,
    onStopTurnByTurnGuidance: () -> Unit,
    onGuideBackToRoute: () -> Unit,
    onDismissGuideBackPrompt: () -> Unit,
    onAcceptStartDecisionPrompt: () -> Unit,
    onDismissStartDecisionPrompt: () -> Unit,
    activeGpxDetails: List<GpxTrackDetails>,
    gpxTrackColor: Int,
    routeToolSession: RouteToolSession?,
    crosshairSelectionActive: Boolean,
    crosshairSelectionTitle: String? = null,
    crosshairSelectionInstruction: String? = null,
    crosshairSelectionBusy: Boolean = false,
    crosshairSelectionBusyMessage: String? = null,
    routeToolCreatePreview: RouteToolCreatePreview?,
    routeToolDraftConnectorPoints: List<LatLong>,
    routeToolCreatePreviewInProgress: Boolean,
    routeToolCreatePreviewMessage: String?,
    reshapePreviewInspectMode: Boolean,
    reshapePreviewPoints: List<LatLong>,
    reshapePreviewTitle: String,
    reshapePreviewInstruction: String,
    reshapePreviewBusy: Boolean,
    reshapePreviewBusyMessage: String?,
    reshapePreviewMessage: String?,
    onRouteToolPickHere: (LatLong) -> Unit,
    onRouteToolUndoLastPoint: () -> Unit,
    onRouteToolSaveCreatePreview: () -> Unit,
    onRouteToolRefreshCreatePreview: () -> Unit,
    onCancelRouteToolMode: () -> Unit,
    onDismissReshapePreview: () -> Unit,
    onSaveReshapePreview: () -> Unit,
    onCrosshairSelectionPickHere: ((LatLong) -> Unit)? = null,
    onCancelCrosshairSelection: (() -> Unit)? = null,
    onInspectTrack: (LatLong) -> Unit,
    visiblePoiMarkers: List<PoiOverlayMarker>,
    poiFocusTarget: PoiNavigateTarget?,
    onPoiFocusTargetConsumed: () -> Unit,
    onPoiTapCreateGpx: (PoiOverlayMarker) -> Unit,
    poiPopupTimeoutSeconds: Int,
    poiPopupManualCloseOnly: Boolean,
    markerMotionDebugOverlayLabel: String?,
    onCompassIssueNow: (() -> Unit)?,
) {
    SideEffect {
        BenchmarkTrace.mark("recompose.NavigateContent")
    }
    val mapView = mapHolder?.mapView
    val context = LocalContext.current
    val screenSize = rememberWearScreenSize()
    val adaptive = rememberWearAdaptiveSpec()
    val latestOnNavigateTimeSuppressedChange = rememberUpdatedState(onNavigateTimeSuppressedChange)
    val expandedOverlayState =
        rememberNavigateExpandedOverlayState(
            focusRequester = focusRequester,
            backButtonExitsNavigation = backButtonExitsNavigation,
            turnByTurnGuidanceActive = turnByTurnGuidanceState.active,
            traceRecordingActive = traceRecordingState.active,
            recordingActionPromptRequestToken = recordingActionPromptRequestToken,
        )
    val turnByTurnFullScreenExpanded = expandedOverlayState.turnByTurnFullScreenExpanded
    val recordingDashboardFullScreenExpanded = expandedOverlayState.recordingDashboardFullScreenExpanded
    val combinedGuidanceRecordingFullScreenExpanded =
        expandedOverlayState.combinedGuidanceRecordingFullScreenExpanded
    val effectiveRecordingActionPromptRequestToken =
        expandedOverlayState.effectiveRecordingActionPromptRequestToken
    val suppressMapRenderingForGuidance = expandedOverlayState.suppressMapRenderingForGuidance

    NavigateMapReadyEffect(
        mapView = mapView,
        onMapViewReadyForRendering = onMapViewReadyForRendering,
    )
    val sizing =
        rememberNavigateContentSizing(
            adaptive = adaptive,
            screenSize = screenSize,
            mapZoomButtonsMode = mapZoomButtonsMode,
        )
    val gestureExclusionStripDp = sizing.gestureExclusionStripDp
    val zoomButtonSize = sizing.zoomButtonSize
    val zoomIconSize = sizing.zoomIconSize
    val zoomLabelTopPadding = sizing.zoomLabelTopPadding
    val zoomScaleBarWidth = sizing.zoomScaleBarWidth
    val showZoomPlusButton = sizing.showZoomPlusButton
    val showZoomMinusButton = sizing.showZoomMinusButton
    val sideButtonSize = sizing.sideButtonSize
    val sideButtonIconSize = sizing.sideButtonIconSize
    val sideButtonEdgePadding = sizing.sideButtonEdgePadding
    val liveElevationIconSize = sizing.liveElevationIconSize
    val navButtonBottomPadding = sizing.navButtonBottomPadding
    val navButtonSize = sizing.navButtonSize
    val navButtonIconSize = sizing.navButtonIconSize
    val northIndicatorButtonSize = sizing.northIndicatorButtonSize
    val northIndicatorIconSize = sizing.northIndicatorIconSize
    val latestNavMode = rememberUpdatedState(navMode)
    val latestOnUserPanStarted = rememberUpdatedState(onUserPanStarted)
    val latestOnInspectTrack = rememberUpdatedState(onInspectTrack)
    val latestInspectionEnabled =
        rememberUpdatedState(
            isGpxInspectionEnabled &&
                routeToolSession == null &&
                !crosshairSelectionActive &&
                !reshapePreviewInspectMode,
        )
    val latestRouteToolSession = rememberUpdatedState(routeToolSession)
    val latestCrosshairSelectionActive = rememberUpdatedState(crosshairSelectionActive)
    val latestReshapePreviewInspectMode = rememberUpdatedState(reshapePreviewInspectMode)
    val latestMapView = rememberUpdatedState(mapView)
    val latestOnZoomLevelChange = rememberUpdatedState(onZoomLevelChange)
    val latestOnViewportChanged = rememberUpdatedState(onViewportChanged)
    val latestVisiblePoiMarkers = rememberUpdatedState(visiblePoiMarkers)
    val latestLastKnownLocation = rememberUpdatedState(lastKnownLocation)
    val latestNavigationMarkerAnchorMode = rememberUpdatedState(navigationMarkerAnchorMode)
    var rotaryScrollAccumulator by remember(mapView, crownZoomEnabled, crownZoomInverted) {
        mutableStateOf(0f)
    }
    var poiTapMarker by remember { mutableStateOf<PoiOverlayMarker?>(null) }
    var poiTapPopup by remember { mutableStateOf<PoiTapPopupContent?>(null) }
    var poiTapPopupExpanded by remember { mutableStateOf(false) }
    var poiTapPopupScrollInProgress by remember { mutableStateOf(false) }
    var routeToolOverlayRevision by remember { mutableIntStateOf(0) }
    var pendingDoubleTapPanningCheck by remember { mutableStateOf(false) }
    val panTelemetry = remember(mapView) { NavigatePanTelemetry() }
    val latestPoiTapPopupScrollInProgress = rememberUpdatedState(poiTapPopupScrollInProgress)

    LaunchedEffect(poiFocusTarget) {
        val target = poiFocusTarget ?: return@LaunchedEffect
        val marker =
            PoiOverlayMarker(
                key = "focus:${target.lat},${target.lon}:${target.label.orEmpty()}",
                lat = target.lat,
                lon = target.lon,
                label = target.label,
                type = target.type,
                details = target.details,
            )
        poiTapMarker = marker
        poiTapPopup = buildPoiTapPopupContent(marker, isMetric = isMetric)
        poiTapPopupExpanded = false
        poiTapPopupScrollInProgress = false
        onPoiFocusTargetConsumed()
    }

    LaunchedEffect(reshapePreviewInspectMode, reshapePreviewPoints, mapView, zoomMin, zoomMax) {
        val currentMapView = mapView ?: return@LaunchedEffect
        if (!reshapePreviewInspectMode || reshapePreviewPoints.size < 2) return@LaunchedEffect
        onUserPanStarted()
        currentMapView.post {
            fitMapViewToPreviewPoints(
                mapView = currentMapView,
                points = reshapePreviewPoints,
                zoomMin = zoomMin,
                zoomMax = zoomMax,
            )
        }
    }

    fun applyMapZoomStep(step: Int): Boolean {
        val zoomApplied =
            mapView?.let { currentMapView ->
                val current =
                    currentMapView.model.mapViewPosition.zoomLevel
                        .toInt()
                val next = (current + step).coerceIn(zoomMin, zoomMax)
                if (next == current) {
                    false
                } else {
                    MapLayerMutationCoordinator.setGestureActive(currentMapView, true)
                    try {
                        currentMapView.model.mapViewPosition.setZoomLevel(next.toByte(), false)
                    } finally {
                        MapLayerMutationCoordinator.setGestureActive(currentMapView, false)
                    }
                    true
                }
            } ?: false
        if (zoomApplied) triggerHaptic()
        return zoomApplied
    }

    fun canApplyMapZoomStep(step: Int): Boolean {
        val mv = mapView ?: return false
        val current =
            mv.model.mapViewPosition.zoomLevel
                .toInt()
        val next = (current + step).coerceIn(zoomMin, zoomMax)
        return next != current
    }

    fun checkDoubleTapPanningAfterViewportSettles() {
        val mv = latestMapView.value ?: return
        mv.post {
            if (!pendingDoubleTapPanningCheck) return@post
            pendingDoubleTapPanningCheck = false
            if (latestNavMode.value == NavMode.PANNING) return@post
            if (
                shouldEnterPanningAfterDoubleTap(
                    center = mv.model.mapViewPosition.center,
                    marker =
                        latestLastKnownLocation.value?.let { marker ->
                            mv.resolveMapCenterForNavigationMarker(
                                markerLatLong = marker,
                                markerAnchorMode = latestNavigationMarkerAnchorMode.value,
                            )
                        },
                )
            ) {
                latestOnUserPanStarted.value.invoke()
            }
        }
    }

    fun scheduleDoubleTapPanningCheck() {
        latestMapView.value?.postDelayed(
            { checkDoubleTapPanningAfterViewportSettles() },
            DOUBLE_TAP_PANNING_CHECK_DELAY_MS,
        )
    }

    val gestureDetector =
        remember {
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        val mv = latestMapView.value ?: return false
                        val anchor = mv.resolveNavigationMarkerScreenAnchor(latestNavigationMarkerAnchorMode.value)
                        val (x, y) =
                            unrotateTouchToMapSpace(
                                point = ScreenAnchor(e.x.toDouble(), e.y.toDouble()),
                                mapWidth = mv.width.toDouble(),
                                mapHeight = mv.height.toDouble(),
                                mapRotationDeg = mv.mapRotation.degrees.toDouble(),
                                pivot = anchor,
                            )
                        val ll = runCatching { mv.mapViewProjection.fromPixels(x, y) }.getOrNull() ?: return false
                        if (latestRouteToolSession.value != null) return false
                        if (latestCrosshairSelectionActive.value) {
                            return false
                        }
                        if (latestReshapePreviewInspectMode.value) {
                            return false
                        }
                        val zoomNow =
                            mv.model.mapViewPosition.zoomLevel
                                .toInt()
                        val tappedPoi =
                            findTappedPoiMarker(
                                tap = ll,
                                zoomLevel = zoomNow,
                                markers = latestVisiblePoiMarkers.value,
                            ) ?: return false

                        triggerHaptic()
                        poiTapMarker = tappedPoi
                        poiTapPopup = buildPoiTapPopupContent(tappedPoi, isMetric = isMetric)
                        poiTapPopupExpanded = false
                        poiTapPopupScrollInProgress = false
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (!latestInspectionEnabled.value) return
                        val mv = latestMapView.value ?: return
                        val anchor = mv.resolveNavigationMarkerScreenAnchor(latestNavigationMarkerAnchorMode.value)
                        val (x, y) =
                            unrotateTouchToMapSpace(
                                point = ScreenAnchor(e.x.toDouble(), e.y.toDouble()),
                                mapWidth = mv.width.toDouble(),
                                mapHeight = mv.height.toDouble(),
                                mapRotationDeg = mv.mapRotation.degrees.toDouble(),
                                pivot = anchor,
                            )
                        val ll =
                            runCatching {
                                mv.mapViewProjection.fromPixels(x, y)
                            }.getOrNull() ?: return
                        latestOnInspectTrack.value(ll)
                    }
                },
            )
        }
    val doubleTapGestureDetector =
        remember {
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (latestNavMode.value == NavMode.PANNING) return false
                        if (latestLastKnownLocation.value == null) return false
                        pendingDoubleTapPanningCheck = true
                        scheduleDoubleTapPanningCheck()
                        return false
                    }
                },
            )
        }
    val routeToolModeActive = routeToolSession != null || crosshairSelectionActive || reshapePreviewInspectMode
    val density = LocalDensity.current
    var visibleMapSizePx by remember { mutableStateOf(IntSize.Zero) }
    val liveHudState =
        rememberNavigateLiveHudState(
            enabled = hasLocationPermission && mapView != null,
            mapHolder = mapHolder,
            mapView = mapView,
            currentZoomLevel = currentZoomLevel,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            zoomMinScaleMeters = zoomMinScaleMeters,
            zoomMaxScaleMeters = zoomMaxScaleMeters,
            isMetric = isMetric,
            navMode = navMode,
            liveElevationEnabled = liveElevationEnabled,
            liveDistanceEnabled = liveDistanceEnabled,
            locationMarker = locationMarker,
            lastKnownLocation = lastKnownLocation,
            visibleMapSizePx = visibleMapSizePx,
        )
    val scaleIndicator = liveHudState.scaleIndicator
    val showScaleBar = liveHudState.showScaleBar
    val liveElevationLabel = liveHudState.liveElevationLabel
    val liveDistanceLabel = liveHudState.liveDistanceLabel
    val fullScreenPopupExpanded =
        turnByTurnFullScreenExpanded ||
            recordingDashboardFullScreenExpanded ||
            combinedGuidanceRecordingFullScreenExpanded
    val shouldSuppressNavigateTime =
        !fullScreenPopupExpanded &&
            adaptive.fontScale > 1f &&
            (showScaleBar || routeToolModeActive)
    val overlayOwnsRotary = expandedOverlayState.overlayOwnsRotary

    LaunchedEffect(shouldSuppressNavigateTime) {
        latestOnNavigateTimeSuppressedChange.value(shouldSuppressNavigateTime)
    }

    DisposableEffect(Unit) {
        onDispose {
            latestOnNavigateTimeSuppressedChange.value(false)
        }
    }

    LaunchedEffect(poiTapPopup, poiTapPopupExpanded, poiPopupTimeoutSeconds, poiPopupManualCloseOnly) {
        if (poiTapPopup == null || poiPopupManualCloseOnly) return@LaunchedEffect
        var remainingMs = poiPopupTimeoutSeconds.coerceAtLeast(1) * 1_000L
        while (remainingMs > 0L) {
            val tickMs = minOf(100L, remainingMs)
            delay(tickMs)
            if (!latestPoiTapPopupScrollInProgress.value) {
                remainingMs -= tickMs
            }
        }
        poiTapMarker = null
        poiTapPopup = null
        poiTapPopupExpanded = false
        poiTapPopupScrollInProgress = false
    }

    val poiTapMessage =
        when {
            poiTapPopup == null -> null
            poiTapPopupExpanded -> poiTapPopup?.expandedText ?: poiTapPopup?.compactText
            else -> poiTapPopup?.compactText
        }
    val expandedMapSurfaceHeightPx =
        navigationMarkerMapSurfaceHeightPx(
            visibleHeightPx = visibleMapSizePx.height,
            density = density.density,
            markerAnchorMode = navigationMarkerAnchorMode,
        )
    val expandedMapSurfaceEnabled =
        navigationMarkerAnchorMode == SettingsRepository.NAVIGATION_MARKER_ANCHOR_LOWER &&
            expandedMapSurfaceHeightPx > visibleMapSizePx.height &&
            visibleMapSizePx.height > 0

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { visibleMapSizePx = it }
                // Crown/rotary zoom support for Navigate screen.
                .onPreRotaryScrollEvent { event ->
                    if (overlayOwnsRotary) return@onPreRotaryScrollEvent false
                    if (!crownZoomEnabled) return@onPreRotaryScrollEvent false
                    val delta = event.verticalScrollPixels
                    if (!delta.isFinite() || delta == 0f) return@onPreRotaryScrollEvent false

                    if (
                        rotaryScrollAccumulator != 0f &&
                        (rotaryScrollAccumulator > 0f) != (delta > 0f)
                    ) {
                        rotaryScrollAccumulator = 0f
                    }

                    rotaryScrollAccumulator += delta

                    // Keep the threshold low enough that a single crown detent feels immediate.
                    val thresholdPx = 24f
                    val positiveStep = if (crownZoomInverted) +1 else -1
                    val negativeStep = -positiveStep
                    var consumed = false

                    while (rotaryScrollAccumulator >= thresholdPx) {
                        consumed = applyMapZoomStep(step = positiveStep) || consumed
                        rotaryScrollAccumulator -= thresholdPx
                    }
                    while (rotaryScrollAccumulator <= -thresholdPx) {
                        consumed = applyMapZoomStep(step = negativeStep) || consumed
                        rotaryScrollAccumulator += thresholdPx
                    }

                    if (consumed) {
                        true
                    } else {
                        val pendingStep =
                            when {
                                rotaryScrollAccumulator > 0f -> positiveStep
                                rotaryScrollAccumulator < 0f -> negativeStep
                                else -> 0
                            }
                        pendingStep != 0 && canApplyMapZoomStep(step = pendingStep)
                    }
                }.focusRequester(focusRequester)
                .focusable(),
    ) {
        if (hasLocationPermission && mapView != null) {
            DisposableEffect(mapView, zoomMin, zoomMax) {
                // Broaden first so changing between non-overlapping ranges never violates
                // Mapsforge's requirement that the new minimum remains below the old maximum.
                mapView.setZoomLevelMin(MAP_ZOOM_MIN_LEVEL.toByte())
                mapView.setZoomLevelMax(MAP_ZOOM_MAX_LEVEL.toByte())
                mapView.setZoomLevelMin(zoomMin.toByte())
                mapView.setZoomLevelMax(zoomMax.toByte())
                val currentZoom =
                    mapView.model.mapViewPosition.zoomLevel
                        .toInt()
                val boundedZoom = currentZoom.coerceIn(zoomMin, zoomMax)
                if (boundedZoom != currentZoom) {
                    mapView.model.mapViewPosition.setZoomLevel(boundedZoom.toByte(), false)
                }
                onDispose { }
            }

            // Gesture exclusion: update only when size changes
            DisposableEffect(mapView) {
                var lastW = -1
                var lastH = -1
                val listener =
                    View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        val w = v.width
                        val h = v.height
                        if (w <= 0 || h <= 0) return@OnLayoutChangeListener
                        if (w == lastW && h == lastH) return@OnLayoutChangeListener
                        lastW = w
                        lastH = h

                        val density = v.resources.displayMetrics.density
                        val leftStripPx = (gestureExclusionStripDp * density).toInt().coerceAtMost(w)
                        ViewCompat.setSystemGestureExclusionRects(
                            v,
                            listOf(Rect(0, 0, leftStripPx, h)),
                        )
                    }
                mapView.addOnLayoutChangeListener(listener)
                onDispose { mapView.removeOnLayoutChangeListener(listener) }
            }

            // Sync Map Zoom -> VM (deduped)
            DisposableEffect(mapView) {
                var lastZoom =
                    mapView.model.mapViewPosition.zoomLevel
                        .toInt()
                var lastCenter = mapView.model.mapViewPosition.center
                val observer =
                    Observer {
                        val newCenter = mapView.model.mapViewPosition.center
                        val newZoom =
                            mapView.model.mapViewPosition.zoomLevel
                                .toInt()
                        val zoomChanged = newZoom != lastZoom
                        val centerChanged =
                            newCenter.latitude != lastCenter.latitude ||
                                newCenter.longitude != lastCenter.longitude
                        if (zoomChanged) {
                            lastZoom = newZoom
                            latestOnZoomLevelChange.value(newZoom)
                        }
                        if (centerChanged || zoomChanged) {
                            lastCenter = newCenter
                            val routeToolOverlayRefreshed =
                                shouldRefreshRouteToolOverlayForViewport(
                                    routeToolSessionActive = latestRouteToolSession.value != null,
                                )
                            if (routeToolOverlayRefreshed) {
                                routeToolOverlayRevision++
                            }
                            panTelemetry.onViewportChanged(routeToolOverlayRefreshed)
                            latestOnViewportChanged.value(newCenter, newZoom)
                            if (pendingDoubleTapPanningCheck) {
                                scheduleDoubleTapPanningCheck()
                            }
                        }
                    }
                mapView.model.mapViewPosition.addObserver(observer)
                onDispose { mapView.model.mapViewPosition.removeObserver(observer) }
            }

            var isDragging by remember { mutableStateOf(false) }
            var isMultiTouchGestureSuppressed by remember { mutableStateOf(false) }
            var lastMapSurfaceTelemetrySignature by remember { mutableStateOf<String?>(null) }

            if (suppressMapRenderingForGuidance) {
                DisposableEffect(mapView) {
                    (mapView.parent as? ViewGroup)?.removeView(mapView)
                    MapLayerMutationCoordinator.setGestureActive(mapView, false)
                    onDispose { }
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                )
            } else {
                AndroidView(
                    factory = {
                        FrameLayout(context).apply {
                            clipChildren = true
                            clipToPadding = true
                            (mapView.parent as? ViewGroup)?.removeView(mapView)
                            addView(
                                mapView.apply {
                                    setOnTouchListener { v, event ->
                                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                            isMultiTouchGestureSuppressed = false
                                            MapLayerMutationCoordinator.setGestureActive(mapView, true)
                                        }
                                        if (
                                            event.pointerCount > 1 ||
                                            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                                            event.actionMasked == MotionEvent.ACTION_POINTER_UP
                                        ) {
                                            if (!isMultiTouchGestureSuppressed) {
                                                panTelemetry.onPanFinished(
                                                    navMode = latestNavMode.value,
                                                    reason = "multi_touch",
                                                    zoomLevel =
                                                        mapView.model.mapViewPosition.zoomLevel
                                                            .toInt(),
                                                )
                                                isMultiTouchGestureSuppressed = true
                                                MotionEvent.obtain(event).run {
                                                    action = MotionEvent.ACTION_CANCEL
                                                    v.onTouchEvent(this)
                                                    recycle()
                                                }
                                            }
                                            isDragging = false
                                            v.parent?.requestDisallowInterceptTouchEvent(true)
                                            return@setOnTouchListener true
                                        }
                                        if (isMultiTouchGestureSuppressed) {
                                            if (
                                                event.actionMasked == MotionEvent.ACTION_UP ||
                                                event.actionMasked == MotionEvent.ACTION_CANCEL
                                            ) {
                                                isMultiTouchGestureSuppressed = false
                                                MapLayerMutationCoordinator.setGestureActive(mapView, false)
                                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                            }
                                            return@setOnTouchListener true
                                        }

                                        doubleTapGestureDetector.onTouchEvent(event)
                                        if (latestInspectionEnabled.value) {
                                            gestureDetector.onTouchEvent(event)
                                        }

                                        // Reliable panning detection (MapView gets these events).
                                        when (event.actionMasked) {
                                            MotionEvent.ACTION_MOVE -> {
                                                if (!isDragging) {
                                                    isDragging = true
                                                    panTelemetry.onPanStarted(
                                                        navMode = latestNavMode.value,
                                                        routeToolSessionActive =
                                                            latestRouteToolSession.value != null,
                                                        zoomLevel =
                                                            mapView.model.mapViewPosition.zoomLevel
                                                                .toInt(),
                                                    )
                                                }
                                                panTelemetry.onInputMove()
                                                if (latestNavMode.value != NavMode.PANNING) {
                                                    latestOnUserPanStarted.value.invoke()
                                                }
                                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                            }
                                            MotionEvent.ACTION_UP,
                                            MotionEvent.ACTION_CANCEL,
                                            -> {
                                                isDragging = false
                                                panTelemetry.onPanFinished(
                                                    navMode = latestNavMode.value,
                                                    reason =
                                                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                                                            "touch_up"
                                                        } else {
                                                            "cancel"
                                                        },
                                                    zoomLevel =
                                                        mapView.model.mapViewPosition.zoomLevel
                                                            .toInt(),
                                                )
                                                MapLayerMutationCoordinator.setGestureActive(mapView, false)
                                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                            }
                                            else -> Unit
                                        }

                                        false // let Mapsforge handle pan/zoom
                                    }
                                },
                                navigationMapViewLayoutParams(
                                    expandedMapSurfaceEnabled = expandedMapSurfaceEnabled,
                                    expandedMapSurfaceHeightPx = expandedMapSurfaceHeightPx,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { container ->
                        if (mapView.parent !== container) {
                            (mapView.parent as? ViewGroup)?.removeView(mapView)
                            container.removeAllViews()
                            container.addView(mapView)
                        }
                        val targetLayoutParams =
                            navigationMapViewLayoutParams(
                                expandedMapSurfaceEnabled = expandedMapSurfaceEnabled,
                                expandedMapSurfaceHeightPx = expandedMapSurfaceHeightPx,
                            )
                        val currentLayoutParams = mapView.layoutParams as? FrameLayout.LayoutParams
                        val layoutParamsNeedUpdate =
                            currentLayoutParams?.let {
                                it.width != targetLayoutParams.width ||
                                    it.height != targetLayoutParams.height ||
                                    it.gravity != targetLayoutParams.gravity
                            } ?: true
                        if (layoutParamsNeedUpdate) {
                            mapView.layoutParams = targetLayoutParams
                            mapView.requestLayout()
                        }
                        val telemetryState =
                            NavigationMapSurfaceTelemetryState(
                                visibleMapSizePx = visibleMapSizePx,
                                navigationMarkerAnchorMode = navigationMarkerAnchorMode,
                                expandedMapSurfaceEnabled = expandedMapSurfaceEnabled,
                                targetMapSurfaceHeightPx = targetLayoutParams.height,
                            )
                        logNavigationMapSurfaceTelemetryIfChanged(
                            mapView = mapView,
                            visibleContainer = container,
                            state = telemetryState,
                            lastSignature = lastMapSurfaceTelemetrySignature,
                        ) { signature ->
                            lastMapSurfaceTelemetrySignature = signature
                        }
                        if (layoutParamsNeedUpdate) {
                            container.post {
                                logNavigationMapSurfaceTelemetryIfChanged(
                                    mapView = mapView,
                                    visibleContainer = container,
                                    state = telemetryState,
                                    lastSignature = lastMapSurfaceTelemetrySignature,
                                ) { signature ->
                                    lastMapSurfaceTelemetrySignature = signature
                                }
                            }
                        }
                        val mapViewReady =
                            mapView.isAttachedToWindow &&
                                mapView.width > 0 &&
                                mapView.height > 0 &&
                                mapView.hasWindowFocus()
                        if (mapViewReady) {
                            onMapViewReadyForRendering()
                        } else if (!mapView.isAttachedToWindow || mapView.width <= 0 || mapView.height <= 0) {
                            mapView.post { onMapViewReadyForRendering() }
                        }
                    },
                )
            }

            CompositionLocalProvider(LocalFullscreenPopupTimeFormat provides navigateTimeFormat) {
                NavigateOverlaysLayer(
                    mapView = mapView,
                    mapAppearanceApplyInProgress = mapAppearanceApplyInProgress,
                    slopeOverlayToggleEnabled = slopeOverlayToggleEnabled,
                    slopeOverlayEnabled = slopeOverlayEnabled,
                    slopeOverlayProcessing = slopeOverlayProcessing,
                    slopeOverlayProgressPercent = slopeOverlayProgressPercent,
                    navMode = navMode,
                    screenSize = screenSize,
                    isMetric = isMetric,
                    liveElevationEnabled = liveElevationEnabled,
                    liveElevationLabel = liveElevationLabel,
                    liveDistanceEnabled = liveDistanceEnabled,
                    liveDistanceLabel = liveDistanceLabel,
                    zoomLabelTopPadding = zoomLabelTopPadding,
                    liveElevationIconSize = liveElevationIconSize,
                    northIndicatorMode = northIndicatorMode,
                    mapRotationDeg = mapRotationDeg,
                    navigationMarkerAnchorMode = navigationMarkerAnchorMode,
                    compassHeadingDeg = compassHeadingDeg,
                    northIndicatorButtonSize = northIndicatorButtonSize,
                    northIndicatorIconSize = northIndicatorIconSize,
                    showZoomPlusButton = showZoomPlusButton,
                    showZoomMinusButton = showZoomMinusButton,
                    triggerHaptic = triggerHaptic,
                    onZoomStep = ::applyMapZoomStep,
                    zoomButtonSize = zoomButtonSize,
                    zoomIconSize = zoomIconSize,
                    scaleIndicator = scaleIndicator,
                    showScaleBar = showScaleBar,
                    zoomScaleBarWidth = zoomScaleBarWidth,
                    poiTapMessage = poiTapMessage,
                    poiTapCanExpand = poiTapPopup?.canExpand == true,
                    poiTapCanCreateGpx = poiTapMarker != null,
                    poiTapExpanded = poiTapPopupExpanded,
                    onPoiTapExpandToggle = {
                        poiTapPopupExpanded = !poiTapPopupExpanded
                    },
                    onPoiTapCreateGpx = {
                        poiTapMarker?.let { marker ->
                            onPoiTapCreateGpx(marker)
                            poiTapMarker = null
                            poiTapPopup = null
                            poiTapPopupExpanded = false
                            poiTapPopupScrollInProgress = false
                        }
                    },
                    onPoiTapDismiss = {
                        poiTapMarker = null
                        poiTapPopup = null
                        poiTapPopupExpanded = false
                        poiTapPopupScrollInProgress = false
                    },
                    onPoiTapScrollInProgressChanged = { isScrolling ->
                        poiTapPopupScrollInProgress = isScrolling
                    },
                    onMenuClick = onMenuClick,
                    sideButtonEdgePadding = sideButtonEdgePadding,
                    sideButtonSize = sideButtonSize,
                    sideButtonIconSize = sideButtonIconSize,
                    shortcutTrayExpanded = shortcutTrayExpanded,
                    routeToolModeActive = routeToolModeActive,
                    onShortcutTrayToggle = onShortcutTrayToggle,
                    onShortcutTrayDismiss = onShortcutTrayDismiss,
                    onGpxToolsClick = onOpenGpxTools,
                    onCreatePoiClick = onStartPoiCreation,
                    keepAppOpen = keepAppOpen,
                    onKeepAppOpenToggle = onKeepAppOpenToggle,
                    traceRecordingState = traceRecordingState,
                    recordingDashboardMetricSlots = recordingDashboardMetricSlots,
                    turnByTurnDashboardMetricSlots = turnByTurnDashboardMetricSlots,
                    userWeightKg = userWeightKg,
                    backpackWeightKg = backpackWeightKg,
                    bikeWeightKg = bikeWeightKg,
                    recordingDashboardExpandRequestToken = recordingDashboardExpandRequestToken,
                    recordingActionPromptRequestToken = effectiveRecordingActionPromptRequestToken,
                    onRecordingClick = {
                        onShortcutTrayDismiss()
                        if (traceRecordingState.active || traceRecordingState.saving) {
                            expandedOverlayState.requestRecordingActionPrompt()
                        } else {
                            onStartRecording()
                        }
                    },
                    onPauseRecording = onPauseRecording,
                    onResumeRecording = onResumeRecording,
                    onFinishRecording = onFinishRecording,
                    onDiscardRecording = onDiscardRecording,
                    onRecordingMetricSelected = onRecordingMetricSelected,
                    onTurnByTurnMetricSelected = onTurnByTurnMetricSelected,
                    gpsIndicatorState = gpsIndicatorState,
                    watchGpsDegradedWarning = watchGpsDegradedWarning,
                    navButtonBottomPadding = navButtonBottomPadding,
                    navButtonSize = navButtonSize,
                    navButtonIconSize = navButtonIconSize,
                    locationMarker = locationMarker,
                    lastKnownLocation = lastKnownLocation,
                    onRecenter = onRecenter,
                    onRecenterRequested = onRecenterRequested,
                    onToggleOrientation = onToggleOrientation,
                    isOfflineMode = isOfflineMode,
                    selectingGpxPointB = selectingGpxPointB,
                    onCancelSelectingGpxPointB = onCancelSelectingGpxPointB,
                    turnByTurnGuidanceState = turnByTurnGuidanceState,
                    turnByTurnGuidancePaused = turnByTurnGuidancePaused,
                    turnByTurnVoiceGuidanceEnabled = turnByTurnVoiceGuidanceEnabled,
                    turnByTurnCompactPopupEnabled = turnByTurnCompactPopupEnabled,
                    onTurnByTurnVoiceGuidanceChange = onTurnByTurnVoiceGuidanceChange,
                    turnByTurnFullScreenExpanded = turnByTurnFullScreenExpanded,
                    recordingDashboardFullScreenExpanded = recordingDashboardFullScreenExpanded,
                    combinedGuidanceRecordingFullScreenExpanded =
                    combinedGuidanceRecordingFullScreenExpanded,
                    guideBackToRouteActive = guideBackToRouteActive,
                    showGuideBackPrompt = showGuideBackPrompt,
                    startDecisionPrompt = startDecisionPrompt,
                    onPauseTurnByTurnGuidance = onPauseTurnByTurnGuidance,
                    onResumeTurnByTurnGuidance = onResumeTurnByTurnGuidance,
                    onStopTurnByTurnGuidance = onStopTurnByTurnGuidance,
                    onTurnByTurnExpandedChange = expandedOverlayState.onTurnByTurnExpandedChange,
                    onRecordingExpandedChange = expandedOverlayState.onRecordingExpandedChange,
                    onCombinedGuidanceRecordingExpandedChange =
                        expandedOverlayState.onCombinedGuidanceRecordingExpandedChange,
                    onGuideBackToRoute = onGuideBackToRoute,
                    onDismissGuideBackPrompt = onDismissGuideBackPrompt,
                    onAcceptStartDecisionPrompt = onAcceptStartDecisionPrompt,
                    onDismissStartDecisionPrompt = onDismissStartDecisionPrompt,
                )
            }

            CenteredNavigateTimeChip(
                visible =
                    !fullScreenPopupExpanded &&
                        !shouldSuppressNavigateTime &&
                        (showNavigateTime || traceRecordingState.active || traceRecordingState.saving),
                showTime = showNavigateTime,
                timeFormat = navigateTimeFormat,
                recordingActive = traceRecordingState.active || traceRecordingState.saving,
                recordingPaused = traceRecordingState.paused,
                recordingSaving = traceRecordingState.saving,
                guidanceActive = turnByTurnGuidanceState.active,
                onTap = onRecordingTimeTap,
                onLongPress = onRecordingTimeLongPress,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            DiagnosticsDebugOverlay(
                label = markerMotionDebugOverlayLabel,
                screenSize = screenSize,
                recordingStatusChipVisible = traceRecordingState.active || traceRecordingState.saving,
                onCompassIssueNow = onCompassIssueNow,
            )

            GpsEnvironmentWarningOverlay(
                warning = gpsEnvironmentWarning,
                visible = hasLocationPermission && !isOfflineMode,
            )

            RecordingStatusMessageChip(
                message = recordingStatusMessage,
                modifier = Modifier.align(Alignment.Center),
            )

            NavigateRouteToolOverlayHost(
                routeToolSession = routeToolSession,
                activeGpxDetails = activeGpxDetails,
                mapView = mapView,
                mapRotationDeg = mapRotationDeg,
                viewportRevision = routeToolOverlayRevision,
                screenSize = screenSize,
                isMetric = isMetric,
                routeToolCreatePreview = routeToolCreatePreview,
                routeToolCreatePreviewInProgress = routeToolCreatePreviewInProgress,
                routeToolCreatePreviewMessage = routeToolCreatePreviewMessage,
                routeToolDraftConnectorPoints = routeToolDraftConnectorPoints,
                gpxTrackColor = gpxTrackColor,
                onRouteToolPickHere = onRouteToolPickHere,
                onRouteToolUndoLastPoint = onRouteToolUndoLastPoint,
                onRouteToolSaveCreatePreview = onRouteToolSaveCreatePreview,
                onRouteToolRefreshCreatePreview = onRouteToolRefreshCreatePreview,
                onCancelRouteToolMode = onCancelRouteToolMode,
                reshapePreviewInspectMode = reshapePreviewInspectMode,
                reshapePreviewPoints = reshapePreviewPoints,
                reshapePreviewTitle = reshapePreviewTitle,
                reshapePreviewInstruction = reshapePreviewInstruction,
                reshapePreviewBusy = reshapePreviewBusy,
                reshapePreviewBusyMessage = reshapePreviewBusyMessage,
                reshapePreviewMessage = reshapePreviewMessage,
                onDismissReshapePreview = onDismissReshapePreview,
                onSaveReshapePreview = onSaveReshapePreview,
                crosshairSelectionActive = crosshairSelectionActive,
                crosshairSelectionBusy = crosshairSelectionBusy,
                crosshairSelectionBusyMessage = crosshairSelectionBusyMessage,
                crosshairSelectionTitle = crosshairSelectionTitle,
                crosshairSelectionInstruction = crosshairSelectionInstruction,
                onCrosshairSelectionPickHere = onCrosshairSelectionPickHere,
                onCancelCrosshairSelection = onCancelCrosshairSelection,
                visibleMapSizePx = visibleMapSizePx,
            )
        } else {
            NavigatePermissionRequiredContent(
                sizing = sizing,
                onPermissionLaunch = onPermissionLaunch,
            )
        }
    }
}

@Composable
private fun CenteredNavigateTimeChip(
    visible: Boolean,
    showTime: Boolean,
    timeFormat: String,
    recordingActive: Boolean,
    recordingPaused: Boolean,
    recordingSaving: Boolean,
    guidanceActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val context = LocalContext.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val label =
        when {
            showTime -> formatNavigateClockTime(nowMillis, timeFormat)
            recordingSaving -> "SAVE"
            recordingPaused -> "PAUSE"
            recordingActive -> "REC"
            else -> ""
        }
    val accentColor =
        when {
            recordingSaving || recordingPaused -> Color(0xFFFFB74D)
            recordingActive -> Color(0xFFFF1744)
            guidanceActive -> MaterialTheme.colorScheme.primary
            else -> Color.White.copy(alpha = 0.82f)
        }
    val baseModifier =
        modifier
            .padding(top = 4.dp)
            .width(128.dp)
            .height(48.dp)
    Box(
        modifier =
            if (recordingActive) {
                baseModifier.pointerInput(onTap, onLongPress) {
                    detectTapGestures(
                        onPress = {
                            DebugTelemetry.log(
                                "TraceRecording",
                                "event=time_chip_touch_down x=${it.x.toInt()} y=${it.y.toInt()}",
                            )
                            tryAwaitRelease()
                        },
                        onTap = {
                            DebugTelemetry.log("TraceRecording", "event=time_chip_touch_up action=tap")
                            onTap()
                        },
                        onLongPress = {
                            DebugTelemetry.log("TraceRecording", "event=time_chip_touch_up action=long_press")
                            onLongPress()
                        },
                    )
                }
            } else {
                baseModifier
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (label.isBlank()) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .size(4.dp)
                        .background(accentColor, CircleShape),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .height(20.dp)
                        .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(percent = 50))
                        .border(1.dp, accentColor.copy(alpha = 0.96f), RoundedCornerShape(percent = 50))
                        .padding(start = if (recordingActive) 7.dp else 9.dp, end = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                cappedFontScale(maxFontScale = 1f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (recordingActive) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(4.dp)
                                        .background(accentColor, CircleShape),
                            )
                        }
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = if (recordingActive) 5.dp else 0.dp),
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 15.sp,
                                ),
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

internal fun shouldEnterPanningAfterDoubleTap(
    center: LatLong?,
    marker: LatLong?,
    thresholdMeters: Double = DOUBLE_TAP_PANNING_DISTANCE_THRESHOLD_METERS,
): Boolean {
    if (center == null || marker == null) return false
    return navigateHaversineMeters(center, marker) > thresholdMeters
}

internal fun shouldRefreshRouteToolOverlayForViewport(
    routeToolSessionActive: Boolean,
): Boolean = routeToolSessionActive

private const val DOUBLE_TAP_PANNING_DISTANCE_THRESHOLD_METERS = 4.0
private const val DOUBLE_TAP_PANNING_CHECK_DELAY_MS = 120L
