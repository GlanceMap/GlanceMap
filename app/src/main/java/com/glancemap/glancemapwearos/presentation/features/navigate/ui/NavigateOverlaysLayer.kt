package com.glancemap.glancemapwearos.presentation.features.navigate

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.ArcPaddingValues
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassHeadingReferenceDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingDashboardOverlay
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteShortcutTray
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolInlineProgressBanner
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import kotlinx.coroutines.delay
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView

@Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "LongParameterList",
)
@Composable
internal fun BoxScope.NavigateOverlaysLayer(
    mapView: MapView,
    mapAppearanceApplyInProgress: Boolean,
    slopeOverlayToggleEnabled: Boolean,
    slopeOverlayEnabled: Boolean,
    slopeOverlayProcessing: Boolean,
    slopeOverlayProgressPercent: Int?,
    navMode: NavMode,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    liveElevationEnabled: Boolean,
    liveElevationLabel: String?,
    liveDistanceEnabled: Boolean,
    liveDistanceLabel: String?,
    zoomLabelTopPadding: Dp,
    liveElevationIconSize: Dp,
    northIndicatorMode: String,
    mapRotationDeg: Float,
    navigationMarkerAnchorMode: String,
    compassHeadingDeg: Float,
    northIndicatorButtonSize: Dp,
    northIndicatorIconSize: Dp,
    showZoomPlusButton: Boolean,
    showZoomMinusButton: Boolean,
    triggerHaptic: () -> Unit,
    onZoomStep: (Int) -> Boolean,
    zoomButtonSize: Dp,
    zoomIconSize: Dp,
    scaleIndicator: ScaleIndicatorUi?,
    showScaleBar: Boolean,
    zoomScaleBarWidth: Dp,
    poiTapMessage: String?,
    poiTapCanExpand: Boolean,
    poiTapCanCreateGpx: Boolean,
    poiTapExpanded: Boolean,
    onPoiTapExpandToggle: () -> Unit,
    onPoiTapCreateGpx: () -> Unit,
    onPoiTapDismiss: () -> Unit,
    onPoiTapScrollInProgressChanged: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    sideButtonEdgePadding: Dp,
    sideButtonSize: Dp,
    sideButtonIconSize: Dp,
    shortcutTrayExpanded: Boolean,
    routeToolModeActive: Boolean,
    onShortcutTrayToggle: () -> Unit,
    onShortcutTrayDismiss: () -> Unit,
    onGpxToolsClick: () -> Unit,
    onCreatePoiClick: () -> Unit,
    keepAppOpen: Boolean,
    onKeepAppOpenToggle: () -> Unit,
    traceRecordingState: TraceRecordingUiState,
    recordingDashboardMetricSlots: List<String>,
    turnByTurnDashboardMetricSlots: List<String>,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float,
    recordingDashboardExpandRequestToken: Long,
    recordingActionPromptRequestToken: Long,
    onRecordingClick: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onFinishRecording: (String?) -> Unit,
    onDiscardRecording: () -> Unit,
    onRecordingMetricSelected: (Int, String) -> Unit,
    onTurnByTurnMetricSelected: (Int, String) -> Unit,
    gpsIndicatorState: GpsFixIndicatorState,
    watchGpsDegradedWarning: Boolean,
    navButtonBottomPadding: Dp,
    navButtonSize: Dp,
    navButtonIconSize: Dp,
    locationMarker: RotatableMarker?,
    lastKnownLocation: LatLong?,
    onRecenter: () -> Unit,
    onRecenterRequested: () -> Unit,
    onToggleOrientation: () -> Unit,
    isOfflineMode: Boolean,
    selectingGpxPointB: Boolean,
    onCancelSelectingGpxPointB: () -> Unit,
    turnByTurnGuidanceState: TurnByTurnGuidanceState,
    turnByTurnGuidancePaused: Boolean,
    turnByTurnVoiceGuidanceEnabled: Boolean,
    turnByTurnCompactPopupEnabled: Boolean,
    onTurnByTurnVoiceGuidanceChange: (Boolean) -> Unit,
    turnByTurnFullScreenExpanded: Boolean,
    recordingDashboardFullScreenExpanded: Boolean,
    combinedGuidanceRecordingFullScreenExpanded: Boolean,
    guideBackToRouteActive: Boolean,
    showGuideBackPrompt: Boolean,
    startDecisionPrompt: GuidanceDecisionPrompt?,
    onPauseTurnByTurnGuidance: () -> Unit,
    onResumeTurnByTurnGuidance: () -> Unit,
    onStopTurnByTurnGuidance: () -> Unit,
    onTurnByTurnExpandedChange: (Boolean) -> Unit,
    onRecordingExpandedChange: (Boolean) -> Unit,
    onCombinedGuidanceRecordingExpandedChange: (Boolean) -> Unit,
    onGuideBackToRoute: () -> Unit,
    onDismissGuideBackPrompt: () -> Unit,
    onAcceptStartDecisionPrompt: () -> Unit,
    onDismissStartDecisionPrompt: () -> Unit,
) {
    val context = LocalContext.current
    val headingReferenceTestActive by CompassHeadingReferenceDiagnostics.active.collectAsState()
    var liveDistanceLineStart by
        remember(mapView, locationMarker, lastKnownLocation) {
            mutableStateOf<Offset?>(null)
        }
    var showRouteCompleteRecordingPrompt by remember(traceRecordingState.startedAtMillis) {
        mutableStateOf(false)
    }
    val slopeIndicatorButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 28.dp
            WearScreenSize.MEDIUM -> 26.dp
            WearScreenSize.SMALL -> 24.dp
        }
    val slopeIndicatorToolGap =
        when (screenSize) {
            WearScreenSize.LARGE -> 5.dp
            WearScreenSize.MEDIUM -> 4.dp
            WearScreenSize.SMALL -> 3.dp
        }
    val showSlopeIndicatorAccessory = slopeOverlayToggleEnabled && slopeOverlayEnabled && slopeOverlayProcessing
    val routeShortcutAccessoryWidth =
        if (showSlopeIndicatorAccessory) {
            slopeIndicatorButtonSize + slopeIndicatorToolGap
        } else {
            0.dp
        }
    val suppressLiveMetricsForPoi = poiTapMessage != null
    val suppressGuidanceForPanning = navMode == NavMode.PANNING
    val hasTurnByTurnDecisionPrompt = startDecisionPrompt != null || showGuideBackPrompt
    val combinedGuidanceRecordingActive =
        traceRecordingState.active &&
            (turnByTurnGuidanceState.active || turnByTurnGuidancePaused) &&
            !hasTurnByTurnDecisionPrompt
    val suppressMapControlsForGuidance =
        (turnByTurnGuidanceState.active && turnByTurnFullScreenExpanded) ||
            (traceRecordingState.active && recordingDashboardFullScreenExpanded) ||
            combinedGuidanceRecordingFullScreenExpanded
    LaunchedEffect(
        turnByTurnGuidanceState.mode,
        turnByTurnGuidanceState.trackTitle,
        traceRecordingState.active,
    ) {
        if (
            turnByTurnGuidanceState.mode == GuidanceMode.FINISHED &&
            traceRecordingState.active
        ) {
            showRouteCompleteRecordingPrompt = true
            DebugTelemetry.log(
                "TurnByTurn",
                "event=route_complete guidanceAction=auto_stop recordingAction=keep_active",
            )
            onStopTurnByTurnGuidance()
        }
    }

    LaunchedEffect(shortcutTrayExpanded, routeToolModeActive, headingReferenceTestActive) {
        if (!shortcutTrayExpanded || routeToolModeActive || headingReferenceTestActive) return@LaunchedEffect
        delay(5_000L)
        if (shortcutTrayExpanded) {
            onShortcutTrayDismiss()
        }
    }

    LaunchedEffect(
        navMode,
        liveDistanceEnabled,
        locationMarker,
        lastKnownLocation,
        mapView,
        mapRotationDeg,
        navigationMarkerAnchorMode,
        suppressLiveMetricsForPoi,
    ) {
        if (navMode != NavMode.PANNING || !liveDistanceEnabled || suppressLiveMetricsForPoi) {
            liveDistanceLineStart = null
            return@LaunchedEffect
        }
        while (true) {
            val liveDistanceOrigin =
                resolveLiveDistanceOrigin(
                    currentMarkerLatLong = locationMarker?.latLong,
                    fallbackLatLong = lastKnownLocation,
                )
            liveDistanceLineStart =
                liveDistanceOrigin?.let { origin ->
                    val anchor = mapView.resolveNavigationMarkerScreenAnchor(navigationMarkerAnchorMode)
                    projectLatLongToScreenOffset(
                        mapView = mapView,
                        latLong = origin,
                        mapRotationDeg = mapRotationDeg,
                        rotationPivot = anchor,
                    )
                }
            delay(80L)
        }
    }

    PanningDistanceGuide(
        navMode = navMode,
        liveDistanceEnabled = liveDistanceEnabled && !suppressLiveMetricsForPoi,
        liveDistanceLineStart = liveDistanceLineStart,
    )

    RouteToolInlineProgressBanner(
        visible = mapAppearanceApplyInProgress,
        message = stringResource(R.string.navigation_updating_map),
        startInset = sideButtonEdgePadding + sideButtonSize + 8.dp,
        endInset = sideButtonEdgePadding + sideButtonSize + routeShortcutAccessoryWidth + 8.dp,
    )

    SlopeOverlayStatusIndicator(
        visible = slopeOverlayToggleEnabled,
        processing = slopeOverlayProcessing,
        progressPercent = slopeOverlayProgressPercent,
        enabled = slopeOverlayEnabled,
        screenSize = screenSize,
        sideButtonEdgePadding = sideButtonEdgePadding,
        sideButtonSize = sideButtonSize,
    )

    PanningLiveMetricsOverlay(
        navMode = navMode,
        liveElevationEnabled = liveElevationEnabled && !suppressLiveMetricsForPoi,
        liveElevationLabel = liveElevationLabel,
        liveDistanceEnabled = liveDistanceEnabled && !suppressLiveMetricsForPoi,
        liveDistanceLabel = liveDistanceLabel,
        zoomLabelTopPadding = zoomLabelTopPadding,
        liveElevationIconSize = liveElevationIconSize,
        navButtonBottomPadding = navButtonBottomPadding,
        navButtonSize = navButtonSize,
    )

    if (!suppressMapControlsForGuidance) {
        NorthIndicatorOverlay(
            northIndicatorMode = northIndicatorMode,
            navMode = navMode,
            mapRotationDeg = mapRotationDeg,
            compassHeadingDeg = compassHeadingDeg,
            indicatorButtonSize = northIndicatorButtonSize,
            indicatorIconSize = northIndicatorIconSize,
        )
    }

    if (!suppressMapControlsForGuidance && showZoomPlusButton) {
        CurvedLayout(
            modifier = Modifier.fillMaxSize(),
            anchor = 320f,
            anchorType = AnchorType.Center,
        ) {
            curvedComposable(
                modifier =
                    CurvedModifier.padding(
                        ArcPaddingValues(outer = sideButtonEdgePadding),
                    ),
            ) {
                IconButton(
                    onClick = { onZoomStep(1) },
                    modifier = Modifier.size(zoomButtonSize),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.7f),
                            contentColor = Color.White,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.navigation_zoom_in),
                        modifier =
                            Modifier
                                .size(zoomIconSize)
                                .rotate(40f),
                    )
                }
            }
        }
    }

    if (!suppressMapControlsForGuidance && showZoomMinusButton) {
        CurvedLayout(
            modifier = Modifier.fillMaxSize(),
            anchor = 338f,
            anchorType = AnchorType.Center,
        ) {
            curvedComposable(
                modifier =
                    CurvedModifier.padding(
                        ArcPaddingValues(outer = sideButtonEdgePadding),
                    ),
            ) {
                IconButton(
                    onClick = { onZoomStep(-1) },
                    modifier = Modifier.size(zoomButtonSize),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.7f),
                            contentColor = Color.White,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = stringResource(R.string.navigation_zoom_out),
                        modifier =
                            Modifier
                                .size(zoomIconSize)
                                .rotate(112f),
                    )
                }
            }
        }
    }

    if (!suppressMapControlsForGuidance) {
        scaleIndicator?.let { indicator ->
            val scaleFontScale = LocalDensity.current.fontScale
            val scaleTopPadding = zoomLabelTopPadding + if (scaleFontScale > 1f) 4.dp else 0.dp
            AnimatedVisibility(
                visible = showScaleBar,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(220)),
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = scaleTopPadding),
            ) {
                cappedFontScale(maxFontScale = 1f) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = indicator.label,
                            modifier =
                                Modifier
                                    .padding(top = 2.dp)
                                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                        )
                        StandardScaleBar(
                            width = zoomScaleBarWidth * indicator.widthRatio,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }

    PoiTapMessageOverlay(
        poiTapMessage = poiTapMessage,
        poiTapCanExpand = poiTapCanExpand,
        poiTapCanCreateGpx = poiTapCanCreateGpx,
        poiTapExpanded = poiTapExpanded,
        onPoiTapExpandToggle = onPoiTapExpandToggle,
        onPoiTapCreateGpx = onPoiTapCreateGpx,
        onPoiTapDismiss = onPoiTapDismiss,
        onPoiTapScrollInProgressChanged = onPoiTapScrollInProgressChanged,
        screenSize = screenSize,
        sideButtonEdgePadding = sideButtonEdgePadding,
        sideButtonSize = sideButtonSize,
        navButtonBottomPadding = navButtonBottomPadding,
        navButtonSize = navButtonSize,
    )

    GpxInspectionBSelectionPromptOverlay(
        visible = selectingGpxPointB,
        screenSize = screenSize,
        navButtonBottomPadding = navButtonBottomPadding,
        navButtonSize = navButtonSize,
        onCancel = onCancelSelectingGpxPointB,
    )

    IconButton(
        onClick = onMenuClick,
        modifier =
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = sideButtonEdgePadding)
                .size(sideButtonSize),
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.7f),
                contentColor = Color.White,
            ),
    ) {
        Icon(Icons.Default.Menu, "Menu", Modifier.size(sideButtonIconSize))
    }

    if (!suppressMapControlsForGuidance && !routeToolModeActive) {
        RouteShortcutTray(
            expanded = shortcutTrayExpanded,
            keepAppOpen = keepAppOpen,
            edgePadding = sideButtonEdgePadding,
            anchorSize = sideButtonSize,
            adjacentAccessoryWidth = routeShortcutAccessoryWidth,
            actionHeight = sideButtonSize,
            iconSize = sideButtonIconSize,
            onToggleExpanded = onShortcutTrayToggle,
            onKeepAppOpenClick = onKeepAppOpenToggle,
            onGpxToolsClick = onGpxToolsClick,
            onCreatePoiClick = onCreatePoiClick,
            recordingActive = traceRecordingState.active,
            recordingPaused = traceRecordingState.paused,
            recordingSaving = traceRecordingState.saving,
            headingReferenceTestActive = headingReferenceTestActive,
            onRecordingClick = onRecordingClick,
            onHeadingReferenceMark = { referenceHeadingDeg ->
                val result = CompassHeadingReferenceDiagnostics.recordReference(referenceHeadingDeg)
                Toast.makeText(context, result.userMessage, Toast.LENGTH_SHORT).show()
            },
        )
    }

    NavModeButtonOverlay(
        mapView = mapView,
        navMode = navMode,
        isOfflineMode = isOfflineMode,
        gpsIndicatorState = gpsIndicatorState,
        watchGpsDegradedWarning = watchGpsDegradedWarning,
        lastKnownLocation = lastKnownLocation,
        triggerHaptic = triggerHaptic,
        navButtonBottomPadding = navButtonBottomPadding,
        navButtonSize = navButtonSize,
        navButtonIconSize = navButtonIconSize,
        onRecenter = onRecenter,
        onRecenterRequested = onRecenterRequested,
        onToggleOrientation = onToggleOrientation,
        navigationMarkerAnchorMode = navigationMarkerAnchorMode,
    )

    TurnByTurnGuidanceOverlay(
        state = turnByTurnGuidanceState,
        paused = turnByTurnGuidancePaused,
        dashboardMetricSlots = turnByTurnDashboardMetricSlots,
        voiceGuidanceEnabled = turnByTurnVoiceGuidanceEnabled,
        screenSize = screenSize,
        isMetric = isMetric,
        compassHeadingDeg = compassHeadingDeg,
        guideBackToRouteActive = guideBackToRouteActive,
        showGuideBackPrompt = showGuideBackPrompt,
        startDecisionPrompt = startDecisionPrompt,
        expandRequestToken = recordingDashboardExpandRequestToken,
        actionPromptRequestToken = recordingActionPromptRequestToken,
        compactPopupEnabled = turnByTurnCompactPopupEnabled,
        suppressed =
            poiTapMessage != null ||
                suppressGuidanceForPanning ||
                recordingDashboardFullScreenExpanded ||
                combinedGuidanceRecordingActive,
        onPause = onPauseTurnByTurnGuidance,
        onResume = onResumeTurnByTurnGuidance,
        onStop = onStopTurnByTurnGuidance,
        onVoiceGuidanceChange = onTurnByTurnVoiceGuidanceChange,
        onDashboardMetricSelected = onTurnByTurnMetricSelected,
        onExpandedChange = onTurnByTurnExpandedChange,
        onGuideBackToRoute = onGuideBackToRoute,
        onDismissGuideBackPrompt = onDismissGuideBackPrompt,
        onAcceptStartDecisionPrompt = onAcceptStartDecisionPrompt,
        onDismissStartDecisionPrompt = onDismissStartDecisionPrompt,
    )

    RecordingDashboardOverlay(
        state = traceRecordingState,
        metricSlots = recordingDashboardMetricSlots,
        userWeightKg = userWeightKg,
        backpackWeightKg = backpackWeightKg,
        bikeWeightKg = bikeWeightKg,
        screenSize = screenSize,
        isMetric = isMetric,
        showRouteCompletePrompt = showRouteCompleteRecordingPrompt,
        onRouteCompletePromptDismiss = {
            showRouteCompleteRecordingPrompt = false
        },
        suppressed =
            poiTapMessage != null ||
                turnByTurnFullScreenExpanded ||
                combinedGuidanceRecordingActive,
        onPause = onPauseRecording,
        onResume = onResumeRecording,
        onStopConfirmed = onFinishRecording,
        onDiscard = onDiscardRecording,
        onMetricSelected = onRecordingMetricSelected,
        expandRequestToken = recordingDashboardExpandRequestToken,
        actionPromptRequestToken = recordingActionPromptRequestToken,
        onExpandedChange = onRecordingExpandedChange,
    )

    CombinedGuidanceRecordingOverlay(
        guidanceState = turnByTurnGuidanceState,
        guidancePaused = turnByTurnGuidancePaused,
        voiceGuidanceEnabled = turnByTurnVoiceGuidanceEnabled,
        recordingState = traceRecordingState,
        metricSlots = recordingDashboardMetricSlots,
        guidanceMetricSlots = turnByTurnDashboardMetricSlots,
        userWeightKg = userWeightKg,
        backpackWeightKg = backpackWeightKg,
        bikeWeightKg = bikeWeightKg,
        screenSize = screenSize,
        isMetric = isMetric,
        compassHeadingDeg = compassHeadingDeg,
        guideBackToRouteActive = guideBackToRouteActive,
        showGuideBackPrompt = showGuideBackPrompt,
        expandRequestToken = recordingDashboardExpandRequestToken,
        actionPromptRequestToken = recordingActionPromptRequestToken,
        compactPopupEnabled = turnByTurnCompactPopupEnabled,
        suppressed =
            poiTapMessage != null ||
                suppressGuidanceForPanning ||
                !combinedGuidanceRecordingActive,
        onPauseGuidance = onPauseTurnByTurnGuidance,
        onResumeGuidance = onResumeTurnByTurnGuidance,
        onStopGuidance = onStopTurnByTurnGuidance,
        onVoiceGuidanceChange = onTurnByTurnVoiceGuidanceChange,
        onGuideBackToRoute = onGuideBackToRoute,
        onDismissGuideBackPrompt = onDismissGuideBackPrompt,
        onPauseRecording = onPauseRecording,
        onResumeRecording = onResumeRecording,
        onFinishRecording = onFinishRecording,
        onDiscardRecording = onDiscardRecording,
        onMetricSelected = onRecordingMetricSelected,
        onGuidanceMetricSelected = onTurnByTurnMetricSelected,
        onExpandedChange = { expanded ->
            onCombinedGuidanceRecordingExpandedChange(expanded)
        },
    )
}
