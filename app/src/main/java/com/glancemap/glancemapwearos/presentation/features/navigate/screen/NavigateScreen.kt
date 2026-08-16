package com.glancemap.glancemapwearos.presentation.features.navigate

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glancemap.glancemapwearos.core.service.diagnostics.BenchmarkTrace
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.location.config.AUTO_PAUSE_GPS_INTERVAL_MS
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.effectiveAccuracyMeters
import com.glancemap.glancemapwearos.core.service.location.model.isInteractive
import com.glancemap.glancemapwearos.core.service.location.model.isNonInteractive
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeDemandReason
import com.glancemap.glancemapwearos.data.repository.PoiType
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.UserPoiRecord
import com.glancemap.glancemapwearos.domain.sensors.CompassViewModel
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxViewModel
import com.glancemap.glancemapwearos.presentation.features.maps.DemSetupBottomSheet
import com.glancemap.glancemapwearos.presentation.features.maps.DemSetupReason
import com.glancemap.glancemapwearos.presentation.features.maps.MapHolder
import com.glancemap.glancemapwearos.presentation.features.maps.MapRenderer
import com.glancemap.glancemapwearos.presentation.features.maps.MapViewModel
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.NavigateCalibrationEffects
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.NavigateCompassEffects
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.NavigateCompassWakeTelemetry
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.rememberNavigateLocationUiState
import com.glancemap.glancemapwearos.presentation.features.poi.PoiNavigateTarget
import com.glancemap.glancemapwearos.presentation.features.poi.PoiOverlayMarker
import com.glancemap.glancemapwearos.presentation.features.poi.PoiViewModel
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingViewModel
import com.glancemap.glancemapwearos.presentation.features.recording.recordedTraceSegments
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteModifyMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolCreatePreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolKind
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolLoopRetryOption
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolModifyPreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolOptions
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSaveResult
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSession
import com.glancemap.glancemapwearos.presentation.features.routetools.previewBeforeSaving
import com.glancemap.glancemapwearos.presentation.features.routetools.routeStylePresetFromSettingsValue
import com.glancemap.glancemapwearos.presentation.features.routetools.routeToolMultiPointDraftConnectorPoints
import com.glancemap.glancemapwearos.presentation.features.routetools.visibleRouteToolCreatePreview
import com.glancemap.glancemapwearos.presentation.features.routetools.withVisibleLoopDefaults
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsViewModel
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap

@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")
@Composable
fun NavigateScreen(
    mapViewModel: MapViewModel,
    gpxViewModel: GpxViewModel,
    poiViewModel: PoiViewModel,
    settingsViewModel: SettingsViewModel,
    locationViewModel: LocationViewModel,
    traceRecordingViewModel: TraceRecordingViewModel,
    isAmbient: Boolean,
    isDeviceInteractive: Boolean,
    ambientTickMs: Long,
    onNavigateTimeSuppressedChange: (Boolean) -> Unit = {},
    showNavigateTime: Boolean = true,
    navigateTimeFormat: String = SettingsRepository.TIME_FORMAT_24_HOUR,
    recordingDashboardExpandRequestToken: Long = 0L,
    recordingActionPromptRequestToken: Long = 0L,
    onRecordingTimeTap: () -> Unit = {},
    onRecordingTimeLongPress: () -> Unit = {},
    onMenuClick: () -> Unit,
    onOpenGpxToolsSettings: () -> Unit = {},
    compassViewModel: CompassViewModel = viewModel(),
    navigateViewModel: NavigateViewModel = viewModel(),
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val adaptive = rememberWearAdaptiveSpec()
    val screenSize = rememberWearScreenSize()
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    SideEffect {
        BenchmarkTrace.mark("recompose.NavigateScreen")
    }
    val lifecycleState = rememberNavigateScreenLifecycleState(isDeviceInteractive = isDeviceInteractive)
    val isScreenResumed = lifecycleState.isScreenResumed
    val lastScreenResumeElapsedMs = lifecycleState.lastScreenResumeElapsedMs
    val startRecordingWithActivityPermission =
        rememberRecordingStartWithActivityPermission(
            context = context,
            onStartRecording = traceRecordingViewModel::startRecording,
        )
    val menuClickGuardUntilElapsedMs = lifecycleState.menuClickGuardUntilElapsedMs

    // ---- UI STATE ----
    val uiState by navigateViewModel.uiState.collectAsState()
    val navMode by navigateViewModel.navMode.collectAsState()
    val showCalibrationDialog = uiState.showCalibrationDialog
    val currentZoomLevel = uiState.currentZoomLevel

    // ---- Persisted Keep App Open ----
    val keepAppOpen by settingsViewModel.keepAppOpen.collectAsState()
    val keepAppOpenTipShown by settingsViewModel.keepAppOpenTipShown.collectAsState()
    var pendingKeepAppOpen by rememberSaveable { mutableStateOf(false) }
    var showKeepAppOpenInfoDialog by rememberSaveable { mutableStateOf(false) }
    var showNotificationPermissionDialog by rememberSaveable { mutableStateOf(false) }

    // ---- PERMISSIONS ----
    val notificationPermissionState =
        rememberNotificationPermissionState(context) { granted ->
            if (granted && pendingKeepAppOpen) {
                settingsViewModel.setKeepAppOpen(true)
                pendingKeepAppOpen = false
            } else if (!granted) {
                pendingKeepAppOpen = false
            }
        }

    val locationPermissionState =
        rememberLocationPermissionState(context) { granted ->
            if (granted && pendingKeepAppOpen) {
                if (
                    notificationPermissionState.isPermissionRequired &&
                    !notificationPermissionState.hasNotificationPermission
                ) {
                    showNotificationPermissionDialog = true
                } else {
                    settingsViewModel.setKeepAppOpen(true)
                    pendingKeepAppOpen = false
                }
            } else if (!granted) {
                pendingKeepAppOpen = false
            }
        }
    val notificationPermissionPromptState =
        notificationPermissionState.copy(
            launchPermissionRequest = { showNotificationPermissionDialog = true },
        )

    val hillshadeTerrainUnavailableEvent by mapViewModel.hillshadeTerrainUnavailableEvent.collectAsState()
    DemSetupBottomSheet(
        visible = hillshadeTerrainUnavailableEvent != null,
        reason = DemSetupReason.HILL_SHADING_VISIBLE_AREA,
        onDismiss = mapViewModel::dismissHillshadeTerrainUnavailable,
    )

    // ---- SETTINGS ----
    val navigateSettings = collectNavigateSettingsState(settingsViewModel)
    with<NavigateSettingsState, Unit>(navigateSettings) {
        // ---- VMS ----
        val selectedMapPath by mapViewModel.selectedMapPath.collectAsState()
        val activeGpxDetails by gpxViewModel.activeGpxDetails.collectAsState()
        val turnByTurnGuidanceSession by gpxViewModel.turnByTurnGuidanceSession.collectAsState()
        val turnByTurnGuidancePaused by gpxViewModel.turnByTurnGuidancePaused.collectAsState()
        val activeTurnByTurnGuidanceSession =
            if (turnByTurnGuidancePaused) {
                null
            } else {
                turnByTurnGuidanceSession
            }
        val effectiveNavigationMarkerAnchorMode =
            if (turnByTurnGuidanceSession != null) {
                SettingsRepository.NAVIGATION_MARKER_ANCHOR_LOWER
            } else {
                navigationMarkerAnchorMode
            }
        val activePoiOverlaySources by poiViewModel.activeOverlaySources.collectAsState()
        val navigateTarget by poiViewModel.navigateTarget.collectAsState()
        val offlinePoiSearchUiState by poiViewModel.offlineSearchUiState.collectAsState()
        val traceRecordingState by traceRecordingViewModel.uiState.collectAsState()
        val recordingSampleIntervalSeconds by settingsViewModel.recordingSampleIntervalSeconds.collectAsState()
        val recordingScreenOffSampleIntervalSeconds by settingsViewModel.recordingScreenOffSampleIntervalSeconds.collectAsState()
        val turnByTurnGpsIntervalSeconds by settingsViewModel.turnByTurnGpsIntervalSeconds.collectAsState()
        val turnByTurnScreenOffGpsIntervalSeconds by settingsViewModel.turnByTurnScreenOffGpsIntervalSeconds.collectAsState()
        val recordingScreenOnGpsEnabled =
            recordingSampleIntervalSeconds != SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
        val recordingScreenOffGpsEnabled =
            when (recordingScreenOffSampleIntervalSeconds) {
                SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> recordingScreenOnGpsEnabled
                SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> false
                else -> true
            }
        val turnByTurnScreenOnGpsEnabled =
            turnByTurnGpsIntervalSeconds != SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
        val turnByTurnScreenOffGpsEnabled =
            when (turnByTurnScreenOffGpsIntervalSeconds) {
                SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> turnByTurnScreenOnGpsEnabled
                SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> false
                else -> true
            }
        val recordingTraceSegments =
            remember(traceRecordingState.points) {
                recordedTraceSegments(traceRecordingState.points)
                    .map { segment -> segment.map { it.latLong } }
            }
        val recordingStatusMessage =
            rememberRecordingStatusMessage(
                state = traceRecordingState,
                traceRecordingViewModel = traceRecordingViewModel,
            )

        val mapZoomState =
            rememberNavigateMapZoomState(
                configuration = configuration,
                density = density,
                zoomDefaultScaleMeters = zoomDefaultScaleMeters,
                zoomMinScaleMeters = zoomMinScaleMeters,
                zoomMaxScaleMeters = zoomMaxScaleMeters,
            )
        val zoomDefault = mapZoomState.default
        val zoomMin = mapZoomState.min
        val zoomMax = mapZoomState.max

        // Inspection UI state
        val inspectionUiState by gpxViewModel.inspectionUiState.collectAsState()

        // A/B marker points
        val selectedPointA by gpxViewModel.selectedPointA.collectAsState()
        val selectedPointB by gpxViewModel.selectedPointB.collectAsState()
        val selectingGpxPointB by gpxViewModel.selectingPointB.collectAsState()
        var shortcutTrayExpanded by rememberSaveable { mutableStateOf(false) }
        var showRouteToolsPanel by rememberSaveable { mutableStateOf(false) }
        var routeToolOptions by rememberSaveable(stateSaver = routeToolOptionsSaver) {
            mutableStateOf(RouteToolOptions())
        }
        var routeToolSession by rememberSaveable(stateSaver = routeToolSessionSaver) {
            mutableStateOf<RouteToolSession?>(null)
        }
        var poiCreationSelectionActive by rememberSaveable { mutableStateOf(false) }
        var completedRouteToolDraft by remember { mutableStateOf<RouteToolSession?>(null) }
        var routeToolExecutionInProgress by remember { mutableStateOf(false) }
        var routeToolExecutionStatus by remember { mutableStateOf<String?>(null) }
        var routeToolExecutionMessage by remember { mutableStateOf<String?>(null) }
        var routeToolLoopRetryOptions by remember { mutableStateOf<List<RouteToolLoopRetryOption>>(emptyList()) }
        var routeToolResult by remember { mutableStateOf<RouteToolSaveResult?>(null) }
        var routeToolRenameInProgress by remember { mutableStateOf(false) }
        var routeToolRenameError by remember { mutableStateOf<String?>(null) }
        var routeToolPreview by remember { mutableStateOf<RouteToolModifyPreview?>(null) }
        var routeToolCreatePreview by remember { mutableStateOf<RouteToolCreatePreview?>(null) }
        var routeToolCreatePreviewInProgress by remember { mutableStateOf(false) }
        var routeToolCreatePreviewMessage by remember { mutableStateOf<String?>(null) }
        var routeToolPreflightMessage by remember { mutableStateOf<String?>(null) }
        var createdPoiCreateInProgress by remember { mutableStateOf(false) }
        var createdPoiPendingRename by remember { mutableStateOf<UserPoiRecord?>(null) }
        var showCreatedPoiRenameDialog by remember { mutableStateOf(false) }
        var createdPoiRenameInProgress by remember { mutableStateOf(false) }
        var createdPoiRenameError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        // ---- Should track location? ----
        val runtimeState =
            rememberNavigateRuntimeState(
                isAmbient = isAmbient,
                isDeviceInteractive = isDeviceInteractive,
                isScreenResumed = isScreenResumed,
                hasLocationPermission = locationPermissionState.hasLocationPermission,
                offlineMode = offlineMode,
                generalGpsInAmbient = keepGpsInAmbient,
                traceRecordingState = traceRecordingState,
                recordingScreenOnGpsEnabled = recordingScreenOnGpsEnabled,
                recordingScreenOffGpsEnabled = recordingScreenOffGpsEnabled,
                turnByTurnScreenOnGpsEnabled = turnByTurnScreenOnGpsEnabled,
                turnByTurnScreenOffGpsEnabled = turnByTurnScreenOffGpsEnabled,
                turnByTurnActive = turnByTurnGuidanceSession != null,
                turnByTurnPaused = turnByTurnGuidancePaused,
                turnByTurnGpsInAmbient = turnByTurnScreenOffGpsEnabled,
                locationViewModel = locationViewModel,
            )
        val screenState = runtimeState.screenState
        val shouldTrackLocation = runtimeState.shouldTrackLocation
        val configuredMarkerGpsIntervalMs =
            remember(
                runtimeState.reason,
                screenState,
                recordingSampleIntervalSeconds,
                recordingScreenOffSampleIntervalSeconds,
                turnByTurnGpsIntervalSeconds,
                turnByTurnScreenOffGpsIntervalSeconds,
            ) {
                expectedMarkerGpsIntervalMs(
                    runtimeReason = runtimeState.reason,
                    screenState = screenState,
                    recordingSampleIntervalSeconds = recordingSampleIntervalSeconds,
                    recordingScreenOffSampleIntervalSeconds = recordingScreenOffSampleIntervalSeconds,
                    turnByTurnGpsIntervalSeconds = turnByTurnGpsIntervalSeconds,
                    turnByTurnScreenOffGpsIntervalSeconds = turnByTurnScreenOffGpsIntervalSeconds,
                )
            }
        val serviceEffectiveGpsIntervalMs by locationViewModel.effectiveGpsIntervalMs.collectAsState()
        val expectedMarkerGpsIntervalMs =
            resolveMarkerGpsIntervalMs(
                serviceEffectiveIntervalMs = serviceEffectiveGpsIntervalMs,
                configuredIntervalMs = configuredMarkerGpsIntervalMs,
            )
        val effectiveNavMode = if (offlineMode || navigateTarget != null) NavMode.PANNING else navMode
        // ---- Heading + Accuracy ----
        val compassUiState =
            rememberNavigateCompassUiState(
                compassViewModel = compassViewModel,
                screenState = screenState,
                isScreenResumed = isScreenResumed,
                offlineMode = offlineMode,
                northReferenceMode = northReferenceMode,
                compassProviderMode = compassProviderMode,
                headingSourceMode = headingSourceMode,
                navigationMarkerStyleSetting = navigationMarkerStyleSetting,
                compassConeAccuracyColorsEnabled = compassConeAccuracyColorsEnabled,
                density = density,
                screenSize = screenSize,
            )
        val compassRenderState = compassUiState.renderState
        val compassAccuracy = compassUiState.accuracy
        val magneticInterference = compassUiState.magneticInterference
        val selectedCompassProviderType = compassUiState.providerType
        val navigationMarkerStyle = compassUiState.markerStyle
        val navigationMarkerSizePx = compassUiState.markerSizePx
        val showCompassConeOverlay = compassUiState.showCompassConeOverlay
        val compassConeBaseSizePx = compassUiState.coneBaseSizePx
        val compassConeQuality = compassUiState.coneQuality
        val compassConeHeadingErrorDeg = compassUiState.coneHeadingErrorDeg

        // ---- MAP OBJECTS ----
        val mapHolder: MapHolder =
            remember(zoomDefault, zoomMin, zoomMax) {
                mapViewModel.getOrCreateMapHolder(
                    context = context,
                    zoomDefault = zoomDefault,
                    zoomMin = zoomMin,
                    zoomMax = zoomMax,
                )
            }

        LaunchedEffect(mapHolder) {
            mapViewModel.setMapRenderer(mapHolder.renderer)
        }

        LaunchedEffect(mapHolder, selectedMapPath) {
            val mapView = mapHolder.mapView
            val center = mapView.model.mapViewPosition.center
            mapZoomState.updateReference(center, mapView.width)
        }

        LaunchedEffect(mapHolder, northReferenceMode) {
            if (northReferenceMode != SettingsRepository.NORTH_REFERENCE_TRUE) return@LaunchedEffect
            val center = mapHolder.mapView.model.mapViewPosition.center
            compassViewModel.primeDeclinationFromApproximateLocation(
                latitude = center.latitude,
                longitude = center.longitude,
            )
        }

        val navigationMarkerBitmap =
            remember(navigationMarkerStyle, navigationMarkerSizePx) {
                val bitmap =
                    createNavigationMarkerBitmap(
                        style = navigationMarkerStyle,
                        sizePx = navigationMarkerSizePx,
                    )
                AndroidBitmap(bitmap)
            }

        NavigateCompassEffects(
            compassViewModel = compassViewModel,
            compassProviderType = selectedCompassProviderType,
            screenState = screenState,
            isOfflineMode = offlineMode,
        )

        // In follow modes, keep user location centered at all times.
        // autoRecenterEnabled only controls whether we exit panning automatically.
        val shouldFollowPosition = effectiveNavMode != NavMode.PANNING

        // Ensure zoom initialised once
        LaunchedEffect(zoomDefault) {
            navigateViewModel.initZoom(zoomDefault)
        }

        NavigateCalibrationEffects(
            compassViewModel = compassViewModel,
            compassProviderType = selectedCompassProviderType,
            compassAccuracy = compassAccuracy,
            magneticInterference = magneticInterference,
            navMode = effectiveNavMode,
            isAmbient = isAmbient,
            promptForCalibration = promptForCalibration,
            showCalibrationDialog = showCalibrationDialog,
            onShowCalibrationDialog = { navigateViewModel.showCalibrationDialog() },
            onHideCalibrationDialog = { navigateViewModel.hideCalibrationDialog() },
            onApplyRecalibration = { compassViewModel.recalibrate() },
            onRecalibrationSucceeded = compassUiState.onCalibrationSucceeded,
        )

        val screenActions =
            rememberNavigateScreenActions(
                context = context,
                settingsViewModel = settingsViewModel,
                locationPermissionState = locationPermissionState,
                notificationPermissionState = notificationPermissionPromptState,
                keepAppOpen = keepAppOpen,
                keepAppOpenTipShown = keepAppOpenTipShown,
                offlineMode = offlineMode,
                setPendingKeepAppOpen = { pendingKeepAppOpen = it },
                setShowKeepAppOpenInfoDialog = { showKeepAppOpenInfoDialog = it },
                setShortcutTrayExpanded = { shortcutTrayExpanded = it },
                isShortcutTrayExpanded = shortcutTrayExpanded,
            )

        // ---- Auto-recenter timer ----
        LaunchedEffect(effectiveNavMode, autoRecenterEnabled, autoRecenterDelay, offlineMode) {
            if (!offlineMode && effectiveNavMode == NavMode.PANNING && autoRecenterEnabled) {
                delay(autoRecenterDelay.toLong() * 1000L)
                navigateViewModel.onRecenterRequested()
            }
        }

        if (isAmbient) {
            AmbientScreen(
                ambientTick = ambientTickMs,
                timeFormat = navigateTimeFormat,
            )
            return
        }

        var pendingPoiFocusTarget by remember { mutableStateOf<PoiNavigateTarget?>(null) }
        val markerMotionDebugOverlayLabel =
            rememberMarkerMotionDebugOverlayLabel(
                gpsDebugTelemetry = gpsDebugTelemetry,
                gpsDebugTelemetryPopupEnabled = gpsDebugTelemetryPopupEnabled,
                offlineMode = offlineMode,
            )

        val mapView = mapHolder.mapView

        LaunchedEffect(navigateTarget, mapView, zoomMin, zoomMax) {
            val target = navigateTarget ?: return@LaunchedEffect
            navigateViewModel.onUserPanStarted()
            mapView.setCenter(LatLong(target.lat, target.lon))
            val focusZoom =
                poiFocusZoomLevel(
                    mapView = mapView,
                    latitude = target.lat,
                    minZoom = zoomMin,
                    maxZoom = zoomMax,
                )
            mapView.model.mapViewPosition.setZoomLevel(focusZoom.toByte(), false)
            navigateViewModel.onZoomChanged(focusZoom)
            pendingPoiFocusTarget = target
            poiViewModel.consumeNavigateTarget()
        }

        val locationUiState =
            rememberNavigateLocationUiState(
                mapView = mapView,
                locationViewModel = locationViewModel,
                compassViewModel = compassViewModel,
                navigateViewModel = navigateViewModel,
                shouldTrackLocation = shouldTrackLocation,
                shouldFollowPosition = shouldFollowPosition,
                screenState = screenState,
                expectedGpsIntervalMs = expectedMarkerGpsIntervalMs,
                isBikeActivityProfile = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE,
                navigationMarkerBitmap = navigationMarkerBitmap,
                suppressLocationMarker = offlineMode,
                navigationMarkerAnchorMode = effectiveNavigationMarkerAnchorMode,
            )

        val locationMarker = locationUiState.locationMarker
        val gpsIndicatorState = locationUiState.gpsIndicatorState
        val effectiveGpsIndicatorState =
            if (offlineMode) {
                GpsFixIndicatorState.UNAVAILABLE
            } else {
                gpsIndicatorState
            }
        val gpsSignalSnapshot by locationViewModel.gpsSignalSnapshot.collectAsState()
        LaunchedEffect(routeToolPreflightMessage, gpsSignalSnapshot.lastFixFresh) {
            if (routeToolPreflightMessage == "Waiting for GPS" && gpsSignalSnapshot.lastFixFresh) {
                routeToolPreflightMessage = null
            }
        }
        val rawCurrentLocation by locationViewModel.currentLocation.collectAsState()
        val gpsFixFreshForAccuracyCircle =
            gpsSignalSnapshot.isLocationAvailable &&
                gpsSignalSnapshot.lastFixElapsedRealtimeMs > 0L &&
                gpsSignalSnapshot.lastFixAgeMs in 0..gpsSignalSnapshot.lastFixFreshMaxAgeMs
        val watchGpsDegradedWarning = locationUiState.watchGpsDegradedWarning
        val gpsEnvironmentWarning = locationUiState.gpsEnvironmentWarning
        val mapAppearanceApplyInProgress by mapViewModel.mapAppearanceApplyInProgress.collectAsState()
        val slopeOverlayToggleEnabled by mapViewModel.reliefOverlayToggleEnabled.collectAsState()
        var slopeOverlayState by remember {
            mutableStateOf(
                MapRenderer.ReliefOverlayState(
                    enabled = false,
                    processing = false,
                    progressPercent = null,
                ),
            )
        }

        DisposableEffect(mapHolder) {
            val listener: (MapRenderer.ReliefOverlayState) -> Unit = { state ->
                slopeOverlayState = state
            }
            mapHolder.renderer.addReliefOverlayStateListener(listener)
            onDispose {
                mapHolder.renderer.removeReliefOverlayStateListener(listener)
            }
        }

        // All overlays + popups + yellow A/B markers
        var renderedMapRotationDeg by remember { mutableFloatStateOf(0f) }
        var renderedCompassHeadingDeg by
            remember {
                mutableFloatStateOf(
                    resolveNavigateInitialRenderedHeadingDeg(
                        renderState = compassRenderState,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    ),
                )
            }
        var visiblePoiMarkers by remember { mutableStateOf<List<PoiOverlayMarker>>(emptyList()) }
        val displayedRouteToolCreatePreview =
            visibleRouteToolCreatePreview(
                session = routeToolSession,
                createPreview = routeToolCreatePreview,
                createPreviewInProgress = routeToolCreatePreviewInProgress,
            )
        val routeToolDraftConnectorPoints =
            routeToolMultiPointDraftConnectorPoints(
                session = routeToolSession,
                visibleCreatePreview = displayedRouteToolCreatePreview,
                createPreviewInProgress = routeToolCreatePreviewInProgress,
            )
        MapOverlays(
            mapHolder = mapHolder,
            activeGpxDetails = activeGpxDetails,
            routeToolPreviewPoints =
                routeToolPreview?.previewPoints
                    ?: displayedRouteToolCreatePreview?.previewPoints
                    ?: emptyList(),
            recordingTraceSegments = recordingTraceSegments,
            recordingTraceFollowsMarker = traceRecordingState.active && !traceRecordingState.paused,
            routeToolCreatePreviewActive = displayedRouteToolCreatePreview != null,
            routeToolDraftPoints = routeToolDraftConnectorPoints,
            poiViewModel = poiViewModel,
            activePoiOverlaySources = activePoiOverlaySources,
            poiMarkerSizePx = poiIconSizePx,
            poiMarkerStyle = poiMarkerStyle,
            gpxTrackColor = gpxTrackColor,
            gpxTrackColorMode = gpxTrackColorMode,
            gpxTrackWidth = gpxTrackWidth,
            gpxTrackOpacityPercent = gpxTrackOpacityPercent,
            gpxTrackDirectionArrowsEnabled = gpxTrackDirectionArrowsEnabled,
            compassRenderStateFlow = compassViewModel.renderState,
            compassInteractive = isScreenResumed && screenState.isInteractive && !offlineMode,
            navMode = effectiveNavMode,
            forceNorthUpInPanning = offlineMode,
            showRealMarkerInCompassMode = true,
            showCompassConeOverlay = showCompassConeOverlay,
            compassConeBaseSizePx = compassConeBaseSizePx,
            compassQuality = compassConeQuality,
            compassHeadingErrorDeg = compassConeHeadingErrorDeg,
            gpsAccuracyCircleEnabled = gpsAccuracyCircleEnabled && !offlineMode,
            gpsFixAccuracyM = gpsSignalSnapshot.effectiveAccuracyMeters(),
            gpsFixFresh = gpsFixFreshForAccuracyCircle,
            gpsFixSpeedMps = locationUiState.lastFixSpeedMps,
            gpsFixBearingDeg = locationUiState.lastFixBearingDeg,
            renderedHeadingDeg = renderedCompassHeadingDeg,
            locationMarker = locationMarker,
            navigationMarkerAnchorMode = effectiveNavigationMarkerAnchorMode,
            inspectionUiState = inspectionUiState,
            selectedPointA = selectedPointA,
            selectedPointB = selectedPointB,
            onDismissInspection = { gpxViewModel.dismissInspection() },
            onStartSelectB = { gpxViewModel.startSelectingB() },
            isMetric = isMetric,
            onRenderedHeadingChanged = { renderedCompassHeadingDeg = it },
            onRenderedMapRotationChanged = { renderedMapRotationDeg = it },
            onPoiMarkersSnapshotChanged = { markers -> visiblePoiMarkers = markers },
        )
        NavigateCompassWakeTelemetry(
            isScreenResumed = isScreenResumed,
            screenState = screenState,
            isOfflineMode = offlineMode,
            renderState = compassRenderState,
            renderedHeadingDeg = renderedCompassHeadingDeg,
            renderedMapRotationDeg = renderedMapRotationDeg,
        )

        NavigateBackHandler(
            createdPoiCreateInProgress = createdPoiCreateInProgress,
            completedRouteToolDraftActive = completedRouteToolDraft != null,
            routeToolExecutionInProgress = routeToolExecutionInProgress,
            routeToolSessionActive = routeToolSession != null,
            showCreatedPoiRenameDialog = showCreatedPoiRenameDialog,
            createdPoiRenameInProgress = createdPoiRenameInProgress,
            poiCreationSelectionActive = poiCreationSelectionActive,
            showRouteToolsPanel = showRouteToolsPanel,
            shortcutTrayExpanded = shortcutTrayExpanded,
            backButtonExitsNavigation = backButtonExitsNavigation,
            onDismissCompletedRouteToolDraft = {
                completedRouteToolDraft = null
                routeToolPreview = null
            },
            onCancelRouteToolSession = {
                routeToolSession = null
                routeToolCreatePreview = null
                routeToolCreatePreviewMessage = null
                routeToolCreatePreviewInProgress = false
            },
            onDismissCreatedPoiRename = {
                showCreatedPoiRenameDialog = false
                createdPoiPendingRename = null
                createdPoiRenameError = null
                poiCreationSelectionActive = false
            },
            onCancelPoiCreation = {
                poiCreationSelectionActive = false
            },
            onDismissRouteToolsPanel = {
                showRouteToolsPanel = false
                routeToolPreview = null
                routeToolCreatePreview = null
                routeToolCreatePreviewMessage = null
                routeToolCreatePreviewInProgress = false
                poiViewModel.clearOfflinePoiSearch()
            },
            onDismissShortcutTray = { shortcutTrayExpanded = false },
            onMenuClick = onMenuClick,
        )

        val reshapePreviewInspectDraft =
            completedRouteToolDraft?.takeIf { draft ->
                draft.options.toolKind == RouteToolKind.MODIFY &&
                    draft.options.modifyMode.previewBeforeSaving &&
                    routeToolPreview != null
            }
        val reshapePreviewInspectMode = reshapePreviewInspectDraft != null
        val routeModifyPreviewTitle =
            reshapePreviewInspectDraft?.options?.modifyMode?.let { mode ->
                when (mode) {
                    RouteModifyMode.RESHAPE_ROUTE -> "Reshape preview"
                    RouteModifyMode.TRIM_START_TO_HERE -> "Change start preview"
                    RouteModifyMode.TRIM_END_FROM_HERE -> "Change end preview"
                    else -> "GPX preview"
                }
            } ?: "GPX preview"
        val routeModifyPreviewInstruction =
            reshapePreviewInspectDraft?.options?.modifyMode?.let { mode ->
                when (mode) {
                    RouteModifyMode.RESHAPE_ROUTE -> "Inspect the reroute, then save."
                    RouteModifyMode.TRIM_START_TO_HERE -> "Inspect the new start, then save."
                    RouteModifyMode.TRIM_END_FROM_HERE -> "Inspect the new end, then save."
                    else -> "Inspect the edit, then save."
                }
            } ?: "Inspect the edit, then save."
        val recenterTarget: LatLong? =
            NavigateStartupCenteringEffects(
                offlineMode = offlineMode,
                shouldTrackLocation = shouldTrackLocation,
                locationMarkerLatLong = locationMarker?.latLong,
                lastKnownLocation = uiState.lastKnownLocation,
                navigateTarget = navigateTarget,
                pendingPoiFocusTarget = pendingPoiFocusTarget,
                mapView = mapView,
                mapViewModel = mapViewModel,
                selectedMapPath = selectedMapPath,
                activeGpxDetails = activeGpxDetails,
                navigationMarkerAnchorMode = effectiveNavigationMarkerAnchorMode,
            )
        val guidanceRuntime =
            rememberNavigateGuidanceRuntime(
                context = context,
                gpxViewModel = gpxViewModel,
                activeSession = activeTurnByTurnGuidanceSession,
                session = turnByTurnGuidanceSession,
                paused = turnByTurnGuidancePaused,
                rawCurrentLocation = rawCurrentLocation,
                recenterTarget = recenterTarget,
                offlineMode = offlineMode,
                routeStartBehavior = turnByTurnRouteStartBehavior,
                reverseSuggestionMode = turnByTurnReverseSuggestionMode,
                offRouteThresholdMeters = turnByTurnOffRouteThresholdMeters,
                hapticsEnabled = turnByTurnHapticsEnabled,
                voiceGuidanceEnabled = turnByTurnVoiceGuidanceEnabled,
                turnAlertsMode = turnByTurnTurnAlertsMode,
                offRouteAlertsEnabled = turnByTurnOffRouteAlertsEnabled,
                offRouteRepeatSeconds = turnByTurnOffRouteRepeatSeconds,
                guidanceGpsInAmbient = turnByTurnGpsInAmbient,
                brouterGuideBackEnabled = true,
                lastScreenResumeElapsedMs = lastScreenResumeElapsedMs,
                isMetric = isMetric,
                activityProfile = activityProfile,
                userWeightKg = userWeightKg,
                backpackWeightKg = backpackWeightKg,
                bikeWeightKg = bikeWeightKg,
                recordingActive = traceRecordingState.active,
                recordingSampleIntervalSeconds = recordingSampleIntervalSeconds,
                gpxFlatSpeedMps = gpxFlatSpeedMps,
                gpxAdvancedEtaEnabled = gpxAdvancedEtaEnabled,
                gpxStaminaAdjustmentEnabled = gpxStaminaAdjustmentEnabled,
                gpxUphillVerticalMetersPerHour = gpxUphillVerticalMetersPerHour,
                gpxDownhillVerticalMetersPerHour = gpxDownhillVerticalMetersPerHour,
            )
        val adaptiveTurnByTurnScreenOffIntervalMs =
            if (
                turnByTurnScreenOffGpsIntervalSeconds ==
                SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS &&
                screenState.isNonInteractive &&
                activeTurnByTurnGuidanceSession != null
            ) {
                resolveAdaptiveTurnByTurnScreenOffIntervalMs(
                    state = guidanceRuntime.state,
                    currentSpeedMps = rawCurrentLocation?.speed,
                    activityProfile = activityProfile,
                )
            } else {
                null
            }
        LaunchedEffect(adaptiveTurnByTurnScreenOffIntervalMs) {
            locationViewModel.setTurnByTurnScreenOffIntervalOverride(
                intervalMs = adaptiveTurnByTurnScreenOffIntervalMs,
            )
        }
        DisposableEffect(locationViewModel) {
            onDispose {
                locationViewModel.setTurnByTurnScreenOffIntervalOverride(intervalMs = null)
            }
        }

        LaunchedEffect(
            effectiveNavigationMarkerAnchorMode,
            effectiveNavMode,
            recenterTarget,
            mapView,
        ) {
            if (!offlineMode && effectiveNavMode != NavMode.PANNING) {
                recenterTarget?.let { mapView.setCenterForNavigationMarker(it, effectiveNavigationMarkerAnchorMode) }
            }
        }

        val defaultRouteToolOptions =
            remember(
                gpxToolRouteStyle,
                gpxToolUseElevation,
                gpxToolAllowFerries,
                gpxToolCustomHikeParams,
            ) {
                RouteToolOptions(
                    routeStyle = routeStylePresetFromSettingsValue(gpxToolRouteStyle),
                    useElevation = gpxToolUseElevation,
                    allowFerries = gpxToolAllowFerries,
                    customHikeParams = gpxToolCustomHikeParams,
                )
            }

        val routeToolActions =
            rememberNavigateRouteToolActions(
                context = context,
                scope = scope,
                mapView = mapView,
                gpxViewModel = gpxViewModel,
                poiViewModel = poiViewModel,
                locationViewModel = locationViewModel,
                recenterTarget = recenterTarget,
                gpsSignalSnapshot = gpsSignalSnapshot,
                offlineMode = offlineMode,
                activeGpxDetailsCount = activeGpxDetails.size,
                selectedMapPath = selectedMapPath,
                triggerHaptic = screenActions.triggerHaptic,
                routeToolOptions = routeToolOptions,
                routeToolDefaultOptions = defaultRouteToolOptions,
                setRouteToolOptions = { routeToolOptions = it },
                routeToolSession = routeToolSession,
                setRouteToolSession = { routeToolSession = it },
                setCompletedRouteToolDraft = { completedRouteToolDraft = it },
                routeToolExecutionInProgress = routeToolExecutionInProgress,
                setRouteToolExecutionInProgress = { routeToolExecutionInProgress = it },
                setRouteToolExecutionStatus = { routeToolExecutionStatus = it },
                setRouteToolExecutionMessage = { routeToolExecutionMessage = it },
                setRouteToolLoopRetryOptions = { routeToolLoopRetryOptions = it },
                setRouteToolResult = { routeToolResult = it },
                setRouteToolRenameInProgress = { routeToolRenameInProgress = it },
                setRouteToolRenameError = { routeToolRenameError = it },
                setRouteToolPreview = { routeToolPreview = it },
                routeToolCreatePreview = routeToolCreatePreview,
                setRouteToolCreatePreview = { routeToolCreatePreview = it },
                routeToolCreatePreviewInProgress = routeToolCreatePreviewInProgress,
                setRouteToolCreatePreviewInProgress = { routeToolCreatePreviewInProgress = it },
                routeToolCreatePreviewMessage = routeToolCreatePreviewMessage,
                setRouteToolCreatePreviewMessage = { routeToolCreatePreviewMessage = it },
                setRouteToolPreflightMessage = { routeToolPreflightMessage = it },
                setShortcutTrayExpanded = { shortcutTrayExpanded = it },
                setShowRouteToolsPanel = { showRouteToolsPanel = it },
                setPoiCreationSelectionActive = { poiCreationSelectionActive = it },
                createdPoiCreateInProgress = createdPoiCreateInProgress,
                setCreatedPoiCreateInProgress = { createdPoiCreateInProgress = it },
                setCreatedPoiPendingRename = { createdPoiPendingRename = it },
                setCreatedPoiRenameError = { createdPoiRenameError = it },
                setShowCreatedPoiRenameDialog = { showCreatedPoiRenameDialog = it },
            )

        NavigateScreenDialogsHost(
            showKeepAppOpenInfoDialog = showKeepAppOpenInfoDialog,
            helpDialogMaxHeight = adaptive.helpDialogMaxHeight,
            onContinueKeepAppOpen = {
                showKeepAppOpenInfoDialog = false
                screenActions.continueKeepAppOpenEnableFlow()
            },
            onDismissKeepAppOpen = {
                showKeepAppOpenInfoDialog = false
                pendingKeepAppOpen = false
            },
            showNotificationPermissionDialog = showNotificationPermissionDialog,
            onContinueNotificationPermission = {
                showNotificationPermissionDialog = false
                notificationPermissionState.launchPermissionRequest()
            },
            onDismissNotificationPermission = {
                showNotificationPermissionDialog = false
                pendingKeepAppOpen = false
            },
            showCreatedPoiRenameDialog = showCreatedPoiRenameDialog,
            createdPoiPendingRename = createdPoiPendingRename,
            createdPoiRenameInProgress = createdPoiRenameInProgress,
            createdPoiRenameError = createdPoiRenameError,
            onDismissCreatedPoiRename = {
                if (!createdPoiRenameInProgress) {
                    createdPoiPendingRename?.let { createdPoi ->
                        pendingPoiFocusTarget =
                            PoiNavigateTarget(
                                lat = createdPoi.lat,
                                lon = createdPoi.lon,
                                label = createdPoi.name,
                                type = PoiType.CUSTOM,
                                details = createdPoi.details,
                            )
                    }
                    showCreatedPoiRenameDialog = false
                    createdPoiPendingRename = null
                    createdPoiRenameError = null
                    poiCreationSelectionActive = false
                }
            },
            onConfirmCreatedPoiRename = { newName ->
                val target = createdPoiPendingRename ?: return@NavigateScreenDialogsHost
                if (createdPoiRenameInProgress) return@NavigateScreenDialogsHost
                createdPoiRenameInProgress = true
                createdPoiRenameError = null
                scope.launch {
                    runCatching {
                        poiViewModel.renameMyCreationPoi(target.id, newName)
                    }.onSuccess {
                        pendingPoiFocusTarget =
                            PoiNavigateTarget(
                                lat = target.lat,
                                lon = target.lon,
                                label = newName.trim().ifBlank { target.name },
                                type = PoiType.CUSTOM,
                                details = target.details,
                            )
                        createdPoiRenameInProgress = false
                        showCreatedPoiRenameDialog = false
                        createdPoiPendingRename = null
                        createdPoiRenameError = null
                        poiCreationSelectionActive = false
                    }.onFailure { error ->
                        createdPoiRenameInProgress = false
                        createdPoiRenameError = error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Failed to rename the POI."
                    }
                }
            },
            createdPoiCreateInProgress = createdPoiCreateInProgress,
            showRouteToolsPanel = showRouteToolsPanel,
            activeGpxDetailsCount = activeGpxDetails.size,
            coordinateSeed = mapView.model.mapViewPosition.center,
            poiSearchState = offlinePoiSearchUiState,
            routeToolOptions = routeToolOptions,
            routeToolPreflightMessage = routeToolPreflightMessage,
            onRouteToolOptionsChange = {
                routeToolPreflightMessage = null
                routeToolOptions = it.withVisibleLoopDefaults()
            },
            poiViewModel = poiViewModel,
            routeToolActions = routeToolActions,
            completedRouteToolDraft = completedRouteToolDraft,
            reshapePreviewInspectMode = reshapePreviewInspectMode,
            routeToolExecutionInProgress = routeToolExecutionInProgress,
            routeToolExecutionMessage = routeToolExecutionMessage,
            routeToolExecutionStatus = routeToolExecutionStatus,
            routeToolLoopRetryOptions = routeToolLoopRetryOptions,
            onDismissDraftSummary = {
                if (!routeToolExecutionInProgress) {
                    completedRouteToolDraft = null
                    routeToolExecutionMessage = null
                    routeToolLoopRetryOptions = emptyList()
                    routeToolPreview = null
                }
            },
            onSetRouteToolOptions = { routeToolOptions = it },
            onClearRouteToolExecutionMessage = { routeToolExecutionMessage = null },
            onClearRouteToolLoopRetryOptions = { routeToolLoopRetryOptions = emptyList() },
            routeToolResult = routeToolResult,
            routeToolRenameInProgress = routeToolRenameInProgress,
            routeToolRenameError = routeToolRenameError,
            isMetric = isMetric,
            recordingGpsEnabled = recordingScreenOnGpsEnabled,
            gpxViewModel = gpxViewModel,
            onSetRouteToolResult = { routeToolResult = it },
            onSetRouteToolRenameInProgress = { routeToolRenameInProgress = it },
            onSetRouteToolRenameError = { routeToolRenameError = it },
            onRouteToolGuidanceStarted = {
                navigateViewModel.onRecenterRequested()
                locationViewModel.requestImmediateLocation(source = "ui_route_tool_guidance_start")
                DebugTelemetry.log(
                    "NavigationTelemetry",
                    "event=recenter reason=route_tool_guidance_start",
                )
            },
            onDismissRouteToolsPanel = {
                showRouteToolsPanel = false
                routeToolPreflightMessage = null
                poiViewModel.clearOfflinePoiSearch()
            },
            onOpenGpxToolsSettings = {
                showRouteToolsPanel = false
                routeToolPreflightMessage = null
                poiViewModel.clearOfflinePoiSearch()
                onOpenGpxToolsSettings()
            },
        )

        NavigateContent(
            hasLocationPermission = locationPermissionState.hasLocationPermission || offlineMode,
            focusRequester = focusRequester,
            mapHolder = mapHolder,
            onMapViewReadyForRendering = { mapViewModel.onMapViewReadyForRendering() },
            onNavigateTimeSuppressedChange = onNavigateTimeSuppressedChange,
            showNavigateTime = showNavigateTime,
            navigateTimeFormat = navigateTimeFormat,
            mapAppearanceApplyInProgress = mapAppearanceApplyInProgress,
            slopeOverlayToggleEnabled = slopeOverlayToggleEnabled,
            slopeOverlayEnabled = slopeOverlayState.enabled,
            slopeOverlayProcessing = slopeOverlayState.processing,
            slopeOverlayProgressPercent = slopeOverlayState.progressPercent,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            zoomMinScaleMeters = zoomMinScaleMeters,
            zoomMaxScaleMeters = zoomMaxScaleMeters,
            crownZoomEnabled = crownZoomEnabled,
            crownZoomInverted = crownZoomInverted,
            mapZoomButtonsMode = mapZoomButtonsMode,
            northIndicatorMode = northIndicatorMode,
            currentZoomLevel = currentZoomLevel,
            onZoomLevelChange = { newZoom -> navigateViewModel.onZoomChanged(newZoom) },
            onViewportChanged = { center, zoomLevel ->
                mapZoomState.updateReference(center, mapView.width)
                if (offlineMode) {
                    mapViewModel.saveOfflineViewport(
                        selectedMapPath = selectedMapPath,
                        activeGpxDetails = activeGpxDetails,
                        center = center,
                        zoomLevel = zoomLevel,
                    )
                }
            },
            isMetric = isMetric,
            navMode = effectiveNavMode,
            locationMarker = locationMarker,
            lastKnownLocation = recenterTarget,
            onToggleOrientation = {
                if (!offlineMode) {
                    navigateViewModel.onToggleOrientation()
                }
            },
            onUserPanStarted = { navigateViewModel.onUserPanStarted() },
            onRecenter = { navigateViewModel.onRecenterRequested() },
            onRecenterRequested = {
                if (!offlineMode) {
                    locationViewModel.requestImmediateLocation(source = "ui_recenter_from_panning")
                }
            },
            triggerHaptic = screenActions.triggerHaptic,
            onMenuClick = {
                val nowElapsedMs = SystemClock.elapsedRealtime()
                if (nowElapsedMs < menuClickGuardUntilElapsedMs) {
                    DebugTelemetry.log(
                        "NavigationTelemetry",
                        "event=menu_click_ignored route=navigate_screen reason=recent_resume " +
                            "ageMs=${nowElapsedMs - lastScreenResumeElapsedMs} " +
                            "remainingMs=${menuClickGuardUntilElapsedMs - nowElapsedMs}",
                    )
                } else {
                    onMenuClick()
                }
            },
            onPermissionLaunch = { locationPermissionState.launchPermissions() },
            mapRotationDeg = renderedMapRotationDeg,
            navigationMarkerAnchorMode = effectiveNavigationMarkerAnchorMode,
            compassHeadingDeg = renderedCompassHeadingDeg,
            liveElevationEnabled = liveElevationEnabled,
            liveDistanceEnabled = liveDistanceEnabled && !offlineMode,
            keepAppOpen = keepAppOpen,
            onKeepAppOpenToggle = screenActions.toggleKeepAppOpen,
            backButtonExitsNavigation = backButtonExitsNavigation,
            traceRecordingState = traceRecordingState,
            recordingStatusMessage = recordingStatusMessage,
            recordingDashboardMetricSlots = recordingDashboardMetricSlots,
            turnByTurnDashboardMetricSlots = turnByTurnDashboardMetricSlots,
            userWeightKg = userWeightKg,
            backpackWeightKg = backpackWeightKg,
            bikeWeightKg = bikeWeightKg,
            recordingDashboardExpandRequestToken = recordingDashboardExpandRequestToken,
            recordingActionPromptRequestToken = recordingActionPromptRequestToken,
            onRecordingTimeTap = onRecordingTimeTap,
            onRecordingTimeLongPress = onRecordingTimeLongPress,
            onStartRecording = {
                shortcutTrayExpanded = false
                startRecordingWithActivityPermission()
            },
            onPauseRecording = traceRecordingViewModel::pauseRecording,
            onResumeRecording = traceRecordingViewModel::resumeRecording,
            onFinishRecording = traceRecordingViewModel::finishAndSaveRecording,
            onDiscardRecording = traceRecordingViewModel::discardRecording,
            onRecordingMetricSelected = settingsViewModel::setRecordingDashboardMetricSlot,
            onTurnByTurnMetricSelected = settingsViewModel::setTurnByTurnDashboardMetricSlot,
            shortcutTrayExpanded = shortcutTrayExpanded,
            onShortcutTrayToggle = screenActions.toggleShortcutTray,
            onShortcutTrayDismiss = { shortcutTrayExpanded = false },
            onOpenGpxTools = routeToolActions.openRouteToolsPanel,
            onStartPoiCreation = routeToolActions.startPoiCreationSelection,
            gpsIndicatorState = effectiveGpsIndicatorState,
            gpsEnvironmentWarning = gpsEnvironmentWarning,
            watchGpsDegradedWarning = watchGpsDegradedWarning,
            isOfflineMode = offlineMode,
            isGpxInspectionEnabled = isGpxInspectionEnabled,
            selectingGpxPointB = selectingGpxPointB,
            onCancelSelectingGpxPointB = { gpxViewModel.cancelSelectingB() },
            turnByTurnGuidanceState = guidanceRuntime.state,
            turnByTurnGuidancePaused = turnByTurnGuidancePaused,
            turnByTurnVoiceGuidanceEnabled = turnByTurnVoiceGuidanceEnabled,
            turnByTurnCompactPopupEnabled =
                turnByTurnCompactPopupEnabled && !showRouteToolsPanel,
            onTurnByTurnVoiceGuidanceChange = settingsViewModel::setTurnByTurnVoiceGuidanceEnabled,
            guideBackToRouteActive = guidanceRuntime.guideBackToRouteActive,
            showGuideBackPrompt = guidanceRuntime.showGuideBackPrompt,
            startDecisionPrompt = guidanceRuntime.startDecisionPrompt,
            onPauseTurnByTurnGuidance = { gpxViewModel.pauseTurnByTurnGuidance() },
            onResumeTurnByTurnGuidance = { gpxViewModel.resumeTurnByTurnGuidance() },
            onStopTurnByTurnGuidance = { gpxViewModel.stopTurnByTurnGuidance() },
            onGuideBackToRoute = guidanceRuntime.onGuideBackToRoute,
            onDismissGuideBackPrompt = guidanceRuntime.onDismissGuideBackPrompt,
            onAcceptStartDecisionPrompt = guidanceRuntime.onAcceptStartDecisionPrompt,
            onDismissStartDecisionPrompt = guidanceRuntime.onDismissStartDecisionPrompt,
            activeGpxDetails = activeGpxDetails,
            gpxTrackColor = gpxTrackColor,
            routeToolSession = routeToolSession,
            crosshairSelectionActive = poiCreationSelectionActive,
            crosshairSelectionTitle = "+ POI",
            crosshairSelectionInstruction = "Move map, then check.",
            crosshairSelectionBusy = createdPoiCreateInProgress,
            crosshairSelectionBusyMessage = "Saving POI...",
            routeToolCreatePreview = displayedRouteToolCreatePreview,
            routeToolDraftConnectorPoints = routeToolDraftConnectorPoints,
            routeToolCreatePreviewInProgress = routeToolCreatePreviewInProgress,
            routeToolCreatePreviewMessage = routeToolCreatePreviewMessage,
            reshapePreviewInspectMode = reshapePreviewInspectMode,
            reshapePreviewPoints = routeToolPreview?.previewPoints ?: emptyList(),
            reshapePreviewTitle = routeModifyPreviewTitle,
            reshapePreviewInstruction = routeModifyPreviewInstruction,
            reshapePreviewBusy = routeToolExecutionInProgress,
            reshapePreviewBusyMessage = routeToolExecutionStatus,
            reshapePreviewMessage = routeToolExecutionMessage,
            onRouteToolPickHere = routeToolActions.captureRouteToolPoint,
            onRouteToolUndoLastPoint = routeToolActions.undoRouteToolPoint,
            onRouteToolSaveCreatePreview = routeToolActions.saveCreatePreview,
            onRouteToolRefreshCreatePreview = routeToolActions.refreshLoopPreview,
            onCancelRouteToolMode = {
                routeToolSession = null
                routeToolCreatePreview = null
                routeToolCreatePreviewMessage = null
                routeToolCreatePreviewInProgress = false
            },
            onDismissReshapePreview = {
                if (!routeToolExecutionInProgress) {
                    completedRouteToolDraft = null
                    routeToolExecutionMessage = null
                    routeToolLoopRetryOptions = emptyList()
                    routeToolPreview = null
                }
            },
            onSaveReshapePreview = {
                reshapePreviewInspectDraft?.let { draft ->
                    routeToolActions.executeModifyDraft(draft, false)
                }
            },
            onCrosshairSelectionPickHere = routeToolActions.savePoiAt,
            onCancelCrosshairSelection = { poiCreationSelectionActive = false },
            onInspectTrack = { latLong -> gpxViewModel.onMapLongPress(latLong) },
            visiblePoiMarkers = visiblePoiMarkers,
            poiFocusTarget = pendingPoiFocusTarget,
            onPoiFocusTargetConsumed = { pendingPoiFocusTarget = null },
            onPoiTapCreateGpx = routeToolActions.createRouteToPoi,
            poiPopupTimeoutSeconds = poiPopupTimeoutSeconds,
            poiPopupManualCloseOnly = poiPopupManualCloseOnly,
            markerMotionDebugOverlayLabel = markerMotionDebugOverlayLabel,
            onCompassIssueNow =
                if (gpsDebugTelemetry && gpsDebugTelemetryPopupEnabled && !offlineMode) {
                    {
                        reportCompassIssueNow(
                            renderState = compassRenderState,
                            renderedHeadingDeg = renderedCompassHeadingDeg,
                            renderedMapRotationDeg = renderedMapRotationDeg,
                            screenState = screenState,
                        )
                    }
                } else {
                    null
                },
        )

        LaunchedEffect(isScreenResumed) {
            if (isScreenResumed) {
                focusRequester.requestFocus()
            }
        }

        DisposableEffect(mapView, offlineMode, selectedMapPath, activeGpxDetails) {
            onDispose {
                if (offlineMode) {
                    mapViewModel.saveOfflineViewport(
                        selectedMapPath = selectedMapPath,
                        activeGpxDetails = activeGpxDetails,
                        center = mapView.model.mapViewPosition.center,
                        zoomLevel =
                            mapView.model.mapViewPosition.zoomLevel
                                .toInt(),
                    )
                }
            }
        }
    }
}

private fun expectedMarkerGpsIntervalMs(
    runtimeReason: String,
    screenState: LocationScreenState,
    recordingSampleIntervalSeconds: Int,
    recordingScreenOffSampleIntervalSeconds: Int,
    turnByTurnGpsIntervalSeconds: Int,
    turnByTurnScreenOffGpsIntervalSeconds: Int,
): Long {
    val recordingScreenOnMs = gpsIntervalMsOrDefault(recordingSampleIntervalSeconds)
    val recordingScreenOffMs =
        screenOffGpsIntervalMs(
            screenOnIntervalMs = recordingScreenOnMs,
            screenOffSeconds = recordingScreenOffSampleIntervalSeconds,
        )
    val turnByTurnScreenOnMs = gpsIntervalMsOrDefault(turnByTurnGpsIntervalSeconds)
    val turnByTurnScreenOffMs =
        screenOffGpsIntervalMs(
            screenOnIntervalMs = turnByTurnScreenOnMs,
            screenOffSeconds = turnByTurnScreenOffGpsIntervalSeconds,
        )
    return when (runtimeReason) {
        NavigationRuntimeDemandReason.RECORDING ->
            if (screenState.isNonInteractive) recordingScreenOffMs else recordingScreenOnMs
        NavigationRuntimeDemandReason.RECORDING_AUTO_PAUSED ->
            maxOf(
                if (screenState.isNonInteractive) recordingScreenOffMs else recordingScreenOnMs,
                AUTO_PAUSE_GPS_INTERVAL_MS,
            )
        NavigationRuntimeDemandReason.RECORDING_GUIDANCE ->
            minOf(
                if (screenState.isNonInteractive) recordingScreenOffMs else recordingScreenOnMs,
                if (screenState.isNonInteractive) turnByTurnScreenOffMs else turnByTurnScreenOnMs,
            )
        NavigationRuntimeDemandReason.GUIDANCE_VISIBLE,
        NavigationRuntimeDemandReason.GUIDANCE_AMBIENT,
        NavigationRuntimeDemandReason.GUIDANCE_BACKGROUND,
        -> if (screenState.isNonInteractive) turnByTurnScreenOffMs else turnByTurnScreenOnMs
        else -> SettingsRepository.DEFAULT_GPS_INTERVAL_MS
    }
}

internal fun resolveMarkerGpsIntervalMs(
    serviceEffectiveIntervalMs: Long,
    configuredIntervalMs: Long,
): Long = serviceEffectiveIntervalMs.takeIf { it > 0L } ?: configuredIntervalMs

private fun screenOffGpsIntervalMs(
    screenOnIntervalMs: Long,
    screenOffSeconds: Int,
): Long =
    when (screenOffSeconds) {
        SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS ->
            SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS * 1_000L
        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> screenOnIntervalMs
        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> SettingsRepository.DEFAULT_GPS_INTERVAL_MS
        else -> gpsIntervalMsOrDefault(screenOffSeconds)
    }

private fun gpsIntervalMsOrDefault(seconds: Int): Long =
    when (seconds) {
        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> SettingsRepository.DEFAULT_GPS_INTERVAL_MS
        else -> seconds.coerceAtLeast(1) * 1_000L
    }
