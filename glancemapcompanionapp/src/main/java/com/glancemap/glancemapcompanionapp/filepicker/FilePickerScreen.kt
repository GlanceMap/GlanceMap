@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MaxLineLength",
    "TooManyFunctions",
)

package com.glancemap.glancemapcompanionapp.filepicker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SpatialTracking
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glancemap.glancemapcompanionapp.CompanionAdaptiveSpec
import com.glancemap.glancemapcompanionapp.FileTransferViewModel
import com.glancemap.glancemapcompanionapp.GeneratedPhoneFile
import com.glancemap.glancemapcompanionapp.PrivacyPolicyActivity
import com.glancemap.glancemapcompanionapp.RefugesImportDialog
import com.glancemap.glancemapcompanionapp.RoutingDownloadDialog
import com.glancemap.glancemapcompanionapp.activehike.LiveHikeDashboardScreen
import com.glancemap.glancemapcompanionapp.activehike.PhoneActiveHikeSnapshot
import com.glancemap.glancemapcompanionapp.companionAdaptiveSpec
import com.glancemap.glancemapcompanionapp.livetracking.LiveTrackingScreen
import com.glancemap.glancemapcompanionapp.routes.MissionPlanDayUi
import com.glancemap.glancemapcompanionapp.routes.MissionPlanScreen
import com.glancemap.glancemapcompanionapp.routes.MissionPlanUiState
import com.glancemap.glancemapcompanionapp.routes.MissionPlanViewModel
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRouteDetails
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryScreen
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryViewModel
import com.glancemap.glancemapcompanionapp.routes.RouteWeatherUiState
import com.glancemap.glancemapcompanionapp.routes.TrailIntelligence
import com.glancemap.glancemapcompanionapp.routes.TrailIntelligenceContext
import com.glancemap.glancemapcompanionapp.routes.missionPlanTodaySummary
import com.glancemap.glancemapcompanionapp.routes.trailIntelligenceFor
import com.glancemap.glancemapcompanionapp.weather.weatherConditionText
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private enum class CompanionHomeArea {
    HOME,
    SEND_TO_WATCH,
    LIVE_TRACKING,
    LIVE_HIKE,
    MAP_LEGEND,
    MISSION_PLAN,
    ROUTES,
}

@Composable
fun FilePickerScreen(
    viewModel: FileTransferViewModel,
    routeLibraryViewModel: RouteLibraryViewModel,
    missionPlanViewModel: MissionPlanViewModel,
    openSendToWatchToken: Long = 0L,
    openLiveTrackingToken: Long = 0L,
    watchGpxSaveToken: Long = 0L,
    watchGpxSaveFiles: List<GeneratedPhoneFile> = emptyList(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsState()
    val activeHikeSnapshot by viewModel.activeHikeSnapshot.collectAsState()
    val routeLibraryUiState by routeLibraryViewModel.uiState.collectAsState()
    val selectedRouteDetails by routeLibraryViewModel.selectedRouteDetails.collectAsState()
    val routeWeatherUiState by routeLibraryViewModel.routeWeatherUiState.collectAsState()
    val missionPlanUiState by missionPlanViewModel.uiState.collectAsState()
    val lastTransferGpx =
        remember(uiState.selectedFileUris, uiState.selectedFileDisplayNames) {
            uiState.selectedFileUris
                .zip(uiState.selectedFileDisplayNames)
                .lastOrNull { (_, name) -> name.endsWith(".gpx", ignoreCase = true) }
        }
    val isImportingRefuges by viewModel.isImportingRefuges.collectAsState()
    val poiImportProgress by viewModel.poiImportProgress.collectAsState()
    val isDownloadingRouting by viewModel.isDownloadingRouting.collectAsState()
    val routingDownloadProgress by viewModel.routingDownloadProgress.collectAsState()
    val lastRefugesRequest by viewModel.lastRefugesRequest.collectAsState()
    val lastRoutingRequest by viewModel.lastRoutingRequest.collectAsState()
    val refugesRegionPresets by viewModel.refugesRegionPresets.collectAsState()
    val useDetailedRefugesRegionPresets by viewModel.useDetailedRefugesRegionPresets.collectAsState()
    val watchInstalledMaps by viewModel.watchInstalledMaps.collectAsState()
    val watchInstalledCoverageAreas by viewModel.watchInstalledCoverageAreas.collectAsState()
    val isLoadingWatchInstalledMaps by viewModel.isLoadingWatchInstalledMaps.collectAsState()
    val watchInstalledMapsStatusMessage by viewModel.watchInstalledMapsStatusMessage.collectAsState()
    val lastImportedPoiFile by viewModel.lastImportedPoiFile.collectAsState()
    val lastRoutingDownloadedFiles by viewModel.lastRoutingDownloadedFiles.collectAsState()
    val debugCaptureState by viewModel.debugCaptureState.collectAsState()
    val canRefreshLastRefuges = lastRefugesRequest?.bbox?.isNotBlank() == true
    val canRefreshLastRouting = lastRoutingRequest?.bbox?.isNotBlank() == true

    val autoOpenHelpOnFirstLaunch =
        remember(context) {
            shouldAutoOpenHelpOnFirstLaunch(context)
        }
    var showCancelDialog by remember { mutableStateOf(false) }
    var quickGuideMode by remember { mutableStateOf(QuickGuideMode.GENERAL) }
    var showHowToDialog by remember(autoOpenHelpOnFirstLaunch) { mutableStateOf(autoOpenHelpOnFirstLaunch) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var activeHomeArea by remember {
        mutableStateOf(
            when {
                openLiveTrackingToken != 0L -> CompanionHomeArea.LIVE_TRACKING
                openSendToWatchToken != 0L -> CompanionHomeArea.SEND_TO_WATCH
                else -> CompanionHomeArea.HOME
            },
        )
    }
    var showRefugesDialog by remember { mutableStateOf(false) }
    var showRoutingMenu by remember { mutableStateOf(false) }
    var showThemeLegendMenu by remember { mutableStateOf(false) }
    var showRoutingDialog by remember { mutableStateOf(false) }
    var showManagePhoneFilesDialog by remember { mutableStateOf(false) }
    var showMapSourcesMenu by remember { mutableStateOf(false) }
    var showRefugesMenu by remember { mutableStateOf(false) }
    var pendingSinglePhoneSave by remember { mutableStateOf<GeneratedPhoneFile?>(null) }
    var pendingFolderPhoneSave by remember { mutableStateOf<List<GeneratedPhoneFile>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    var phoneStoredFilesSummary by remember { mutableStateOf(emptyPhoneStoredFilesSummary()) }
    var isLoadingPhoneStoredFiles by remember { mutableStateOf(false) }
    var isClearingPhoneStoredFiles by remember { mutableStateOf(false) }
    var phoneStoredFilesRefreshToken by remember { mutableIntStateOf(0) }

    BackHandler(enabled = activeHomeArea != CompanionHomeArea.HOME) {
        activeHomeArea = CompanionHomeArea.HOME
    }

    LaunchedEffect(openSendToWatchToken) {
        if (openSendToWatchToken != 0L) {
            activeHomeArea = CompanionHomeArea.SEND_TO_WATCH
        }
    }

    LaunchedEffect(openLiveTrackingToken) {
        if (openLiveTrackingToken != 0L) {
            activeHomeArea = CompanionHomeArea.LIVE_TRACKING
        }
    }

    val mapDownloadSources =
        remember {
            listOf(
                ExternalDownloadSource(
                    category = "Topographic maps",
                    label = "OpenAndroMaps (recommended, worldwide)",
                    url = "https://www.openandromaps.org/en/downloads",
                    guidance = "Map downloads > select your area > Download V5 Map: Karte/Map.",
                ),
                ExternalDownloadSource(
                    category = "Topographic maps",
                    label = "OpenHiking (Europe)",
                    url = "https://www.openhiking.eu/en/downloads/mapsforge-maps",
                ),
                ExternalDownloadSource(
                    category = "Non-topographic maps",
                    label = "BBBike",
                    url = "https://extract.bbbike.org/?format=mapsforge-osm.zip",
                    guidance = "Generate a map for your area, then choose format: Mapsforge OSM.",
                ),
                ExternalDownloadSource(
                    category = "Non-topographic maps",
                    label = "Vector City",
                    url = "https://vector.city/",
                ),
                ExternalDownloadSource(
                    category = "Non-topographic maps",
                    label = "Alternativas Libres",
                    url = "https://alternativaslibres.org/en/downloads-mf.php",
                ),
            )
        }
    val themeLegendSources =
        remember {
            listOf(
                ThemeLegendSource(
                    label = "Elevate",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open legend PDF",
                                url = "https://www.openandromaps.org/wp-content/users/tobias/Elevate.pdf",
                            ),
                            ThemeLegendLink(
                                label = "Open theme website",
                                url = "https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Elevate Winter",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open legend PDF",
                                url = "https://www.openandromaps.org/wp-content/users/tobias/Elevate.pdf",
                            ),
                            ThemeLegendLink(
                                label = "Open theme website",
                                url = "https://www.senotto.de/Tipps_Tricks/GPS/OAM_Winter/OAM_Elevate_Winter.htm",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Hike, Ride & Sight",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open legend PDF",
                                url = "http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.pdf",
                            ),
                            ThemeLegendLink(
                                label = "Open theme website",
                                url = "http://j.seydoux.free.fr/locus/hrs.html",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Voluntary",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open legend PDF",
                                url =
                                    "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/" +
                                        "themes/voluntary/downloads/Voluntary%20Key.pdf",
                            ),
                            ThemeLegendLink(
                                label = "Open theme website",
                                url = "https://voluntary.nichesite.org/",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "OS Map",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open day legend PDF",
                                url =
                                    "https://drive.google.com/uc?export=download&" +
                                        "id=1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs",
                            ),
                            ThemeLegendLink(
                                label = "Open night legend PDF",
                                url =
                                    "https://drive.google.com/uc?export=download&" +
                                        "id=1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets",
                            ),
                            ThemeLegendLink(
                                label = "Open theme discussion",
                                url = "https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "OpenHiking",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open theme website",
                                url = "https://www.openhiking.eu/en/downloads/mapsforge-maps",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "French Kiss",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open theme website",
                                url = "https://xctrack.org/AboutMaps.html",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Tiramisu",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open legend PDF",
                                url =
                                    "https://raw.githubusercontent.com/IgorMagellan/Tiramisu/main/" +
                                        "Tiramisu_3_Legend.pdf",
                            ),
                            ThemeLegendLink(
                                label = "Open theme website",
                                url = "https://github.com/IgorMagellan/Tiramisu",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Mapsforge",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = "Open theme website",
                                url =
                                    "https://github.com/mapsforge/mapsforge/tree/master/" +
                                        "mapsforge-themes/src/main/resources/assets",
                            ),
                        ),
                ),
            )
        }
    var selectedThemeLegend by remember { mutableStateOf(themeLegendSources.first()) }
    // --- Permission Handling ---
    var hasNotificationPermission by remember {
        mutableStateOf(
            hasNotificationPermission(context),
        )
    }
    // Wear OS Data Layer discovery/transfer does not need a user-facing Bluetooth grant.
    var hasBluetoothConnectPermission by remember { mutableStateOf(true) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            hasNotificationPermission =
                permissions[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
        }

    val saveSingleFileLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("*/*"),
        ) { destinationUri ->
            val pendingFile = pendingSinglePhoneSave
            pendingSinglePhoneSave = null
            if (destinationUri == null || pendingFile == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val message =
                    withContext(Dispatchers.IO) {
                        saveGeneratedFileToUri(
                            context = context,
                            source = pendingFile,
                            destinationUri = destinationUri,
                        )
                    }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

    val saveFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            val pendingFiles = pendingFolderPhoneSave
            pendingFolderPhoneSave = emptyList()
            if (treeUri == null || pendingFiles.isEmpty()) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val message =
                    withContext(Dispatchers.IO) {
                        saveGeneratedFilesToTree(
                            context = context,
                            files = pendingFiles,
                            treeUri = treeUri,
                        )
                    }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

    val saveGeneratedFilesOnPhone: (List<GeneratedPhoneFile>) -> Unit =
        remember(
            context,
            saveSingleFileLauncher,
            saveFolderLauncher,
        ) {
            { files ->
                when {
                    files.isEmpty() -> {
                        Toast.makeText(context, "No file available to save.", Toast.LENGTH_SHORT).show()
                    }

                    files.size == 1 -> {
                        val file = files.first()
                        pendingFolderPhoneSave = emptyList()
                        pendingSinglePhoneSave = file
                        saveSingleFileLauncher.launch(file.fileName)
                    }

                    else -> {
                        pendingSinglePhoneSave = null
                        pendingFolderPhoneSave = files
                        saveFolderLauncher.launch(null)
                    }
                }
            }
        }

    LaunchedEffect(watchGpxSaveToken) {
        if (watchGpxSaveToken != 0L && watchGpxSaveFiles.isNotEmpty()) {
            saveGeneratedFilesOnPhone(watchGpxSaveFiles)
        }
    }

    val requestMissingPermissions = {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadRefugesDefaults(context)
        if (!autoOpenHelpOnFirstLaunch) {
            requestMissingPermissions()
        }
    }

    LaunchedEffect(activeHomeArea) {
        when (activeHomeArea) {
            CompanionHomeArea.SEND_TO_WATCH -> {
                if (shouldAutoOpenSendToWatchGuide(context)) {
                    markSendToWatchGuideShown(context)
                    quickGuideMode = QuickGuideMode.TRANSFER
                    showHowToDialog = true
                }
            }

            CompanionHomeArea.LIVE_TRACKING -> {
                if (shouldAutoOpenLiveTrackingGuide(context)) {
                    markLiveTrackingGuideShown(context)
                    quickGuideMode = QuickGuideMode.LIVE_TRACKING
                    showHowToDialog = true
                }
            }

            CompanionHomeArea.HOME,
            CompanionHomeArea.LIVE_HIKE,
            CompanionHomeArea.MAP_LEGEND,
            CompanionHomeArea.MISSION_PLAN,
            CompanionHomeArea.ROUTES,
            -> Unit
        }
    }

    LaunchedEffect(showRefugesDialog, showRoutingDialog) {
        if (!showRefugesDialog && !showRoutingDialog) return@LaunchedEffect
        if (showRefugesDialog) {
            viewModel.resetPoiImportProgress()
        }
        if (showRoutingDialog) {
            viewModel.resetRoutingDownloadProgress()
        }
        viewModel.findWatchNodes()
    }

    LaunchedEffect(showRefugesDialog, showRoutingDialog, uiState.selectedWatch?.id) {
        if (!showRefugesDialog && !showRoutingDialog) return@LaunchedEffect
        if (uiState.selectedWatch != null) {
            viewModel.refreshWatchInstalledMaps(
                context = context,
                showToastIfUnavailable = false,
            )
        }
    }

    LaunchedEffect(showManagePhoneFilesDialog, phoneStoredFilesRefreshToken) {
        if (!showManagePhoneFilesDialog) return@LaunchedEffect
        isLoadingPhoneStoredFiles = true
        phoneStoredFilesSummary =
            withContext(Dispatchers.IO) {
                loadPhoneStoredFilesSummary(context)
            }
        isLoadingPhoneStoredFiles = false
    }

    // --- Service Binding with Lifecycle ---
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        viewModel.bindService(context)
                        viewModel.findWatchNodes()
                    }

                    Lifecycle.Event.ON_RESUME -> {
                        hasNotificationPermission = hasNotificationPermission(context)
                        hasBluetoothConnectPermission = true
                    }

                    Lifecycle.Event.ON_STOP -> {
                        viewModel.unbindService(context)
                    }

                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Multi-file Picker ---
    val multiPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult

            // persist best-effort
            uris.forEach {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }

            viewModel.loadFilesFromUris(context, uris)
        }

    // --- File type validation (light UI-side check) ---
    val isAllowedSelection = uiState.selectedFileUris.isNotEmpty()

    val transferSessionActive = uiState.isTransferring || uiState.isPaused
    val uiLocked = transferSessionActive || isImportingRefuges || isDownloadingRouting
    val cancellingTransfer =
        uiState.statusMessage.contains("Cancelling", ignoreCase = true) ||
            uiState.progressText.contains("Stopping current transfer", ignoreCase = true)
    val waitingForReconnect =
        !uiState.isPaused &&
            uiState.progressText.contains("Waiting for watch reconnect", ignoreCase = true)
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val windowWidth = maxWidth
        val windowHeight = maxHeight

        val adaptive =
            remember(windowWidth, windowHeight, fontScale) {
                companionAdaptiveSpec(
                    windowWidth = windowWidth,
                    windowHeight = windowHeight,
                    fontScale = fontScale,
                )
            }
        val isCompactScreen = adaptive.isCompactScreen
        val enablePageScroll = adaptive.enablePageScroll
        val useCompactPageLayout = adaptive.useCompactPageLayout

        val pageScrollState = rememberScrollState()
        val historyListState = rememberLazyListState()

        LaunchedEffect(uiState.history.firstOrNull()?.id) {
            if (uiState.history.isNotEmpty()) {
                historyListState.scrollToItem(0)
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(adaptive.pagePadding),
        ) {
            when (activeHomeArea) {
                CompanionHomeArea.HOME -> {
                    CompanionHomeScreen(
                        adaptive = adaptive,
                        selectedRoute = routeLibraryUiState.selectedRoute,
                        selectedRouteDetails = selectedRouteDetails,
                        activeHikeSnapshot = activeHikeSnapshot,
                        routeWeatherUiState = routeWeatherUiState,
                        missionPlanUiState = missionPlanUiState,
                        debugCaptureActive = debugCaptureState.active,
                        onOpenDebugCapture = { showDebugDialog = true },
                        onOpenRoutes = { activeHomeArea = CompanionHomeArea.ROUTES },
                        onLoadRouteWeather = { snapshot, forceRefresh, plannedStartDistanceMeters ->
                            routeLibraryViewModel.loadRouteWeather(
                                activeHikeSnapshot = snapshot,
                                forceRefresh = forceRefresh,
                                plannedStartDistanceMeters = plannedStartDistanceMeters,
                            )
                        },
                        onSendSelectedRouteToWatch = {
                            val plannedDay = missionPlanUiState.selectedDay
                            if (plannedDay?.route?.id == routeLibraryUiState.selectedRoute?.id) {
                                missionPlanViewModel.prepareSelectedDayForTransfer { routeUri ->
                                    if (routeUri == null) {
                                        Toast
                                            .makeText(
                                                context,
                                                "The planned GPX is no longer available.",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    } else {
                                        viewModel.loadFilesFromUris(context, listOf(routeUri))
                                        activeHomeArea = CompanionHomeArea.SEND_TO_WATCH
                                    }
                                }
                            } else {
                                val routeUri = routeLibraryViewModel.selectedRouteContentUri()
                                if (routeUri == null) {
                                    Toast
                                        .makeText(
                                            context,
                                            "The selected GPX is no longer available.",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                } else {
                                    viewModel.loadFilesFromUris(context, listOf(routeUri))
                                    activeHomeArea = CompanionHomeArea.SEND_TO_WATCH
                                }
                            }
                        },
                        onOpenMissionPlan = { activeHomeArea = CompanionHomeArea.MISSION_PLAN },
                        onOpenLiveHike = { activeHomeArea = CompanionHomeArea.LIVE_HIKE },
                        onOpenSendToWatch = { activeHomeArea = CompanionHomeArea.SEND_TO_WATCH },
                        onOpenLiveTracking = { activeHomeArea = CompanionHomeArea.LIVE_TRACKING },
                        onOpenMapLegend = { activeHomeArea = CompanionHomeArea.MAP_LEGEND },
                        onOpenQuickGuide = {
                            quickGuideMode = QuickGuideMode.GENERAL
                            showHowToDialog = true
                        },
                        onOpenCreditsLegal = {
                            context.startActivity(PrivacyPolicyActivity.creditsAndLegalIntent(context))
                        },
                    )
                }

                CompanionHomeArea.ROUTES -> {
                    RouteLibraryScreen(
                        uiState = routeLibraryUiState,
                        onBack = { activeHomeArea = CompanionHomeArea.HOME },
                        onImportRoute = routeLibraryViewModel::importRoute,
                        onSelectRoute = routeLibraryViewModel::selectRoute,
                        onSendToWatch = { route ->
                            routeLibraryViewModel.selectRoute(route.id)
                            val routeUri = routeLibraryViewModel.contentUriFor(route.id)
                            if (routeUri == null) {
                                Toast
                                    .makeText(
                                        context,
                                        "The selected GPX is no longer available.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            } else {
                                viewModel.loadFilesFromUris(context, listOf(routeUri))
                                activeHomeArea = CompanionHomeArea.SEND_TO_WATCH
                            }
                        },
                    )
                }

                CompanionHomeArea.MISSION_PLAN -> {
                    MissionPlanScreen(
                        uiState = missionPlanUiState,
                        routes = routeLibraryUiState.routes,
                        onBack = { activeHomeArea = CompanionHomeArea.HOME },
                        onAddDay = missionPlanViewModel::addDay,
                        onSetToday = { dayUi ->
                            missionPlanViewModel.selectDay(dayUi.day.id)
                            routeLibraryViewModel.selectRoute(dayUi.route.id)
                            activeHomeArea = CompanionHomeArea.HOME
                        },
                        onUpdateDay = missionPlanViewModel::updateDay,
                        onMoveDay = missionPlanViewModel::moveDay,
                        onRemoveDay = missionPlanViewModel::removeDay,
                        onOpenRoutes = { activeHomeArea = CompanionHomeArea.ROUTES },
                        onRetry = missionPlanViewModel::refresh,
                    )
                }

                CompanionHomeArea.LIVE_HIKE -> {
                    activeHikeSnapshot?.let { update ->
                        LiveHikeDashboardScreen(
                            update = update,
                            onBack = { activeHomeArea = CompanionHomeArea.HOME },
                        )
                    }
                }

                CompanionHomeArea.LIVE_TRACKING -> {
                    LiveTrackingScreen(
                        onBack = { activeHomeArea = CompanionHomeArea.HOME },
                        onOpenQuickGuide = {
                            quickGuideMode = QuickGuideMode.LIVE_TRACKING
                            showHowToDialog = true
                        },
                        lastTransferGpxUri = lastTransferGpx?.first,
                        lastTransferGpxName = lastTransferGpx?.second.orEmpty(),
                    )
                }

                CompanionHomeArea.MAP_LEGEND -> {
                    CompanionMapLegendScreen(
                        adaptive = adaptive,
                        selectedThemeLegend = selectedThemeLegend,
                        themeLegendSources = themeLegendSources,
                        showThemeLegendMenu = showThemeLegendMenu,
                        onShowThemeLegendMenuChange = { showThemeLegendMenu = it },
                        onThemeLegendSelected = { selectedThemeLegend = it },
                        onOpenLink = { openCompanionUrl(context, it) },
                        onOpenHelp = {
                            quickGuideMode = QuickGuideMode.MAP_LEGEND
                            showHowToDialog = true
                        },
                        onBack = { activeHomeArea = CompanionHomeArea.HOME },
                    )
                }

                CompanionHomeArea.SEND_TO_WATCH -> {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = adaptive.helpIconButtonSize),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(
                            onClick = { activeHomeArea = CompanionHomeArea.HOME },
                            modifier = Modifier.size(adaptive.helpIconButtonSize),
                            colors = companionTonalIconButtonColors(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to home",
                                modifier = Modifier.size(adaptive.helpIconSize),
                            )
                        }
                        Text(
                            text = "Send to Watch",
                            style =
                                if (isCompactScreen) {
                                    MaterialTheme.typography.titleSmall
                                } else {
                                    MaterialTheme.typography.headlineSmall
                                },
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalIconButton(
                            onClick = {
                                quickGuideMode = QuickGuideMode.TRANSFER
                                showHowToDialog = true
                            },
                            modifier = Modifier.size(adaptive.helpIconButtonSize),
                            colors = companionTonalIconButtonColors(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = "Quick Guide",
                                modifier = Modifier.size(adaptive.helpIconSize),
                            )
                        }
                    }

                    if (showManagePhoneFilesDialog) {
                        ManagePhoneFilesDialog(
                            context = context,
                            viewModel = viewModel,
                            uiState = uiState,
                            uiLocked = uiLocked,
                            isLoadingPhoneStoredFiles = isLoadingPhoneStoredFiles,
                            isClearingPhoneStoredFiles = isClearingPhoneStoredFiles,
                            onIsClearingPhoneStoredFilesChange = { isClearingPhoneStoredFiles = it },
                            phoneStoredFilesSummary = phoneStoredFilesSummary,
                            onRefreshRequested = { phoneStoredFilesRefreshToken += 1 },
                            onDismiss = { showManagePhoneFilesDialog = false },
                            coroutineScope = coroutineScope,
                        )
                    }

                    Spacer(modifier = Modifier.height(adaptive.titleGap))

                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    ) {
                        Column(
                            modifier =
                                if (enablePageScroll) {
                                    Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(pageScrollState)
                                        .padding(end = 10.dp)
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                        ) {
                            FilePickerDownloadSection(
                                context = context,
                                adaptive = adaptive,
                                uiLocked = uiLocked,
                                hasNotificationPermission = hasNotificationPermission,
                                hasBluetoothConnectPermission = hasBluetoothConnectPermission,
                                canRefreshLastRefuges = canRefreshLastRefuges,
                                canRefreshLastRouting = canRefreshLastRouting,
                                mapDownloadSources = mapDownloadSources,
                                showMapSourcesMenu = showMapSourcesMenu,
                                onShowMapSourcesMenuChange = { showMapSourcesMenu = it },
                                showRefugesMenu = showRefugesMenu,
                                onShowRefugesMenuChange = { showRefugesMenu = it },
                                showRoutingMenu = showRoutingMenu,
                                onShowRoutingMenuChange = { showRoutingMenu = it },
                                onRequestMissingPermissions = requestMissingPermissions,
                                onShowManagePhoneFiles = { showManagePhoneFilesDialog = true },
                                onShowRefugesDialog = { showRefugesDialog = true },
                                onShowRoutingDialog = { showRoutingDialog = true },
                                onRefreshLastRefuges = {
                                    viewModel.refreshLastRefuges(
                                        context = context,
                                        appendToSelection = true,
                                    )
                                },
                                onRefreshLastRouting = {
                                    viewModel.refreshLastRouting(
                                        context = context,
                                        appendToSelection = true,
                                    )
                                },
                            )

                            Spacer(modifier = Modifier.height(adaptive.sectionGap))

                            SectionCard(
                                title = "2. Select files (.gpx / .map / .poi / .rd5 / .hgt / .hgt.gz)",
                                headerAction = {
                                    TextButton(
                                        onClick = { viewModel.clearSelectedFiles() },
                                        enabled = uiState.selectedFileUris.isNotEmpty() && !uiLocked,
                                    ) {
                                        Text("Clear")
                                    }
                                },
                                modifier =
                                    if (useCompactPageLayout) {
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 120.dp)
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                                ) {
                                    Button(
                                        onClick = { multiPickerLauncher.launch(arrayOf("*/*")) },
                                        enabled = !uiLocked,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Select file(s)")
                                    }
                                    SelectedFilesCompactSummary(
                                        fileNames = uiState.selectedFileDisplayNames,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(adaptive.sectionGap))

                            SectionCard(
                                title = "3. Select watch",
                                headerAction = {
                                    IconButton(
                                        onClick = { viewModel.findWatchNodes() },
                                        enabled = !uiLocked,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Refresh Watch List",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                                modifier =
                                    if (useCompactPageLayout) {
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 110.dp)
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (uiState.availableWatches.isEmpty()) {
                                        Text("No watches found.", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            uiState.availableWatches.forEach { watch ->
                                                val isSelected = uiState.selectedWatch?.id == watch.id
                                                Button(
                                                    onClick = { viewModel.onWatchSelected(context, watch) },
                                                    enabled = !uiLocked,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors =
                                                        ButtonDefaults.buttonColors(
                                                            containerColor =
                                                                if (isSelected) {
                                                                    Color(0xFF4CAF50)
                                                                } else {
                                                                    MaterialTheme.colorScheme.primary
                                                                },
                                                        ),
                                                ) {
                                                    Text(
                                                        text = watch.displayName,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(adaptive.sectionGap))

                            FilePickerTransferSection(
                                adaptive = adaptive,
                                uiState = uiState,
                                uiLocked = uiLocked,
                                isAllowedSelection = isAllowedSelection,
                                transferSessionActive = transferSessionActive,
                                cancellingTransfer = cancellingTransfer,
                                waitingForReconnect = waitingForReconnect,
                                debugCaptureState = debugCaptureState,
                                onSend = { viewModel.sendFiles(context) },
                                onResume = { viewModel.resumeTransfer() },
                                onPause = { viewModel.pauseTransfer() },
                                onCancelRequested = { showCancelDialog = true },
                            )

                            Spacer(modifier = Modifier.height(adaptive.sectionGap))

                            FilePickerHistorySection(
                                adaptive = adaptive,
                                uiState = uiState,
                                historyListState = historyListState,
                                onClearHistory = { viewModel.clearHistory() },
                            )
                        }
                        if (enablePageScroll) {
                            PageScrollbar(
                                scrollState = pageScrollState,
                                modifier =
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight(),
                            )
                        }
                    }
                }
            }

            if (showDebugDialog) {
                DebugCaptureDialog(
                    context = context,
                    viewModel = viewModel,
                    debugCaptureState = debugCaptureState,
                    onDismiss = { showDebugDialog = false },
                )
            }

            if (showHowToDialog) {
                FilePickerQuickGuideDialog(
                    adaptive = adaptive,
                    mode = quickGuideMode,
                    onDismiss = {
                        if (autoOpenHelpOnFirstLaunch && quickGuideMode == QuickGuideMode.GENERAL) {
                            markHelpShown(context)
                        }
                        showHowToDialog = false
                    },
                )
            }

            if (showCancelDialog) {
                CancelTransferDialog(
                    onConfirm = {
                        viewModel.cancelTransfer()
                        showCancelDialog = false
                    },
                    onDismiss = { showCancelDialog = false },
                )
            }

            if (showRefugesDialog) {
                RefugesImportDialog(
                    context = context,
                    adaptive = adaptive,
                    viewModel = viewModel,
                    uiState = uiState,
                    isImportingRefuges = isImportingRefuges,
                    poiImportProgress = poiImportProgress,
                    lastRefugesRequest = lastRefugesRequest,
                    refugesRegionPresets = refugesRegionPresets,
                    useDetailedRefugesRegionPresets = useDetailedRefugesRegionPresets,
                    onUseDetailedRefugesRegionPresetsChange = { enabled ->
                        viewModel.setUseDetailedRefugesRegionPresets(context, enabled)
                    },
                    watchInstalledMaps = watchInstalledMaps,
                    watchInstalledCoverageAreas = watchInstalledCoverageAreas,
                    isLoadingWatchInstalledMaps = isLoadingWatchInstalledMaps,
                    watchInstalledMapsStatusMessage = watchInstalledMapsStatusMessage,
                    lastImportedPoiFile = lastImportedPoiFile,
                    saveGeneratedFilesOnPhone = saveGeneratedFilesOnPhone,
                    onDismiss = { showRefugesDialog = false },
                )
            }

            if (showRoutingDialog) {
                RoutingDownloadDialog(
                    context = context,
                    adaptive = adaptive,
                    viewModel = viewModel,
                    uiState = uiState,
                    isDownloadingRouting = isDownloadingRouting,
                    routingDownloadProgress = routingDownloadProgress,
                    watchInstalledMaps = watchInstalledMaps,
                    watchInstalledCoverageAreas = watchInstalledCoverageAreas,
                    isLoadingWatchInstalledMaps = isLoadingWatchInstalledMaps,
                    watchInstalledMapsStatusMessage = watchInstalledMapsStatusMessage,
                    lastRoutingDownloadedFiles = lastRoutingDownloadedFiles,
                    saveGeneratedFilesOnPhone = saveGeneratedFilesOnPhone,
                    onDismiss = { showRoutingDialog = false },
                )
            }
        }
    }
}

@Composable
private fun SelectedFilesCompactSummary(
    fileNames: List<String>,
    modifier: Modifier = Modifier,
) {
    var showAllFiles by remember { mutableStateOf(false) }

    if (fileNames.isEmpty()) {
        Text(
            text = "No file selected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileNames.first(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (fileNames.size > 1) {
            TextButton(
                onClick = { showAllFiles = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("+${fileNames.size - 1} more")
            }
        }
    }

    if (showAllFiles) {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showAllFiles = false },
            title = { Text("${fileNames.size} selected files") },
            text = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        fileNames.forEachIndexed { index, fileName ->
                            Text(
                                text = "${index + 1}. $fileName",
                                style = MaterialTheme.typography.bodySmall,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (scrollState.maxValue > 0) {
                        PageScrollbar(
                            scrollState = scrollState,
                            modifier =
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAllFiles = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun CompanionHomeScreen(
    adaptive: CompanionAdaptiveSpec,
    selectedRoute: RouteLibraryRoute?,
    selectedRouteDetails: RouteLibraryRouteDetails?,
    activeHikeSnapshot: PhoneActiveHikeSnapshot?,
    routeWeatherUiState: RouteWeatherUiState,
    missionPlanUiState: MissionPlanUiState,
    debugCaptureActive: Boolean,
    onOpenDebugCapture: () -> Unit,
    onOpenRoutes: () -> Unit,
    onLoadRouteWeather: (ActiveHikeSnapshot?, Boolean, Double) -> Unit,
    onSendSelectedRouteToWatch: () -> Unit,
    onOpenMissionPlan: () -> Unit,
    onOpenLiveHike: () -> Unit,
    onOpenSendToWatch: () -> Unit,
    onOpenLiveTracking: () -> Unit,
    onOpenMapLegend: () -> Unit,
    onOpenQuickGuide: () -> Unit,
    onOpenCreditsLegal: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier =
            Modifier
                .fillMaxSize(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = adaptive.helpIconButtonSize),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onOpenDebugCapture,
                modifier = Modifier.size(adaptive.helpIconButtonSize),
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor =
                            if (debugCaptureActive) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        contentColor =
                            if (debugCaptureActive) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                    ),
            ) {
                Icon(
                    imageVector = Icons.Filled.BugReport,
                    contentDescription =
                        if (debugCaptureActive) {
                            "Stop phone debug capture"
                        } else {
                            "Start phone debug capture"
                        },
                    modifier = Modifier.size(adaptive.helpIconSize),
                )
            }
            Text(
                text = "GlanceMap Companion",
                style =
                    if (adaptive.isCompactScreen) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineMedium
                    },
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = onOpenQuickGuide,
                modifier = Modifier.size(adaptive.helpIconButtonSize),
                colors = companionTonalIconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = "Quick Guide",
                    modifier = Modifier.size(adaptive.helpIconSize),
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap),
            ) {
                TodayHikeCard(
                    selectedRoute = selectedRoute,
                    selectedRouteDetails = selectedRouteDetails,
                    activeHikeSnapshot = activeHikeSnapshot,
                    routeWeatherUiState = routeWeatherUiState,
                    missionDay =
                        missionPlanUiState.selectedDay?.takeIf { dayUi ->
                            dayUi.route.id == selectedRoute?.id
                        },
                    isPreparingMissionTransfer = missionPlanUiState.isPreparingTransfer,
                    onOpenRoutes = onOpenRoutes,
                    onLoadRouteWeather = onLoadRouteWeather,
                    onSendSelectedRouteToWatch = onSendSelectedRouteToWatch,
                )

                Button(
                    onClick = onOpenSendToWatch,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.SendToMobile,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Transfer files",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Maps, GPX, POI and routing files",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HomeActionButton(
                    icon = Icons.Filled.SpatialTracking,
                    title = "Live Hike Dashboard",
                    description = "See live progress from the watch",
                    onClick = onOpenLiveHike,
                    enabled =
                        activeHikeSnapshot?.snapshot?.let { snapshot ->
                            snapshot.phase != ActiveHikePhase.IDLE && snapshot.phase != ActiveHikePhase.FINISHED
                        } == true,
                )
                HomeActionButton(
                    icon = Icons.Filled.CalendarMonth,
                    title = "Mission Plan",
                    description =
                        missionPlanUiState.selectedDay?.let { dayUi ->
                            "Day ${dayUi.day.dayNumber}: ${dayUi.route.title}"
                        } ?: "Plan multiple hiking days",
                    onClick = onOpenMissionPlan,
                )
                HomeActionButton(
                    icon = Icons.Filled.SpatialTracking,
                    title = "Live Tracking",
                    description = "Share your GPS location",
                    onClick = onOpenLiveTracking,
                )
                HomeActionButton(
                    icon = Icons.Filled.Map,
                    title = "Map Legend",
                    description = "Open theme legends and reference pages",
                    onClick = onOpenMapLegend,
                )
                HomeActionButton(
                    icon = Icons.Filled.Gavel,
                    title = "Credits & Legal",
                    description = "Privacy, licences and acknowledgements",
                    onClick = onOpenCreditsLegal,
                )
            }
        }
    }
}

@Composable
private fun TodayHikeCard(
    selectedRoute: RouteLibraryRoute?,
    selectedRouteDetails: RouteLibraryRouteDetails?,
    activeHikeSnapshot: PhoneActiveHikeSnapshot?,
    routeWeatherUiState: RouteWeatherUiState,
    missionDay: MissionPlanDayUi?,
    isPreparingMissionTransfer: Boolean,
    onOpenRoutes: () -> Unit,
    onLoadRouteWeather: (ActiveHikeSnapshot?, Boolean, Double) -> Unit,
    onSendSelectedRouteToWatch: () -> Unit,
) {
    val liveHikeSnapshot =
        activeHikeSnapshot?.takeIf { update -> update.snapshot.phase != ActiveHikePhase.IDLE }
    val trailIntelligence =
        liveHikeSnapshot?.snapshot?.let { snapshot -> selectedRouteDetails?.trailIntelligenceFor(snapshot) }
            ?: missionDay
                ?.takeIf { dayUi -> dayUi.route.id == selectedRoute?.id }
                ?.let { dayUi -> selectedRouteDetails?.trailIntelligenceFor(dayUi.day) }
    SectionCard(
        title = "TODAY'S HIKE",
    ) {
        liveHikeSnapshot?.let { update -> ActiveHikeBriefing(update) }
        trailIntelligence?.let { intelligence ->
            Spacer(modifier = Modifier.height(14.dp))
            TrailIntelligenceBriefing(
                intelligence = intelligence,
                weatherUiState = routeWeatherUiState,
            )
        }
        if (liveHikeSnapshot != null) {
            Spacer(modifier = Modifier.height(14.dp))
        }
        if (selectedRoute == null) {
            Text(
                text = "Choose a GPX route to prepare a briefing before you leave.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenRoutes,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Choose a route")
            }
        } else {
            missionDay?.let { dayUi ->
                Text(
                    text = "DAY ${dayUi.day.dayNumber} • MISSION PLAN",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = selectedRoute.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = missionDay?.missionPlanTodaySummary() ?: todayRouteSummary(selectedRoute),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (trailIntelligence == null) {
                Text(
                    text = firstThirtyMinutesBriefing(selectedRoute),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (selectedRouteDetails != null) {
                RouteWeatherBriefing(
                    uiState = routeWeatherUiState,
                    onLoad = { forceRefresh ->
                        onLoadRouteWeather(
                            activeHikeSnapshot?.snapshot,
                            forceRefresh,
                            missionDay?.day?.startDistanceMeters ?: 0.0,
                        )
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Button(
                onClick = onSendSelectedRouteToWatch,
                enabled = !isPreparingMissionTransfer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.SendToMobile,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isPreparingMissionTransfer) {
                        "Preparing day GPX…"
                    } else if (missionDay != null) {
                        "Send planned day to watch"
                    } else {
                        "Send selected route to watch"
                    },
                )
            }
            TextButton(
                onClick = onOpenRoutes,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Change route")
            }
        }
    }
}

@Composable
private fun ActiveHikeBriefing(update: PhoneActiveHikeSnapshot) {
    val snapshot = update.snapshot
    Text(
        text = "LIVE FROM WATCH",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = snapshot.routeTitle ?: "Active hike",
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = activeHikeStatusText(snapshot.phase, snapshot.offRoute),
        color =
            if (snapshot.offRoute) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        style = MaterialTheme.typography.bodyMedium,
    )
    val metrics =
        listOfNotNull(
            snapshot.distanceRemainingMeters?.let { distance -> "${distance.toKilometersText()} left" },
            snapshot.estimatedRemainingSeconds?.let { duration -> "${duration.toDouble().toDurationText()} remaining" },
            snapshot.remainingAscentMeters?.let { ascent -> "+${ascent.toInt()} m to climb" },
        )
    if (metrics.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = metrics.joinToString("  •  "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TrailIntelligenceBriefing(
    intelligence: TrailIntelligence,
    weatherUiState: RouteWeatherUiState,
) {
    val window = intelligence.window
    val remainingMinutes = (window.estimatedDurationSeconds / 60.0).toInt().coerceAtLeast(1)
    Text(
        text = intelligence.trailIntelligenceTitle(remainingMinutes),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(modifier = Modifier.height(4.dp))
    val metrics =
        listOfNotNull(
            window.distanceMeters.toKilometersText(),
            window.ascentMeters.takeIf { it > 0.0 }?.let { ascent -> "+${ascent.toInt()} m climb" },
            window.descentMeters.takeIf { it > 0.0 }?.let { descent -> "−${descent.toInt()} m descent" },
        )
    Text(
        text = metrics.joinToString("  •  "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    intelligence.upcomingWaypoints.forEach { waypoint ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Up next: ${waypoint.title} in ${waypoint.distanceAheadMeters.toKilometersText()}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    TrailIntelligenceWeatherBriefing(weatherUiState)
}

@Composable
private fun TrailIntelligenceWeatherBriefing(uiState: RouteWeatherUiState) {
    Text(
        text = "WEATHER",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    when {
        uiState.isLoading -> {
            Text(
                text = "Loading route forecast…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        uiState.forecast == null -> {
            Text(
                text = "Weather is not loaded for this route segment.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        else -> {
            val forecast = checkNotNull(uiState.forecast)
            val nextHour = forecast.nextHour
            val metrics =
                listOfNotNull(
                    forecast.current.temperatureCelsius?.let { temperature -> temperature.toWeatherTemperatureText() },
                    nextHour?.precipitationProbabilityPercent?.let { probability ->
                        "rain ${probability.roundToInt()}%"
                    },
                    nextHour?.windGustKilometersPerHour?.let { gust ->
                        "gusts ${gust.roundToInt()} km/h"
                    },
                )
            Text(
                text =
                    "${weatherConditionText(forecast.current.weatherCode)}" +
                        metrics
                            .takeIf { values -> values.isNotEmpty() }
                            ?.let { values -> " • ${values.joinToString("  •  ")}" }
                            .orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    "Updated ${forecast.fetchedAtEpochMillis.toWeatherUpdatedText()}" +
                        if (uiState.isStale) " • cached, unable to update" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RouteWeatherBriefing(
    uiState: RouteWeatherUiState,
    onLoad: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Text(
        text = "ROUTE WEATHER",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    when {
        uiState.isLoading -> {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Loading route forecast…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        uiState.forecast == null -> {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Load a forecast for this route. Weather is context, not a safety decision.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            uiState.message?.let { message ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { onLoad(false) }) {
                Text("Load route weather")
            }
        }

        else -> {
            val forecast = checkNotNull(uiState.forecast)
            val outlook = forecast.nextHour
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${forecast.location.label} • ${weatherConditionText(forecast.current.weatherCode)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    "Updated ${forecast.fetchedAtEpochMillis.toWeatherUpdatedText()}" +
                        " • ${uiState.savedSnapshotCount} saved snapshot${if (uiState.savedSnapshotCount == 1) "" else "s"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            val currentMetrics =
                listOfNotNull(
                    forecast.current.temperatureCelsius?.let { temperature -> temperature.toWeatherTemperatureText() },
                    forecast.current.apparentTemperatureCelsius?.let { apparent ->
                        "feels ${apparent.toWeatherTemperatureText()}"
                    },
                    forecast.current.windSpeedKilometersPerHour?.let { wind ->
                        "wind ${wind.roundToInt()} km/h"
                    },
                    forecast.current.windGustKilometersPerHour?.let { gust ->
                        "gusts ${gust.roundToInt()} km/h"
                    },
                )
            if (currentMetrics.isNotEmpty()) {
                Text(
                    text = currentMetrics.joinToString("  •  "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val nextHourMetrics =
                listOfNotNull(
                    outlook?.precipitationProbabilityPercent?.let { probability ->
                        "rain ${probability.roundToInt()}%"
                    },
                    outlook?.windGustKilometersPerHour?.let { gust ->
                        "gusts ${gust.roundToInt()} km/h"
                    },
                    outlook?.visibilityMeters?.let { visibility ->
                        "visibility ${visibility.toKilometersText()}"
                    },
                    outlook?.freezingLevelHeightMeters?.let { height ->
                        "freezing ${height.roundToInt()} m"
                    },
                )
            if (nextHourMetrics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Next hour • ${nextHourMetrics.joinToString("  •  ")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (forecast.daily.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "OUTLOOK",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                forecast.daily.take(3).forEach { day ->
                    val dayMetrics =
                        listOfNotNull(
                            day.minimumTemperatureCelsius?.let { value -> "${value.toWeatherTemperatureText()} low" },
                            day.maximumTemperatureCelsius?.let { value -> "${value.toWeatherTemperatureText()} high" },
                            day.precipitationProbabilityPercent?.let { value -> "rain ${value.roundToInt()}%" },
                            day.windGustKilometersPerHour?.let { value -> "gusts ${value.roundToInt()} km/h" },
                        )
                    Text(
                        text =
                            "${day.date} • ${weatherConditionText(day.weatherCode)}" +
                                dayMetrics
                                    .takeIf { metrics -> metrics.isNotEmpty() }
                                    ?.let { metrics -> " • ${metrics.joinToString("  •  ")}" }
                                    .orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (uiState.isStale) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Showing a cached forecast — unable to update.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            uiState.message?.let { message ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { onLoad(true) }) {
                Text(if (uiState.isCached) "Update weather" else "Refresh weather")
            }
        }
    }
    TextButton(onClick = { openCompanionUrl(context, OPEN_METEO_URL) }) {
        Text("Weather data by Open-Meteo.com")
    }
}

private fun Double.toWeatherTemperatureText(): String = "${roundToInt()}°C"

private fun Long.toWeatherUpdatedText(): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))

private fun TrailIntelligence.trailIntelligenceTitle(remainingMinutes: Int): String {
    val windowLabel = if (remainingMinutes >= 30) "NEXT 30 MINUTES" else "TO FINISH • NEXT $remainingMinutes MIN"
    return if (context == TrailIntelligenceContext.PLANNED_DAY) "DAY START • $windowLabel" else windowLabel
}

private const val OPEN_METEO_URL = "https://open-meteo.com/"

private fun activeHikeStatusText(
    phase: ActiveHikePhase,
    offRoute: Boolean,
): String {
    if (offRoute) return "Off route — check the watch"
    return when (phase) {
        ActiveHikePhase.WAITING_FOR_LOCATION -> "Waiting for a GPS position"
        ActiveHikePhase.TO_START -> "Heading to the route start"
        ActiveHikePhase.FOLLOWING_ROUTE -> "Following the route"
        ActiveHikePhase.PAUSED -> "Navigation paused on watch"
        ActiveHikePhase.FINISHED -> "Route complete"
        ActiveHikePhase.RECORDING -> "Recording on watch"
        ActiveHikePhase.RECORDING_PAUSED -> "Recording paused on watch"
        ActiveHikePhase.IDLE -> "No active hike"
    }
}

private fun todayRouteSummary(route: RouteLibraryRoute): String =
    listOf(
        route.summary.distanceMeters.toKilometersText(),
        "+${route.summary.elevationGainMeters.toInt()} m",
        route.summary.estimatedDurationSeconds.toDurationText(),
    ).joinToString("  •  ")

private fun firstThirtyMinutesBriefing(route: RouteLibraryRoute): String =
    "First 30 min  •  ${route.summary.firstThirtyMinutesDistanceMeters.toKilometersText()}  •  " +
        "+${route.summary.firstThirtyMinutesAscentMeters.toInt()} m climb"

private fun Double.toKilometersText(): String =
    if (this < 1_000.0) {
        "${toInt()} m"
    } else {
        "%.1f km".format(this / 1_000.0)
    }

private fun Double.toDurationText(): String {
    val totalMinutes = (this / 60.0).toInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0) "$minutes min" else "$hours h ${minutes.toString().padStart(2, '0')} min"
}

@Composable
private fun HomeActionButton(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun companionTonalIconButtonColors() =
    IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

@Composable
private fun CompanionMapLegendScreen(
    adaptive: CompanionAdaptiveSpec,
    selectedThemeLegend: ThemeLegendSource,
    themeLegendSources: List<ThemeLegendSource>,
    showThemeLegendMenu: Boolean,
    onShowThemeLegendMenuChange: (Boolean) -> Unit,
    onThemeLegendSelected: (ThemeLegendSource) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenHelp: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = adaptive.helpIconButtonSize),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onBack,
                modifier = Modifier.size(adaptive.helpIconButtonSize),
                colors = companionTonalIconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to home",
                    modifier = Modifier.size(adaptive.helpIconSize),
                )
            }
            Text(
                text = "Map Legend",
                style =
                    if (adaptive.isCompactScreen) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = onOpenHelp,
                modifier = Modifier.size(adaptive.helpIconButtonSize),
                colors = companionTonalIconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = "Quick Guide",
                    modifier = Modifier.size(adaptive.helpIconSize),
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap),
        ) {
            SectionCard(
                title = "Theme legend",
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Select a bundled theme and open its legend or reference page.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { onShowThemeLegendMenuChange(true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = selectedThemeLegend.label,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = Icons.Default.UnfoldMore,
                                contentDescription = "Select theme",
                            )
                        }
                        DropdownMenu(
                            expanded = showThemeLegendMenu,
                            onDismissRequest = { onShowThemeLegendMenuChange(false) },
                        ) {
                            themeLegendSources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.label) },
                                    onClick = {
                                        onThemeLegendSelected(source)
                                        onShowThemeLegendMenuChange(false)
                                    },
                                )
                            }
                        }
                    }
                    if (selectedThemeLegend.links.isEmpty()) {
                        Text(
                            text = "No public legend link found yet for this theme.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        selectedThemeLegend.links.forEach { link ->
                            OutlinedButton(
                                onClick = { onOpenLink(link.url) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(link.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openCompanionUrl(
    context: Context,
    url: String,
) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure { error ->
        Log.w("FilePickerScreen", "Unable to open URL: $url", error)
        Toast.makeText(context, "Unable to open link.", Toast.LENGTH_SHORT).show()
    }
}
