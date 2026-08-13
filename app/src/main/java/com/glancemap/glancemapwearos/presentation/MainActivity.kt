package com.glancemap.glancemapwearos.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.ScreenStateDiagnostics
import com.glancemap.glancemapwearos.core.service.location.model.isNonInteractive
import com.glancemap.glancemapwearos.core.service.location.model.resolveLocationScreenState
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeInputs
import com.glancemap.glancemapwearos.core.service.location.policy.navigationRuntimeDemand
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.presentation.features.download.DownloadScreen
import com.glancemap.glancemapwearos.presentation.features.download.DownloadSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxScreen
import com.glancemap.glancemapwearos.presentation.features.home.MainScreen
import com.glancemap.glancemapwearos.presentation.features.maps.MapsScreen
import com.glancemap.glancemapwearos.presentation.features.navigate.AmbientScreen
import com.glancemap.glancemapwearos.presentation.features.navigate.NavigateScreen
import com.glancemap.glancemapwearos.presentation.features.poi.PoiScreen
import com.glancemap.glancemapwearos.presentation.features.recording.sensors.RecordingSensorBridge
import com.glancemap.glancemapwearos.presentation.features.settings.CompassSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.DebuggingSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.GpsAdvancedSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.GpsSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.GpxAppearanceSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.GpxSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.GpxToolsSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.LicensesScreen
import com.glancemap.glancemapwearos.presentation.features.settings.MapDisplaySettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.MapSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.MapZoomSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.PoiSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.RecordingAdvancedSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.RecordingBikeSensorSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.RecordingDashboardSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.RecordingExternalSensorsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.RecordingSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.RecordingSourceSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.ResetDefaultsConfirmScreen
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.ThemeSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.TurnByTurnAdvancedSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.TurnByTurnAlertsSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.TurnByTurnBackgroundSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.TurnByTurnDashboardSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.TurnByTurnFeedbackSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.TurnByTurnGuidanceSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.TurnByTurnSettingsScreen
import com.glancemap.glancemapwearos.presentation.features.settings.UserProfileSettingsScreen
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.presentation.ui.WearActionButtonRole
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialogButton
import com.google.android.horologist.compose.layout.AppScaffold
import kotlinx.coroutines.launch
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

@Suppress("LargeClass")
class MainActivity : ComponentActivity() {
    private val ambientState = WearAmbientState(this, ::logScreenTelemetry)

    @Volatile
    private var activeRoute: String? = null
    private val thermalTelemetry =
        ThermalTelemetryController(this) {
            "route=${activeRoute ?: "unknown"} ambient=${ambientState.isAmbient} " +
                "interactive=${ambientState.isDeviceInteractive}"
        }

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                ambientState.onScreenStateChanged(intent?.action)
            }
        }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(this.application)
        ambientState.refreshDeviceInteractive(fallback = true)

        val screenStateFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, screenStateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenStateReceiver, screenStateFilter)
        }
        thermalTelemetry.register()

        lifecycle.addObserver(ambientState.observer)

        setContent {
            val appContainer = (application as GlanceMapWearApp).container
            val showTimeInNavigate by appContainer.settingsViewModel.showTimeInNavigate
                .collectAsState(initial = true)
            val navigateTimeFormat by appContainer.settingsViewModel.navigateTimeFormat.collectAsState()
            val isMetric by appContainer.settingsViewModel.isMetric.collectAsState()
            val traceRecordingState by appContainer.traceRecordingViewModel.uiState.collectAsState()
            val recordingStartWarning by appContainer.traceRecordingViewModel.startWarning.collectAsState()
            val recordingLocationStartWarning by
                appContainer.traceRecordingViewModel.locationStartWarning.collectAsState()
            val turnByTurnGuidanceSession by appContainer.gpxViewModel.turnByTurnGuidanceSession.collectAsState()
            val turnByTurnGuidancePaused by appContainer.gpxViewModel.turnByTurnGuidancePaused.collectAsState()
            val gpsInAmbientMode by appContainer.settingsViewModel.gpsInAmbientMode.collectAsState(initial = false)
            val offlineMode by appContainer.settingsViewModel.offlineMode.collectAsState(initial = false)
            val recordingDashboardMetricSlots by appContainer.settingsViewModel.recordingDashboardMetricSlots.collectAsState()
            val recordingStartWithTurnByTurn by appContainer.settingsViewModel.recordingStartWithTurnByTurn.collectAsState()
            val recordingSampleIntervalSeconds by appContainer.settingsViewModel.recordingSampleIntervalSeconds.collectAsState()
            val recordingScreenOffSampleIntervalSeconds by appContainer.settingsViewModel.recordingScreenOffSampleIntervalSeconds.collectAsState()
            val turnByTurnGpsIntervalSeconds by appContainer.settingsViewModel.turnByTurnGpsIntervalSeconds.collectAsState()
            val turnByTurnScreenOffGpsIntervalSeconds by appContainer.settingsViewModel.turnByTurnScreenOffGpsIntervalSeconds.collectAsState()
            val recordingElevationSource by appContainer.settingsViewModel.recordingElevationSource.collectAsState()
            val recordingHeartRateSource by appContainer.settingsViewModel.recordingHeartRateSource.collectAsState()
            val recordingCadenceSource by appContainer.settingsViewModel.recordingCadenceSource.collectAsState()
            val recordingSpeedSource by appContainer.settingsViewModel.recordingSpeedSource.collectAsState()
            val recordingDistanceSource by appContainer.settingsViewModel.recordingDistanceSource.collectAsState()
            val recordingStepsSource by appContainer.settingsViewModel.recordingStepsSource.collectAsState()
            val recordingExternalHeartRateAddress by appContainer.settingsViewModel.recordingExternalHeartRateAddress.collectAsState()
            val recordingExternalRunPodAddress by appContainer.settingsViewModel.recordingExternalRunPodAddress.collectAsState()
            val cyclingWheelCircumferenceMeters by appContainer.settingsViewModel.cyclingWheelCircumferenceMeters.collectAsState()
            val activityProfile by appContainer.settingsViewModel.activityProfile.collectAsState()

            LaunchedEffect(recordingLocationStartWarning) {
                if (recordingLocationStartWarning != null) {
                    appContainer.locationViewModel.requestImmediateLocation(
                        source = "ui_recording_start_missing_location",
                    )
                }
            }

            val isAmbient = ambientState.isAmbient
            val ambientTickMs = ambientState.ambientTickMs
            val isDeviceInteractive = ambientState.isDeviceInteractive
            val burnInProtectionRequired = ambientState.burnInProtectionRequired
            val deviceHasLowBitAmbient = ambientState.deviceHasLowBitAmbient
            val activityLocationScreenState =
                remember(isAmbient, isDeviceInteractive) {
                    resolveLocationScreenState(
                        isAmbient = isAmbient,
                        isDeviceInteractive = isDeviceInteractive,
                    )
                }

            GlanceMapTheme {
                val navController = rememberNavController()
                val appScope = rememberCoroutineScope()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val route = backStackEntry?.destination?.route
                val routeLabel = route ?: WatchRoutes.NAVIGATE
                val compositionContext = LocalContext.current
                RecordingSensorBridge(
                    active = traceRecordingState.active,
                    paused = traceRecordingState.paused,
                    selectedMetricIds = recordingDashboardMetricSlots,
                    elevationSource = recordingElevationSource,
                    heartRateSource = recordingHeartRateSource,
                    cadenceSource = recordingCadenceSource,
                    speedSource = recordingSpeedSource,
                    distanceSource = recordingDistanceSource,
                    stepsSource = recordingStepsSource,
                    externalHeartRateAddress = recordingExternalHeartRateAddress,
                    externalRunPodAddress = recordingExternalRunPodAddress,
                    cyclingWheelCircumferenceMeters = cyclingWheelCircumferenceMeters,
                    activityProfile = traceRecordingState.activityProfile,
                    initialStepCount = traceRecordingState.stepCount,
                    onMetrics = appContainer.traceRecordingViewModel::onSensorMetrics,
                )
                val locationPermissionGranted =
                    ContextCompat.checkSelfPermission(
                        compositionContext,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            compositionContext,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                val recordingScreenOnGpsEnabled =
                    recordingSampleIntervalSeconds != SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
                val recordingScreenOffGpsEnabled =
                    when (recordingScreenOffSampleIntervalSeconds) {
                        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> recordingScreenOnGpsEnabled
                        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> false
                        else -> true
                    }
                val recordingGpsEnabled =
                    if (activityLocationScreenState.isNonInteractive) {
                        recordingScreenOffGpsEnabled
                    } else {
                        recordingScreenOnGpsEnabled
                    }
                val turnByTurnScreenOnGpsEnabled =
                    turnByTurnGpsIntervalSeconds != SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
                val turnByTurnScreenOffGpsEnabled =
                    when (turnByTurnScreenOffGpsIntervalSeconds) {
                        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> turnByTurnScreenOnGpsEnabled
                        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> false
                        else -> true
                    }
                val turnByTurnGpsEnabled =
                    if (activityLocationScreenState.isNonInteractive) {
                        turnByTurnScreenOffGpsEnabled
                    } else {
                        turnByTurnScreenOnGpsEnabled
                    }
                val recordingRuntimePaused = traceRecordingState.paused && !traceRecordingState.autoPaused
                var suppressNavigateTime by remember { mutableStateOf(false) }
                var recordingDashboardExpandRequestToken by remember { mutableLongStateOf(0L) }
                var recordingActionPromptRequestToken by remember { mutableLongStateOf(0L) }
                LaunchedEffect(routeLabel) {
                    activeRoute = routeLabel
                    logNavigationTelemetry(event = "route_visible", route = routeLabel)
                }
                val isNavigateScreen = routeLabel == WatchRoutes.NAVIGATE
                val activityOwnsRuntime =
                    activityOwnsNavigationRuntime(
                        isNavigateScreen = isNavigateScreen,
                        isAmbient = isAmbient,
                    )
                LaunchedEffect(isNavigateScreen) {
                    if (!isNavigateScreen) {
                        suppressNavigateTime = false
                    }
                }
                LaunchedEffect(
                    activityOwnsRuntime,
                    traceRecordingState.active,
                    recordingRuntimePaused,
                    traceRecordingState.autoPaused,
                    recordingGpsEnabled,
                    turnByTurnGuidanceSession,
                    turnByTurnGuidancePaused,
                    turnByTurnGpsEnabled,
                    gpsInAmbientMode,
                    turnByTurnScreenOffGpsEnabled,
                    offlineMode,
                    activityLocationScreenState,
                    locationPermissionGranted,
                ) {
                    if (!activityOwnsRuntime) return@LaunchedEffect
                    val runtimeDemand =
                        navigationRuntimeDemand(
                            NavigationRuntimeInputs(
                                isNavigateScreen = isNavigateScreen,
                                screenState = activityLocationScreenState,
                                isScreenResumed = true,
                                hasLocationPermission = locationPermissionGranted,
                                offlineMode = offlineMode,
                                generalGpsInAmbient = gpsInAmbientMode,
                                recordingActive = traceRecordingState.active,
                                recordingPaused = recordingRuntimePaused,
                                recordingAutoPaused = traceRecordingState.autoPaused,
                                recordingGpsEnabled = recordingGpsEnabled,
                                turnByTurnActive = turnByTurnGuidanceSession != null,
                                turnByTurnPaused = turnByTurnGuidancePaused,
                                turnByTurnGpsEnabled = turnByTurnGpsEnabled,
                                turnByTurnGpsInAmbient = turnByTurnScreenOffGpsEnabled,
                            ),
                        )
                    appContainer.locationViewModel.syncRuntimeState(
                        screenState = activityLocationScreenState,
                        trackingEnabled = runtimeDemand.trackingEnabled,
                        backgroundGpsEnabled = runtimeDemand.backgroundGpsEnabled,
                        runtimeReason = runtimeDemand.reason,
                    )
                    DebugTelemetry.log(
                        "NavigationRuntime",
                        "event=activity_runtime_sync active=${traceRecordingState.active} " +
                            "paused=${traceRecordingState.paused} autoPaused=${traceRecordingState.autoPaused} " +
                            "recordingGps=$recordingGpsEnabled " +
                            "guidance=${turnByTurnGuidanceSession != null} " +
                            "guidancePaused=$turnByTurnGuidancePaused tracking=${runtimeDemand.trackingEnabled} " +
                            "backgroundGps=${runtimeDemand.backgroundGpsEnabled} reason=${runtimeDemand.reason} " +
                            "route=$routeLabel",
                    )
                }
                val navigateViaSwipeLeft: () -> Unit = {
                    val popped = navController.popBackStack(WatchRoutes.NAVIGATE, inclusive = false)
                    if (!popped) {
                        navController.navigate(WatchRoutes.NAVIGATE) {
                            popUpTo(WatchRoutes.NAVIGATE) {
                                inclusive = false
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
                val onRecordingTimeTap = {
                    DebugTelemetry.log(
                        "TraceRecording",
                        "event=time_chip_tap debugCapture=${DebugTelemetry.isEnabled()}",
                    )
                    recordingDashboardExpandRequestToken += 1L
                }
                val onRecordingTimeLongPress = {
                    DebugTelemetry.log(
                        "TraceRecording",
                        "event=time_chip_long_press debugCapture=${DebugTelemetry.isEnabled()}",
                    )
                    recordingActionPromptRequestToken += 1L
                }

                if (isAmbient) {
                    AmbientScreen(
                        ambientTick = ambientTickMs,
                        timeFormat = navigateTimeFormat,
                        burnInProtectionRequired = burnInProtectionRequired,
                        deviceHasLowBitAmbient = deviceHasLowBitAmbient,
                    )
                    return@GlanceMapTheme
                }

                AppScaffold(
                    timeText = {},
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = WatchRoutes.NAVIGATE,
                        modifier = Modifier.background(Color.Black),
                    ) {
                        composable(WatchRoutes.NAVIGATE) {
                            // ✅ NO swipe container here -> swipe-to-dismiss cannot happen
                            DisposableEffect(Unit) {
                                logNavigationTelemetry(
                                    event = "navigate_compose_enter",
                                    route = WatchRoutes.NAVIGATE,
                                )
                                onDispose {
                                    logNavigationTelemetry(
                                        event = "navigate_compose_dispose",
                                        route = activeRoute ?: WatchRoutes.NAVIGATE,
                                    )
                                }
                            }
                            NavigateScreen(
                                mapViewModel = appContainer.mapViewModel,
                                gpxViewModel = appContainer.gpxViewModel,
                                poiViewModel = appContainer.poiViewModel,
                                compassViewModel = appContainer.compassViewModel,
                                settingsViewModel = appContainer.settingsViewModel,
                                locationViewModel = appContainer.locationViewModel,
                                traceRecordingViewModel = appContainer.traceRecordingViewModel,
                                isAmbient = isAmbient,
                                isDeviceInteractive = isDeviceInteractive,
                                ambientTickMs = ambientTickMs,
                                onNavigateTimeSuppressedChange = { suppressNavigateTime = it },
                                showNavigateTime = showTimeInNavigate && !suppressNavigateTime,
                                navigateTimeFormat = navigateTimeFormat,
                                recordingDashboardExpandRequestToken = recordingDashboardExpandRequestToken,
                                recordingActionPromptRequestToken = recordingActionPromptRequestToken,
                                onRecordingTimeTap = onRecordingTimeTap,
                                onRecordingTimeLongPress = onRecordingTimeLongPress,
                                onMenuClick = {
                                    logNavigationTelemetry(
                                        event = "menu_click",
                                        route = activeRoute ?: WatchRoutes.NAVIGATE,
                                    )
                                    navController.navigate(WatchRoutes.MAIN_MENU) {
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onOpenGpxToolsSettings = {
                                    navController.navigate(WatchRoutes.GPX_TOOLS_SETTINGS) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }

                        composable(WatchRoutes.MAIN_MENU) {
                            BackHandler(enabled = true) {
                                finishAndRemoveTask()
                            }
                            DismissableScreen(
                                onDismiss = { finishAndRemoveTask() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                                rightEdgeGestureWidthOverride = 8.dp,
                            ) {
                                MainScreen(
                                    navController = navController,
                                    settingsViewModel = appContainer.settingsViewModel,
                                )
                            }
                        }

                        composable(WatchRoutes.GPX) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                GpxScreen(
                                    navController = navController,
                                    gpxViewModel = appContainer.gpxViewModel,
                                    isMetric = isMetric,
                                    autoStartRecordingWithGuidance = recordingStartWithTurnByTurn,
                                    recordingActiveOrSaving = traceRecordingState.active || traceRecordingState.saving,
                                    onStartRecording = appContainer.traceRecordingViewModel::startRecording,
                                )
                            }
                        }

                        composable(WatchRoutes.POI) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                PoiScreen(
                                    navController = navController,
                                    poiViewModel = appContainer.poiViewModel,
                                )
                            }
                        }

                        composable(WatchRoutes.MAPS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                MapsScreen(
                                    navController = navController,
                                    mapViewModel = appContainer.mapViewModel,
                                    themeViewModel = appContainer.themeViewModel,
                                )
                            }
                        }

                        composable(WatchRoutes.DOWNLOAD) {
                            var isDownloadAreaPickerOpen by rememberSaveable { mutableStateOf(false) }
                            var downloadAreaFolder by rememberSaveable { mutableStateOf<String?>(null) }
                            var downloadAreaSearchQuery by rememberSaveable { mutableStateOf("") }
                            DismissableScreen(
                                onDismiss = {
                                    if (isDownloadAreaPickerOpen) {
                                        when {
                                            downloadAreaSearchQuery.isNotBlank() -> downloadAreaSearchQuery = ""
                                            downloadAreaFolder != null -> downloadAreaFolder = null
                                            else -> isDownloadAreaPickerOpen = false
                                        }
                                    } else {
                                        navController.popBackStack()
                                    }
                                },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                DownloadScreen(
                                    viewModel = appContainer.downloadViewModel,
                                    areaPickerOpen = isDownloadAreaPickerOpen,
                                    onAreaPickerOpenChange = { isDownloadAreaPickerOpen = it },
                                    selectedAreaFolder = downloadAreaFolder,
                                    onSelectedAreaFolderChange = { downloadAreaFolder = it },
                                    areaSearchQuery = downloadAreaSearchQuery,
                                    onAreaSearchQueryChange = { downloadAreaSearchQuery = it },
                                    onLibraryChanged = {
                                        appContainer.mapViewModel.loadMapFiles(preserveExistingCoverage = false)
                                        appContainer.mapViewModel.loadRoutingPackFiles()
                                        appContainer.poiViewModel.loadPoiFiles(forceRefresh = true)
                                    },
                                    onOpenSettings = {
                                        navController.navigate(WatchRoutes.DOWNLOAD_SETTINGS)
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.DOWNLOAD_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                DownloadSettingsScreen(
                                    viewModel = appContainer.downloadViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                SettingsScreen(
                                    navController,
                                    appContainer.settingsViewModel,
                                    appContainer.mapViewModel,
                                    appContainer.gpxViewModel,
                                )
                            }
                        }

                        composable(WatchRoutes.COMPASS_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                CompassSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    compassViewModel = appContainer.compassViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.RESET_DEFAULTS_CONFIRM) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                ResetDefaultsConfirmScreen(
                                    onCancel = { navController.popBackStack() },
                                    onConfirmReset = {
                                        appScope.launch {
                                            appContainer.settingsViewModel.resetToDefaultsAndWait()
                                            appContainer.themeViewModel.resetToDefaultsAndWait()
                                            appContainer.gpxViewModel.resetActiveGpxFilesAndWait()
                                            appContainer.poiViewModel.resetPoiVisibilityAndWait()
                                            navController.navigate(WatchRoutes.SETTINGS) {
                                                popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.GPS_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                GpsSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onOpenAdvancedSettings = {
                                        navController.navigate(WatchRoutes.GPS_ADVANCED_SETTINGS)
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.GPS_ADVANCED_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                GpsAdvancedSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGpsSettings = {
                                        navController.navigate(WatchRoutes.GPS_SETTINGS) {
                                            popUpTo(WatchRoutes.GPS_SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.USER_PROFILE_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                UserProfileSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    profileChangeEnabled = !traceRecordingState.active,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.RECORDING_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                RecordingSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onOpenSourceSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_SOURCE_SETTINGS)
                                    },
                                    onOpenExternalSensors = {
                                        navController.navigate(WatchRoutes.RECORDING_EXTERNAL_SENSORS)
                                    },
                                    onOpenDashboardSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_DASHBOARD_SETTINGS)
                                    },
                                    onOpenAdvancedSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_ADVANCED_SETTINGS)
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.RECORDING_ADVANCED_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                RecordingAdvancedSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenRecordingSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_SETTINGS) {
                                            popUpTo(WatchRoutes.RECORDING_SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.RECORDING_SOURCE_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                RecordingSourceSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenRecordingSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_SETTINGS) {
                                            popUpTo(WatchRoutes.RECORDING_SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onOpenBikeSensorSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_BIKE_SENSOR_SETTINGS) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.RECORDING_BIKE_SENSOR_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                RecordingBikeSensorSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenRecordingSourceSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_SOURCE_SETTINGS) {
                                            popUpTo(WatchRoutes.RECORDING_SOURCE_SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.RECORDING_DASHBOARD_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                RecordingDashboardSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenRecordingSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_SETTINGS) {
                                            popUpTo(WatchRoutes.RECORDING_SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.RECORDING_EXTERNAL_SENSORS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                RecordingExternalSensorsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    connectLinkedSensors =
                                        !traceRecordingState.active || traceRecordingState.paused,
                                    onOpenRecordingSettings = {
                                        navController.navigate(WatchRoutes.RECORDING_SETTINGS) {
                                            popUpTo(WatchRoutes.RECORDING_SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.TURN_BY_TURN_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                TurnByTurnSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onOpenAdvancedSettings = {
                                        navController.navigate(WatchRoutes.TURN_BY_TURN_ADVANCED_SETTINGS)
                                    },
                                    onOpenDashboardSettings = {
                                        navController.navigate(WatchRoutes.TURN_BY_TURN_DASHBOARD_SETTINGS)
                                    },
                                )
                            }
                        }

                        val openTurnByTurnSettings = {
                            navController.navigate(WatchRoutes.TURN_BY_TURN_SETTINGS) {
                                popUpTo(WatchRoutes.TURN_BY_TURN_SETTINGS) { inclusive = false }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        val turnByTurnCategoryScreens: List<Pair<String, @Composable () -> Unit>> =
                            listOf(
                                WatchRoutes.TURN_BY_TURN_ADVANCED_SETTINGS to {
                                    TurnByTurnAdvancedSettingsScreen(
                                        viewModel = appContainer.settingsViewModel,
                                        onOpenTurnByTurnSettings = openTurnByTurnSettings,
                                    )
                                },
                                WatchRoutes.TURN_BY_TURN_GUIDANCE_SETTINGS to {
                                    TurnByTurnGuidanceSettingsScreen(
                                        viewModel = appContainer.settingsViewModel,
                                        onOpenTurnByTurnSettings = openTurnByTurnSettings,
                                    )
                                },
                                WatchRoutes.TURN_BY_TURN_ALERTS_SETTINGS to {
                                    TurnByTurnAlertsSettingsScreen(
                                        viewModel = appContainer.settingsViewModel,
                                        onOpenTurnByTurnSettings = openTurnByTurnSettings,
                                    )
                                },
                                WatchRoutes.TURN_BY_TURN_FEEDBACK_SETTINGS to {
                                    TurnByTurnFeedbackSettingsScreen(
                                        viewModel = appContainer.settingsViewModel,
                                        onOpenTurnByTurnSettings = openTurnByTurnSettings,
                                    )
                                },
                                WatchRoutes.TURN_BY_TURN_BACKGROUND_SETTINGS to {
                                    TurnByTurnBackgroundSettingsScreen(
                                        viewModel = appContainer.settingsViewModel,
                                        onOpenTurnByTurnSettings = openTurnByTurnSettings,
                                    )
                                },
                                WatchRoutes.TURN_BY_TURN_DASHBOARD_SETTINGS to {
                                    TurnByTurnDashboardSettingsScreen(
                                        viewModel = appContainer.settingsViewModel,
                                        onOpenTurnByTurnSettings = openTurnByTurnSettings,
                                    )
                                },
                            )
                        turnByTurnCategoryScreens.forEach { (route, screen) ->
                            composable(route) {
                                DismissableScreen(
                                    onDismiss = { navController.popBackStack() },
                                    onSwipeLeftNavigate = navigateViaSwipeLeft,
                                    content = screen,
                                )
                            }
                        }

                        composable(WatchRoutes.DEBUG_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                DebuggingSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.GPX_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                GpxSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onOpenTurnByTurnSettings = {
                                        navController.navigate(WatchRoutes.TURN_BY_TURN_SETTINGS)
                                    },
                                    onOpenGpxToolsSettings = {
                                        navController.navigate(WatchRoutes.GPX_TOOLS_SETTINGS)
                                    },
                                    onOpenGpxAppearanceSettings = {
                                        navController.navigate(WatchRoutes.GPX_APPEARANCE_SETTINGS)
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.GPX_APPEARANCE_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                GpxAppearanceSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGpxSettings = {
                                        navController.navigate(WatchRoutes.GPX_SETTINGS) {
                                            popUpTo(WatchRoutes.GPX_SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.GPX_TOOLS_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                GpxToolsSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGpxSettings = {
                                        navController.navigate(WatchRoutes.GPX_SETTINGS) {
                                            popUpTo(WatchRoutes.GPX_SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.POI_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                PoiSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.MAP_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                MapSettingsScreen(
                                    navController = navController,
                                    viewModel = appContainer.settingsViewModel,
                                    themeViewModel = appContainer.themeViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.MAP_ZOOM_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                MapZoomSettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.MAP_DISPLAY_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                MapDisplaySettingsScreen(
                                    viewModel = appContainer.settingsViewModel,
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.THEME_SETTINGS) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                ThemeSettingsScreen(
                                    themeViewModel = appContainer.themeViewModel,
                                    mapViewModel = appContainer.mapViewModel,
                                    onOpenMaps = {
                                        navController.navigate(WatchRoutes.MAPS) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable(WatchRoutes.LICENSES) {
                            DismissableScreen(
                                onDismiss = { navController.popBackStack() },
                                onSwipeLeftNavigate = navigateViaSwipeLeft,
                            ) {
                                LicensesScreen(
                                    onOpenGeneralSettings = {
                                        navController.navigate(WatchRoutes.SETTINGS) {
                                            popUpTo(WatchRoutes.SETTINGS) { inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                WearActionDialog(
                    visible = recordingStartWarning != null,
                    title = "External sensors unavailable",
                    onDismissRequest = appContainer.traceRecordingViewModel::cancelStartRecordingWithUnavailableSensors,
                    buttons =
                        listOf(
                            WearActionDialogButton(
                                text = "Use watch sensors",
                                onClick = {
                                    appContainer.traceRecordingViewModel
                                        .switchUnavailableSensorSourcesToWatchAndStartRecording()
                                },
                            ),
                            WearActionDialogButton(
                                text = "Record anyway",
                                onClick = appContainer.traceRecordingViewModel::confirmStartRecordingWithUnavailableSensors,
                                role = WearActionButtonRole.Secondary,
                            ),
                            WearActionDialogButton(
                                text = "Sources",
                                icon = Icons.Filled.Folder,
                                iconTint = Color(0xFFFFD54F),
                                onClick = {
                                    appContainer.traceRecordingViewModel.cancelStartRecordingWithUnavailableSensors()
                                    navController.navigate(WatchRoutes.RECORDING_SOURCE_SETTINGS) {
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                role = WearActionButtonRole.Secondary,
                            ),
                            WearActionDialogButton(
                                text = "Cancel",
                                onClick = appContainer.traceRecordingViewModel::cancelStartRecordingWithUnavailableSensors,
                                role = WearActionButtonRole.Secondary,
                            ),
                        ),
                ) {
                    Text(
                        text = recordingStartWarning?.message.orEmpty(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                WearActionDialog(
                    visible = recordingLocationStartWarning != null,
                    title = "GPS needed",
                    message = "Wait for a fresh GPS position, then start REC again.",
                    confirmText = "OK",
                    onConfirm = appContainer.traceRecordingViewModel::cancelStartRecordingWithoutLocation,
                    onDismissRequest = appContainer.traceRecordingViewModel::cancelStartRecordingWithoutLocation,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ambientState.refreshDeviceInteractive(fallback = true)
        ScreenStateDiagnostics.updateAppForeground(isForeground = true)
        logScreenTelemetry(event = "activity_resume")
    }

    override fun onPause() {
        ambientState.refreshDeviceInteractive(fallback = false)
        ScreenStateDiagnostics.updateAppForeground(isForeground = false)
        logScreenTelemetry(event = "activity_pause")
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenStateReceiver) }
        thermalTelemetry.unregister()
        val appContainer = (application as GlanceMapWearApp).container
        appContainer.mapViewModel.destroyMapHolder()
        val locationPermissionGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        val traceRecordingState = appContainer.traceRecordingViewModel.uiState.value
        val destroyScreenState =
            resolveLocationScreenState(
                isAmbient = ambientState.isAmbient,
                isDeviceInteractive = ambientState.isDeviceInteractive,
            )
        val destroyRecordingScreenOnGpsEnabled =
            appContainer.settingsViewModel.recordingSampleIntervalSeconds.value !=
                SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
        val destroyRecordingScreenOffGpsEnabled =
            when (appContainer.settingsViewModel.recordingScreenOffSampleIntervalSeconds.value) {
                SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> destroyRecordingScreenOnGpsEnabled
                SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> false
                else -> true
            }
        val destroyRecordingGpsEnabled =
            if (destroyScreenState.isNonInteractive) {
                destroyRecordingScreenOffGpsEnabled
            } else {
                destroyRecordingScreenOnGpsEnabled
            }
        val destroyTurnByTurnScreenOnGpsEnabled =
            appContainer.settingsViewModel.turnByTurnGpsIntervalSeconds.value !=
                SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
        val destroyTurnByTurnScreenOffGpsEnabled =
            when (appContainer.settingsViewModel.turnByTurnScreenOffGpsIntervalSeconds.value) {
                SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> destroyTurnByTurnScreenOnGpsEnabled
                SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> false
                else -> true
            }
        val destroyTurnByTurnGpsEnabled =
            if (destroyScreenState.isNonInteractive) {
                destroyTurnByTurnScreenOffGpsEnabled
            } else {
                destroyTurnByTurnScreenOnGpsEnabled
            }
        val runtimeDemand =
            navigationRuntimeDemand(
                NavigationRuntimeInputs(
                    isNavigateScreen = false,
                    screenState = destroyScreenState,
                    isScreenResumed = false,
                    hasLocationPermission = locationPermissionGranted,
                    offlineMode = appContainer.settingsViewModel.offlineMode.value,
                    generalGpsInAmbient = appContainer.settingsViewModel.gpsInAmbientMode.value,
                    recordingActive = traceRecordingState.active,
                    recordingPaused = traceRecordingState.paused && !traceRecordingState.autoPaused,
                    recordingAutoPaused = traceRecordingState.autoPaused,
                    recordingGpsEnabled = destroyRecordingGpsEnabled,
                    turnByTurnActive = appContainer.gpxViewModel.turnByTurnGuidanceSession.value != null,
                    turnByTurnPaused = appContainer.gpxViewModel.turnByTurnGuidancePaused.value,
                    turnByTurnGpsEnabled = destroyTurnByTurnGpsEnabled,
                    turnByTurnGpsInAmbient = destroyTurnByTurnScreenOffGpsEnabled,
                ),
            )
        if (runtimeDemand.trackingEnabled) {
            DebugTelemetry.log(
                "NavigationRuntime",
                "event=activity_destroy_retaining_gps tracking=true backgroundGps=${runtimeDemand.backgroundGpsEnabled} " +
                    "reason=${runtimeDemand.reason}",
            )
        } else {
            appContainer.locationViewModel.setTrackingEnabled(false)
        }
        super.onDestroy()
    }

    private fun logScreenTelemetry(event: String) {
        ActivityLifecycleTelemetry.logScreen(event, activityTelemetryState(activeRoute))
    }

    private fun logNavigationTelemetry(
        event: String,
        route: String?,
    ) {
        ActivityLifecycleTelemetry.logNavigation(event, activityTelemetryState(route))
    }

    private fun activityTelemetryState(route: String?): ActivityTelemetryState =
        ActivityTelemetryState(
            route = route ?: "unknown",
            ambient = ambientState.isAmbient,
            interactive = getSystemService(PowerManager::class.java)?.isInteractive,
        )
}
