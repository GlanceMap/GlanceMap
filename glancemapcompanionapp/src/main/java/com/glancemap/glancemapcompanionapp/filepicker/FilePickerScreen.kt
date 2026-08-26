@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.RefugesImportDialog
import com.glancemap.glancemapcompanionapp.RoutingDownloadDialog
import com.glancemap.glancemapcompanionapp.companionAdaptiveSpec
import com.glancemap.glancemapcompanionapp.livetracking.LiveTrackingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CompanionHomeArea {
    HOME,
    SEND_TO_WATCH,
    LIVE_TRACKING,
    MAP_LEGEND,
}

@Composable
fun FilePickerScreen(
    viewModel: FileTransferViewModel,
    openSendToWatchToken: Long = 0L,
    openLiveTrackingToken: Long = 0L,
    watchGpxSaveToken: Long = 0L,
    watchGpxSaveFiles: List<GeneratedPhoneFile> = emptyList(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsState()
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

    val topographicMapsCategory = stringResource(R.string.map_sources_category_topographic)
    val nonTopographicMapsCategory = stringResource(R.string.map_sources_category_non_topographic)
    val openAndroMapsLabel = stringResource(R.string.map_source_open_andro_maps)
    val openAndroMapsGuidance = stringResource(R.string.map_source_open_andro_maps_guidance)
    val openHikingLabel = stringResource(R.string.map_source_open_hiking)
    val bbbikeLabel = stringResource(R.string.map_source_bbbike)
    val bbbikeGuidance = stringResource(R.string.map_source_bbbike_guidance)
    val vectorCityLabel = stringResource(R.string.map_source_vector_city)
    val alternativasLibresLabel = stringResource(R.string.map_source_alternativas_libres)
    val openLegendPdfLabel = stringResource(R.string.map_legend_action_open_pdf)
    val openThemeWebsiteLabel = stringResource(R.string.map_legend_action_open_website)
    val openDayLegendPdfLabel = stringResource(R.string.map_legend_action_open_day_pdf)
    val openNightLegendPdfLabel = stringResource(R.string.map_legend_action_open_night_pdf)
    val openThemeDiscussionLabel = stringResource(R.string.map_legend_action_open_discussion)
    val mapDownloadSources =
        remember(
            topographicMapsCategory,
            nonTopographicMapsCategory,
            openAndroMapsLabel,
            openAndroMapsGuidance,
            openHikingLabel,
            bbbikeLabel,
            bbbikeGuidance,
            vectorCityLabel,
            alternativasLibresLabel,
        ) {
            listOf(
                ExternalDownloadSource(
                    category = topographicMapsCategory,
                    label = openAndroMapsLabel,
                    url = "https://www.openandromaps.org/en/downloads",
                    guidance = openAndroMapsGuidance,
                ),
                ExternalDownloadSource(
                    category = topographicMapsCategory,
                    label = openHikingLabel,
                    url = "https://www.openhiking.eu/en/downloads/mapsforge-maps",
                ),
                ExternalDownloadSource(
                    category = nonTopographicMapsCategory,
                    label = bbbikeLabel,
                    url = "https://extract.bbbike.org/?format=mapsforge-osm.zip",
                    guidance = bbbikeGuidance,
                ),
                ExternalDownloadSource(
                    category = nonTopographicMapsCategory,
                    label = vectorCityLabel,
                    url = "https://vector.city/",
                ),
                ExternalDownloadSource(
                    category = nonTopographicMapsCategory,
                    label = alternativasLibresLabel,
                    url = "https://alternativaslibres.org/en/downloads-mf.php",
                ),
            )
        }
    val themeLegendSources =
        remember(
            openLegendPdfLabel,
            openThemeWebsiteLabel,
            openDayLegendPdfLabel,
            openNightLegendPdfLabel,
            openThemeDiscussionLabel,
        ) {
            listOf(
                ThemeLegendSource(
                    label = "Elevate",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openLegendPdfLabel,
                                url = "https://www.openandromaps.org/wp-content/users/tobias/Elevate.pdf",
                            ),
                            ThemeLegendLink(
                                label = openThemeWebsiteLabel,
                                url = "https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Elevate Winter",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openLegendPdfLabel,
                                url = "https://www.openandromaps.org/wp-content/users/tobias/Elevate.pdf",
                            ),
                            ThemeLegendLink(
                                label = openThemeWebsiteLabel,
                                url = "https://www.senotto.de/Tipps_Tricks/GPS/OAM_Winter/OAM_Elevate_Winter.htm",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Hike, Ride & Sight",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openLegendPdfLabel,
                                url = "http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.pdf",
                            ),
                            ThemeLegendLink(
                                label = openThemeWebsiteLabel,
                                url = "http://j.seydoux.free.fr/locus/hrs.html",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Voluntary",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openLegendPdfLabel,
                                url =
                                    "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/" +
                                        "themes/voluntary/downloads/Voluntary%20Key.pdf",
                            ),
                            ThemeLegendLink(
                                label = openThemeWebsiteLabel,
                                url = "https://voluntary.nichesite.org/",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "OS Map",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openDayLegendPdfLabel,
                                url =
                                    "https://drive.google.com/uc?export=download&" +
                                        "id=1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs",
                            ),
                            ThemeLegendLink(
                                label = openNightLegendPdfLabel,
                                url =
                                    "https://drive.google.com/uc?export=download&" +
                                        "id=1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets",
                            ),
                            ThemeLegendLink(
                                label = openThemeDiscussionLabel,
                                url = "https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "OpenHiking",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openThemeWebsiteLabel,
                                url = "https://www.openhiking.eu/en/downloads/mapsforge-maps",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "French Kiss",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openThemeWebsiteLabel,
                                url = "https://xctrack.org/AboutMaps.html",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Tiramisu",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openLegendPdfLabel,
                                url =
                                    "https://raw.githubusercontent.com/IgorMagellan/Tiramisu/main/" +
                                        "Tiramisu_3_Legend.pdf",
                            ),
                            ThemeLegendLink(
                                label = openThemeWebsiteLabel,
                                url = "https://github.com/IgorMagellan/Tiramisu",
                            ),
                        ),
                ),
                ThemeLegendSource(
                    label = "Mapsforge",
                    links =
                        listOf(
                            ThemeLegendLink(
                                label = openThemeWebsiteLabel,
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
            CompanionHomeArea.MAP_LEGEND,
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
                        debugCaptureActive = debugCaptureState.active,
                        onOpenDebugCapture = { showDebugDialog = true },
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
                                contentDescription =
                                    stringResource(R.string.common_back_to_home_content_description),
                                modifier = Modifier.size(adaptive.helpIconSize),
                            )
                        }
                        Text(
                            text = stringResource(R.string.transfer_title_send_to_watch),
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
                                contentDescription =
                                    stringResource(R.string.common_quick_guide_content_description),
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
                                title = stringResource(R.string.transfer_section_select_files_title),
                                headerAction = {
                                    TextButton(
                                        onClick = { viewModel.clearSelectedFiles() },
                                        enabled = uiState.selectedFileUris.isNotEmpty() && !uiLocked,
                                    ) {
                                        Text(stringResource(R.string.common_action_clear))
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
                                        Text(stringResource(R.string.transfer_action_select_files))
                                    }
                                    SelectedFilesCompactSummary(
                                        fileNames = uiState.selectedFileDisplayNames,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(adaptive.sectionGap))

                            SectionCard(
                                title = stringResource(R.string.transfer_section_select_watch_title),
                                headerAction = {
                                    IconButton(
                                        onClick = { viewModel.findWatchNodes() },
                                        enabled = !uiLocked,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription =
                                                stringResource(
                                                    R.string.common_refresh_watch_list_content_description,
                                                ),
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
                                        Text(
                                            stringResource(R.string.transfer_no_watches_found),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
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
            text = stringResource(R.string.transfer_no_file_selected),
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
                Text(
                    pluralStringResource(
                        R.plurals.transfer_additional_files,
                        fileNames.size - 1,
                        fileNames.size - 1,
                    ),
                )
            }
        }
    }

    if (showAllFiles) {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showAllFiles = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.transfer_selected_files_title,
                        fileNames.size,
                        fileNames.size,
                    ),
                )
            },
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
                                text =
                                    stringResource(
                                        R.string.transfer_selected_file_row,
                                        index + 1,
                                        fileName,
                                    ),
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
                    Text(stringResource(R.string.common_action_close))
                }
            },
        )
    }
}

@Composable
private fun CompanionHomeScreen(
    adaptive: CompanionAdaptiveSpec,
    debugCaptureActive: Boolean,
    onOpenDebugCapture: () -> Unit,
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
                        stringResource(
                            if (debugCaptureActive) {
                                R.string.home_debug_capture_stop_content_description
                            } else {
                                R.string.home_debug_capture_start_content_description
                            },
                        ),
                    modifier = Modifier.size(adaptive.helpIconSize),
                )
            }
            Text(
                text = stringResource(R.string.home_title),
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
                    contentDescription =
                        stringResource(R.string.common_quick_guide_content_description),
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
                            text = stringResource(R.string.transfer_title_send_to_watch),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.transfer_send_to_watch_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HomeActionButton(
                    icon = Icons.Filled.SpatialTracking,
                    title = stringResource(R.string.live_tracking_title),
                    description = stringResource(R.string.live_tracking_home_description),
                    onClick = onOpenLiveTracking,
                )
                HomeActionButton(
                    icon = Icons.Filled.Map,
                    title = stringResource(R.string.map_legend_title),
                    description = stringResource(R.string.map_legend_home_description),
                    onClick = onOpenMapLegend,
                )
                HomeActionButton(
                    icon = Icons.Filled.Gavel,
                    title = stringResource(R.string.settings_credits_legal_title),
                    description = stringResource(R.string.settings_credits_legal_home_description),
                    onClick = onOpenCreditsLegal,
                )
            }
        }
    }
}

@Composable
private fun HomeActionButton(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
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
                    contentDescription = stringResource(R.string.common_back_to_home_content_description),
                    modifier = Modifier.size(adaptive.helpIconSize),
                )
            }
            Text(
                text = stringResource(R.string.map_legend_title),
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
                    contentDescription = stringResource(R.string.common_quick_guide_content_description),
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
                title = stringResource(R.string.map_legend_theme_label),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.map_legend_intro),
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
                                contentDescription =
                                    stringResource(
                                        R.string.map_legend_select_theme_content_description,
                                    ),
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
                            text = stringResource(R.string.map_legend_no_public_link),
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
