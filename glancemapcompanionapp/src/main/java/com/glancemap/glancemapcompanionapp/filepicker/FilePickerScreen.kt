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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
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
import com.glancemap.glancemapcompanionapp.activehike.LiveHikeDashboardScreen
import com.glancemap.glancemapcompanionapp.activehike.LiveHikeDashboardWaitingScreen
import com.glancemap.glancemapcompanionapp.activehike.PhoneActiveHikeSnapshot
import com.glancemap.glancemapcompanionapp.companionAdaptiveSpec
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionJourneyDiagnostics
import com.glancemap.glancemapcompanionapp.hike.PhoneHikeCheckpointLibrary
import com.glancemap.glancemapcompanionapp.hike.PhoneHikeViewModel
import com.glancemap.glancemapcompanionapp.hike.PhoneHikeWorkspace
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryActions
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryFilter
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryItem
import com.glancemap.glancemapcompanionapp.library.phoneLibraryGpxPicker
import com.glancemap.glancemapcompanionapp.library.phoneLibraryScreen
import com.glancemap.glancemapcompanionapp.library.todayHikeGpxActions
import com.glancemap.glancemapcompanionapp.livetracking.LiveTrackingScreen
import com.glancemap.glancemapcompanionapp.map.CompanionMapScreen
import com.glancemap.glancemapcompanionapp.map.MapLibraryAction
import com.glancemap.glancemapcompanionapp.map.MapNavigationActions
import com.glancemap.glancemapcompanionapp.map.MapTool
import com.glancemap.glancemapcompanionapp.map.PhoneGeneralSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceDraftStore
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceMessage
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceRouteDraft
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceSessionStore
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceStartGate
import com.glancemap.glancemapcompanionapp.map.PhoneGuidanceUiState
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxSettingsPreferences
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxSource
import com.glancemap.glancemapcompanionapp.map.PhoneMapLegendView
import com.glancemap.glancemapcompanionapp.map.PhoneMapLocationContext
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiViewModel
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineThemeCatalog
import com.glancemap.glancemapcompanionapp.map.PhoneRecordingService
import com.glancemap.glancemapcompanionapp.map.phoneGuidanceEtaConfig
import com.glancemap.glancemapcompanionapp.map.phoneMapLegendViewsSaver
import com.glancemap.glancemapcompanionapp.routes.CompanionGpxRouteParser
import com.glancemap.glancemapcompanionapp.routes.MissionPlanDayUi
import com.glancemap.glancemapcompanionapp.routes.MissionPlanScreen
import com.glancemap.glancemapcompanionapp.routes.MissionPlanUiState
import com.glancemap.glancemapcompanionapp.routes.MissionPlanViewModel
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRouteDetails
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryViewModel
import com.glancemap.glancemapcompanionapp.routes.RouteWeatherUiState
import com.glancemap.glancemapcompanionapp.routes.TrailIntelligence
import com.glancemap.glancemapcompanionapp.routes.TrailIntelligenceContext
import com.glancemap.glancemapcompanionapp.routes.liveHikeWeatherContext
import com.glancemap.glancemapcompanionapp.routes.matchesActiveHike
import com.glancemap.glancemapcompanionapp.routes.missionPlanTodaySummary
import com.glancemap.glancemapcompanionapp.routes.reversedForGuidance
import com.glancemap.glancemapcompanionapp.routes.trailIntelligenceFor
import com.glancemap.glancemapcompanionapp.routes.trailIntelligenceForProgress
import com.glancemap.glancemapcompanionapp.weather.weatherConditionText
import com.glancemap.shared.transfer.ActiveHikePhase
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel

@Composable
internal fun FilePickerScreen(
    viewModel: FileTransferViewModel,
    routeLibraryViewModel: RouteLibraryViewModel,
    missionPlanViewModel: MissionPlanViewModel,
    phoneMapPoiViewModel: PhoneMapPoiViewModel,
    openSendToWatchToken: Long = 0L,
    openLiveTrackingToken: Long = 0L,
    watchGpxSaveToken: Long = 0L,
    watchGpxSaveFiles: List<GeneratedPhoneFile> = emptyList(),
) {
    val context = LocalContext.current
    val fileSaveTextResources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hikeController: PhoneHikeViewModel = composeViewModel()
    val hikeSeed by remember(hikeController) {
        hikeController.state.map { it.ready to it.data.todayId }.distinctUntilChanged()
    }.collectAsState(initial = false to null)

    val uiState by viewModel.uiState.collectAsState()
    val activeHikeSnapshot by viewModel.activeHikeSnapshot.collectAsState()
    val liveHikeSyncEnabled by viewModel.liveHikeSyncEnabled.collectAsState()
    val routeLibraryUiState by routeLibraryViewModel.uiState.collectAsState()
    val selectedRouteDetails by routeLibraryViewModel.selectedRouteDetails.collectAsState()
    val phoneMapPoiUiState by phoneMapPoiViewModel.uiState.collectAsState()
    val routeWeatherUiState by routeLibraryViewModel.routeWeatherUiState.collectAsState()
    val missionPlanUiState by missionPlanViewModel.uiState.collectAsState()
    val phoneGuidanceState by PhoneGuidanceSessionStore.state.collectAsState()
    LaunchedEffect(hikeSeed, routeLibraryUiState.selectedRouteId, missionPlanUiState.selectedDay?.day?.id) {
        routeLibraryUiState.selectedRouteId?.takeIf { hikeSeed.first && hikeSeed.second == null }?.let { id ->
            hikeController.seedToday(id, missionPlanUiState.selectedDay?.takeIf { it.route.id == id })
        }
    }
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

    LaunchedEffect(lastImportedPoiFile?.uri) {
        if (lastImportedPoiFile != null) phoneMapPoiViewModel.refresh()
    }

    val autoOpenHelpOnFirstLaunch =
        remember(context) {
            shouldAutoOpenHelpOnFirstLaunch(context)
        }
    var showCancelDialog by remember { mutableStateOf(false) }
    var quickGuideMode by remember { mutableStateOf(QuickGuideMode.GENERAL) }
    var showHowToDialog by remember(autoOpenHelpOnFirstLaunch) { mutableStateOf(autoOpenHelpOnFirstLaunch) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var activeHomeArea by rememberSaveable {
        mutableStateOf(
            initialCompanionHomeArea(openLiveTrackingToken, openSendToWatchToken),
        )
    }
    var discoveryMenuRequest by rememberSaveable { mutableStateOf(0L) }
    var weatherRouteId by remember { mutableStateOf<String?>(null) }
    var pendingMissionRouteDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var stagePlannerRequested by rememberSaveable { mutableStateOf(false) }
    var stagePlannerRouteId by rememberSaveable { mutableStateOf<String?>(null) }
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
    var guidanceStartingKey by remember { mutableStateOf<String?>(null) }
    val guidanceStartGate = remember { PhoneGuidanceStartGate() }
    DisposableEffect(guidanceStartGate) {
        onDispose { guidanceStartGate.invalidate() }
    }
    LaunchedEffect(phoneGuidanceState.session, phoneGuidanceState.message) {
        if (phoneGuidanceState.session != null || phoneGuidanceState.message == PhoneGuidanceMessage.FAILED) {
            guidanceStartGate.completeCurrent()
            guidanceStartingKey = null
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var libraryMapTool by rememberSaveable { mutableStateOf<MapTool?>(null) }
    var libraryFilter by rememberSaveable { mutableStateOf(PhoneLibraryFilter.GPX) }
    val librarySaveableState = rememberSaveableStateHolder()
    val mapSaveableState = rememberSaveableStateHolder()
    var mapLegendViews by rememberSaveable(stateSaver = phoneMapLegendViewsSaver) {
        mutableStateOf(emptyList<PhoneMapLegendView>())
    }
    var contentSettingsTool by rememberSaveable { mutableStateOf(MapTool.SETTINGS) }
    var settingsReturnArea by rememberSaveable { mutableStateOf(CompanionHomeArea.MAP) }
    var settingsReturnToGeneral by rememberSaveable { mutableStateOf(false) }
    var showGpxPicker by rememberSaveable { mutableStateOf(false) }
    var pickGpxForWeather by rememberSaveable { mutableStateOf(false) }
    val returnToMapMenu: () -> Unit = {
        libraryMapTool = null
        activeHomeArea = CompanionHomeArea.MAP
        coroutineScope.launch { drawerState.open() }
    }
    var phoneStoredFilesSummary by remember { mutableStateOf(emptyPhoneStoredFilesSummary()) }
    var isLoadingPhoneStoredFiles by remember { mutableStateOf(false) }
    var isClearingPhoneStoredFiles by remember { mutableStateOf(false) }
    var phoneStoredFilesRefreshToken by remember { mutableIntStateOf(0) }

    val returnFromSettings: () -> Unit = {
        if (settingsReturnToGeneral) {
            contentSettingsTool = MapTool.SETTINGS
            settingsReturnToGeneral = false
        } else if (settingsReturnArea == CompanionHomeArea.LIBRARY) {
            libraryMapTool = MapTool.LIBRARY
            activeHomeArea = CompanionHomeArea.MAP
        } else {
            returnToMapMenu()
        }
    }
    BackHandler(enabled = activeHomeArea != CompanionHomeArea.MAP) {
        when (activeHomeArea) {
            CompanionHomeArea.SETTINGS -> returnFromSettings()
            CompanionHomeArea.MAP_LEGEND -> activeHomeArea = CompanionHomeArea.MAP
            else -> returnToMapMenu()
        }
    }
    if (showGpxPicker) {
        phoneLibraryGpxPicker(
            state = routeLibraryUiState,
            onPick = { id ->
                routeLibraryViewModel.selectRoute(id)
                if (pickGpxForWeather) weatherRouteId = id else hikeController.useLibraryRoute(id)
                showGpxPicker = false
                pickGpxForWeather = false
            },
            onDismiss = { showGpxPicker = false },
        )
    }

    // The initial destination is already applied above; recreation must not replay launch intents.
    var handledSendToWatchToken by remember { mutableStateOf(openSendToWatchToken) }
    var handledLiveTrackingToken by remember { mutableStateOf(openLiveTrackingToken) }
    LaunchedEffect(openSendToWatchToken) {
        if (openSendToWatchToken != handledSendToWatchToken && openSendToWatchToken != 0L) {
            activeHomeArea = CompanionHomeArea.SEND_TO_WATCH
        }
        handledSendToWatchToken = openSendToWatchToken
    }

    LaunchedEffect(openLiveTrackingToken) {
        if (openLiveTrackingToken != handledLiveTrackingToken && openLiveTrackingToken != 0L) {
            activeHomeArea = CompanionHomeArea.LIVE_TRACKING
        }
        handledLiveTrackingToken = openLiveTrackingToken
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
                        Toast
                            .makeText(
                                context,
                                fileSaveTextResources.getString(R.string.phone_files_no_file_available_to_save),
                                Toast.LENGTH_SHORT,
                            ).show()
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

            CompanionHomeArea.LIBRARY,
            CompanionHomeArea.DISCOVER_ROUTES,
            CompanionHomeArea.HOME,
            CompanionHomeArea.LIVE_HIKE,
            CompanionHomeArea.MAP,
            CompanionHomeArea.MAP_LEGEND,
            CompanionHomeArea.MISSION_PLAN,
            CompanionHomeArea.ROUTES,
            CompanionHomeArea.ROUTE_WEATHER,
            CompanionHomeArea.SETTINGS,
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

        companionNavigationDrawer(
            drawerState = drawerState,
            state =
                CompanionMenuState(
                    activeArea = activeHomeArea,
                    debugCaptureActive = debugCaptureState.active,
                    liveHikeSyncEnabled = liveHikeSyncEnabled,
                ),
            actions =
                CompanionMenuActions(
                    onNavigate = { area ->
                        libraryMapTool = null
                        if (area == CompanionHomeArea.DISCOVER_ROUTES) discoveryMenuRequest += 1L
                        if (area == CompanionHomeArea.SETTINGS) {
                            contentSettingsTool = MapTool.SETTINGS
                            settingsReturnArea = CompanionHomeArea.MAP
                            settingsReturnToGeneral = false
                        }
                        if (area == CompanionHomeArea.LIVE_HIKE) {
                            CompanionJourneyDiagnostics.liveHikeDashboardOpened(activeHikeSnapshot?.snapshot)
                        }
                        if (area == CompanionHomeArea.ROUTE_WEATHER) {
                            weatherRouteId = routeLibraryUiState.selectedRoute?.id
                        }
                        activeHomeArea = area
                        coroutineScope.launch { drawerState.close() }
                    },
                    onDebug = {
                        showDebugDialog = true
                    },
                    onQuickGuide = {
                        quickGuideMode = QuickGuideMode.GENERAL
                        showHowToDialog = true
                    },
                    onCreditsLegal = {
                        context.startActivity(PrivacyPolicyActivity.creditsAndLegalIntent(context))
                    },
                    onLiveHikeSyncChanged = { enabled -> viewModel.setLiveHikeSyncEnabled(context, enabled) },
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (
                                activeHomeArea in
                                setOf(
                                    CompanionHomeArea.MAP,
                                    CompanionHomeArea.LIBRARY,
                                    CompanionHomeArea.SETTINGS,
                                    CompanionHomeArea.HOME,
                                    CompanionHomeArea.ROUTES,
                                    CompanionHomeArea.DISCOVER_ROUTES,
                                )
                            ) {
                                Modifier
                            } else {
                                Modifier.padding(adaptive.pagePadding)
                            },
                        ),
            ) {
                when (activeHomeArea) {
                    CompanionHomeArea.ROUTE_WEATHER -> {
                        val weatherRoute =
                            routeLibraryUiState.routes.firstOrNull { route -> route.id == weatherRouteId }
                        val isPreparingWeatherRoute =
                            weatherRoute != null && selectedRouteDetails?.route?.id != weatherRoute.id
                        RouteWeatherScreen(
                            route = weatherRoute,
                            isPreparingRoute = isPreparingWeatherRoute,
                            uiState =
                                routeWeatherUiState.takeIf { weatherRoute?.id == routeLibraryUiState.selectedRoute?.id }
                                    ?: RouteWeatherUiState(),
                            onBack = returnToMapMenu,
                            onChooseRoute = {
                                pickGpxForWeather = true
                                showGpxPicker = true
                            },
                            onLoadWeather = { forceRefresh ->
                                routeLibraryViewModel.loadRouteWeather(
                                    activeHikeSnapshot = null,
                                    forceRefresh = forceRefresh,
                                )
                            },
                        )
                    }

                    CompanionHomeArea.MISSION_PLAN -> {
                        MissionPlanScreen(
                            uiState = missionPlanUiState,
                            routes = routeLibraryUiState.routes,
                            onBack = returnToMapMenu,
                            onSaveAdventure = missionPlanViewModel::saveAdventure,
                            onSelectAdventure = missionPlanViewModel::selectAdventure,
                            onAddBlankDay = missionPlanViewModel::addBlankDay,
                            onAssignRoute = missionPlanViewModel::assignRoute,
                            onImportRoute = { dayId, uri ->
                                missionPlanViewModel.importDayRoute(dayId, uri, routeLibraryViewModel::refresh)
                            },
                            onCreateRoute = { dayId ->
                                pendingMissionRouteDayId = dayId
                                libraryMapTool = MapTool.GPX_TOOLS
                                activeHomeArea = CompanionHomeArea.MAP
                            },
                            onSetToday = { dayUi ->
                                hikeController.setMissionDay(dayUi)
                                libraryMapTool = MapTool.HIKE
                                missionPlanViewModel.selectDay(dayUi.day.id)
                                routeLibraryViewModel.selectRoute(dayUi.route.id)
                                activeHomeArea = CompanionHomeArea.MAP
                            },
                            onUpdateDay = missionPlanViewModel::updateDay,
                            onChangeDay = missionPlanViewModel::changeDay,
                            onMoveDay = missionPlanViewModel::moveDay,
                            onRemoveDay = missionPlanViewModel::removeDay,
                            onLoadDayWeather = missionPlanViewModel::loadDayWeather,
                            onRetry = missionPlanViewModel::refresh,
                            onLoadStageSource = missionPlanViewModel::loadStageSource,
                            onImportStageRoute = { uri ->
                                missionPlanViewModel.importStageRoute(uri).also { routeLibraryViewModel.refresh() }
                            },
                            onApplyStages = missionPlanViewModel::saveStages,
                            stagePlannerRequested = stagePlannerRequested,
                            stagePlannerRouteId = stagePlannerRouteId,
                            onStagePlannerRequestConsumed = { stagePlannerRequested = false },
                        )
                    }

                    CompanionHomeArea.LIVE_HIKE -> {
                        activeHikeSnapshot?.let { update ->
                            val activeMissionDay =
                                missionPlanUiState.selectedDay?.takeIf { dayUi ->
                                    dayUi.matchesActiveHike(update.snapshot)
                                }
                            val weather =
                                activeMissionDay?.let { dayUi ->
                                    missionPlanUiState.weatherByDayId[dayUi.day.id]
                                        ?.takeIf { state ->
                                            state.plannedDate == dayUi.day.plannedDate &&
                                                state.plannedStartTime == dayUi.day.plannedStartTime
                                        }
                                }
                            LiveHikeDashboardScreen(
                                update = update,
                                weather = activeMissionDay?.liveHikeWeatherContext(weather),
                                onLoadWeather =
                                    activeMissionDay?.let { dayUi ->
                                        { forceRefresh ->
                                            missionPlanViewModel.loadDayWeather(dayUi.day.id, forceRefresh)
                                        }
                                    },
                                onBack = returnToMapMenu,
                            )
                        } ?: LiveHikeDashboardWaitingScreen(
                            syncEnabled = liveHikeSyncEnabled,
                            onEnableSync = { viewModel.setLiveHikeSyncEnabled(context, true) },
                            onBack = returnToMapMenu,
                        )
                    }

                    CompanionHomeArea.LIVE_TRACKING -> {
                        LiveTrackingScreen(
                            onBack = returnToMapMenu,
                            onOpenQuickGuide = {
                                quickGuideMode = QuickGuideMode.LIVE_TRACKING
                                showHowToDialog = true
                            },
                            lastTransferGpxUri = lastTransferGpx?.first,
                            lastTransferGpxName = lastTransferGpx?.second.orEmpty(),
                        )
                    }

                    CompanionHomeArea.MAP,
                    CompanionHomeArea.LIBRARY,
                    CompanionHomeArea.SETTINGS,
                    CompanionHomeArea.HOME,
                    CompanionHomeArea.ROUTES,
                    CompanionHomeArea.DISCOVER_ROUTES,
                    -> {
                        mapSaveableState.SaveableStateProvider("phone-map") {
                            CompanionMapScreen(
                                gpxSources =
                                    routeLibraryUiState.routes.map { route ->
                                        PhoneMapGpxSource(
                                            id = route.id,
                                            displayName = route.displayName,
                                            isRecording = route.isRecording,
                                            libraryKey =
                                                route.sourceExportId?.let {
                                                    "export/$it"
                                                } ?: "gpx/${route.id}",
                                        )
                                    },
                                initiallyEnabledGpxId = routeLibraryUiState.selectedRouteId,
                                gpxSourcesLoading = routeLibraryUiState.isLoading,
                                pois = phoneMapPoiUiState.pois,
                                poiSources = phoneMapPoiUiState.sources,
                                onPoiViewportChanged = phoneMapPoiViewModel::onViewportChanged,
                                onPoiVisibilityChanged = phoneMapPoiViewModel::setPoiVisible,
                                onPoiSourceVisibilityChanged = phoneMapPoiViewModel::setSourceVisible,
                                onRenamePoiSource = phoneMapPoiViewModel::renameSource,
                                onDeletePoiSource = phoneMapPoiViewModel::deleteSource,
                                onPoiDataChanged = phoneMapPoiViewModel::refresh,
                                onRenameRoute = routeLibraryViewModel::renameRoute,
                                onDeleteRoute = routeLibraryViewModel::deleteRoute,
                                onRouteSaved = routeLibraryViewModel::refresh,
                                onPlanAcrossDays = { routeId ->
                                    pendingMissionRouteDayId = null
                                    stagePlannerRouteId = routeId ?: routeLibraryUiState.selectedRouteId
                                    stagePlannerRequested = true
                                    libraryMapTool = null
                                    activeHomeArea = CompanionHomeArea.MISSION_PLAN
                                },
                                onPlannedRouteFinished =
                                    pendingMissionRouteDayId?.let { dayId ->
                                        { routeId ->
                                            if (routeId != null) missionPlanViewModel.assignRoute(dayId, routeId)
                                            pendingMissionRouteDayId = null
                                            libraryMapTool = null
                                            activeHomeArea = CompanionHomeArea.MISSION_PLAN
                                        }
                                    },
                                onOpenMenu = {
                                    pendingMissionRouteDayId = null
                                    if (activeHomeArea == CompanionHomeArea.SETTINGS) {
                                        returnFromSettings()
                                    } else {
                                        coroutineScope.launch { drawerState.open() }
                                    }
                                },
                                initialTool =
                                    when (activeHomeArea) {
                                        CompanionHomeArea.SETTINGS -> contentSettingsTool
                                        CompanionHomeArea.LIBRARY -> MapTool.LIBRARY
                                        CompanionHomeArea.DISCOVER_ROUTES -> MapTool.GPX
                                        CompanionHomeArea.HOME, CompanionHomeArea.ROUTES -> MapTool.HIKE
                                        else -> libraryMapTool
                                    },
                                hikeController = hikeController,
                                initialDiscoveryRequest =
                                    discoveryMenuRequest.takeIf { activeHomeArea == CompanionHomeArea.DISCOVER_ROUTES },
                                initialToolSettings =
                                    activeHomeArea == CompanionHomeArea.SETTINGS &&
                                        contentSettingsTool != MapTool.SETTINGS,
                                libraryContent = { mapState, mapActions ->
                                    librarySaveableState.SaveableStateProvider("phone-library") {
                                        phoneLibraryScreen(
                                            checkpointsContent = {
                                                PhoneHikeCheckpointLibrary(hikeController) { mapActions.onOpenTool(MapTool.HIKE) }
                                            },
                                            routes = routeLibraryUiState,
                                            mapState = mapState,
                                            mapActions = mapActions,
                                            filter = libraryFilter,
                                            actions =
                                                PhoneLibraryActions(
                                                    onPreviewGpx = { item ->
                                                        hikeController.preview(item)
                                                        mapActions.onOpenTool(MapTool.HIKE)
                                                    },
                                                    onRefresh = {
                                                        routeLibraryViewModel.refresh()
                                                        phoneMapPoiViewModel.refresh()
                                                    },
                                                    onImportGpx = routeLibraryViewModel::importLibraryGpx,
                                                    guidanceStartingKey = guidanceStartingKey,
                                                    guidanceActive =
                                                        phoneGuidanceState.session != null ||
                                                            phoneGuidanceState.message == PhoneGuidanceMessage.LOADING,
                                                    onStartGuidance = startGuidance@{ item ->
                                                        if (guidanceStartingKey != null ||
                                                            phoneGuidanceState.session != null ||
                                                            phoneGuidanceState.message == PhoneGuidanceMessage.LOADING
                                                        ) {
                                                            return@startGuidance
                                                        }
                                                        val generation =
                                                            guidanceStartGate.begin(
                                                                alreadyActive =
                                                                    phoneGuidanceState.session != null ||
                                                                        phoneGuidanceState.message ==
                                                                        PhoneGuidanceMessage.LOADING,
                                                            ) ?: return@startGuidance
                                                        guidanceStartingKey = item.key
                                                        if (item is PhoneLibraryItem.Gpx) {
                                                            routeLibraryViewModel
                                                                .selectRoute(
                                                                    item.route.id,
                                                                )
                                                        }
                                                        coroutineScope.launch {
                                                            var serviceStarted = false
                                                            try {
                                                                check(PhoneMapLocationContext.hasPermission(context)) {
                                                                    context.getString(
                                                                        R.string.phone_guidance_location_permission_required,
                                                                    )
                                                                }
                                                                val routeInputs =
                                                                    withContext(Dispatchers.IO) {
                                                                        when (item) {
                                                                            is PhoneLibraryItem.Gpx -> {
                                                                                val missionDay =
                                                                                    missionPlanUiState.selectedDay
                                                                                        ?.takeIf {
                                                                                            it.route.id ==
                                                                                                item.route.id
                                                                                        }
                                                                                val uri =
                                                                                    missionDay?.let { selected ->
                                                                                        routeLibraryViewModel
                                                                                            .contentUriFor(
                                                                                                selected.day,
                                                                                            )
                                                                                    }
                                                                                        ?: routeLibraryViewModel.contentUriFor(
                                                                                            item.route.id,
                                                                                        )
                                                                                val parsed =
                                                                                    uri?.let { source ->
                                                                                        context.contentResolver
                                                                                            .openInputStream(source)
                                                                                            ?.use(
                                                                                                CompanionGpxRouteParser::parse,
                                                                                            )
                                                                                    }
                                                                                        ?: error(
                                                                                            context.getString(
                                                                                                R.string.library_gpx_unavailable,
                                                                                            ),
                                                                                        )
                                                                                Triple(
                                                                                    item.route.displayName,
                                                                                    parsed.points,
                                                                                    missionDay?.day?.startDistanceMeters
                                                                                        ?: 0.0,
                                                                                )
                                                                            }
                                                                            is PhoneLibraryItem.FolderGpx ->
                                                                                Triple(
                                                                                    item.item.displayName,
                                                                                    item.item.track.points,
                                                                                    0.0,
                                                                                )
                                                                            is PhoneLibraryItem.ReceivedGpx -> {
                                                                                val parsed =
                                                                                    item.file
                                                                                        .inputStream()
                                                                                        .use(
                                                                                            CompanionGpxRouteParser::parse,
                                                                                        )
                                                                                Triple(item.title, parsed.points, 0.0)
                                                                            }
                                                                            else ->
                                                                                error(
                                                                                    context.getString(
                                                                                        R.string.phone_guidance_route_unavailable,
                                                                                    ),
                                                                                )
                                                                        }
                                                                    }
                                                                require(routeInputs.second.size >= 2) {
                                                                    context.getString(
                                                                        R.string.phone_guidance_route_unavailable,
                                                                    )
                                                                }
                                                                val routeDraft =
                                                                    withContext(Dispatchers.IO) {
                                                                        val general =
                                                                            PhoneGeneralSettingsPreferences(
                                                                                context,
                                                                            ).load()
                                                                        val gpx =
                                                                            PhoneMapGpxSettingsPreferences(
                                                                                context,
                                                                            ).load()
                                                                        PhoneGuidanceRouteDraft(
                                                                            trackId = item.key,
                                                                            trackTitle = routeInputs.first,
                                                                            points = routeInputs.second,
                                                                            routeOffsetMeters = routeInputs.third,
                                                                            activityProfile = general.activityProfile.storageValue,
                                                                            etaConfig =
                                                                                phoneGuidanceEtaConfig(
                                                                                    gpx,
                                                                                    general,
                                                                                ),
                                                                        )
                                                                    }
                                                                if (!guidanceStartGate.isCurrent(
                                                                        generation,
                                                                    )
                                                                ) {
                                                                    return@launch
                                                                }
                                                                withContext(Dispatchers.IO) {
                                                                    PhoneGuidanceDraftStore(
                                                                        context,
                                                                    ).saveRoute(routeDraft)
                                                                }
                                                                if (!guidanceStartGate.isCurrent(
                                                                        generation,
                                                                    )
                                                                ) {
                                                                    return@launch
                                                                }
                                                                PhoneGuidanceSessionStore.publish(
                                                                    PhoneGuidanceUiState(
                                                                        message = PhoneGuidanceMessage.LOADING,
                                                                    ),
                                                                )
                                                                PhoneRecordingService.request(
                                                                    context,
                                                                    PhoneRecordingService.ACTION_GUIDANCE_START,
                                                                )
                                                                serviceStarted = true
                                                                hikeController.selectTab(false)
                                                                mapActions.onOpenTool(MapTool.HIKE)
                                                            } catch (error: Exception) {
                                                                if (guidanceStartGate.isCurrent(generation)) {
                                                                    PhoneGuidanceSessionStore.publish(
                                                                        PhoneGuidanceUiState(
                                                                            message = PhoneGuidanceMessage.FAILED,
                                                                        ),
                                                                    )
                                                                    Toast
                                                                        .makeText(
                                                                            context,
                                                                            error.message
                                                                                ?: context.getString(
                                                                                    R.string.phone_guidance_start_failed,
                                                                                ),
                                                                            Toast.LENGTH_LONG,
                                                                        ).show()
                                                                }
                                                            } finally {
                                                                if (!serviceStarted &&
                                                                    guidanceStartGate.complete(generation)
                                                                ) {
                                                                    guidanceStartingKey =
                                                                        null
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onUseRoute = { id ->
                                                        hikeController.useLibraryRoute(id)
                                                        routeLibraryViewModel.selectRoute(id)
                                                        mapActions.onOpenTool(MapTool.HIKE)
                                                    },
                                                    onOpenMapTools = mapActions.onOpenTool,
                                                    onOpenSettings = { tool ->
                                                        contentSettingsTool = tool
                                                        settingsReturnArea = CompanionHomeArea.LIBRARY
                                                        settingsReturnToGeneral = false
                                                        activeHomeArea = CompanionHomeArea.SETTINGS
                                                    },
                                                    onMapAction = { action ->
                                                        when (action) {
                                                            MapLibraryAction.ADD_POI -> mapActions.onAddPoi()
                                                            MapLibraryAction.IMPORT_MAP -> mapActions.maps.onImportMap()
                                                            MapLibraryAction.DOWNLOAD_BUNDLE ->
                                                                mapActions
                                                                    .onOpenBundleDownload()
                                                        }
                                                    },
                                                    onSendGpx = { id ->
                                                        val uri = routeLibraryViewModel.contentUriFor(id)
                                                        if (uri == null) {
                                                            Toast
                                                                .makeText(
                                                                    context,
                                                                    R.string.library_gpx_unavailable,
                                                                    Toast.LENGTH_SHORT,
                                                                ).show()
                                                        } else {
                                                            viewModel.selectFilesForTransfer(context, listOf(uri))
                                                            activeHomeArea = CompanionHomeArea.SEND_TO_WATCH
                                                        }
                                                    },
                                                    onFilterChanged = { libraryFilter = it },
                                                ),
                                        )
                                    }
                                },
                                hikeHeaderAction = {
                                    todayHikeGpxActions(
                                        state = routeLibraryUiState,
                                        onImport = routeLibraryViewModel::importRoute,
                                        onChoose = {
                                            pickGpxForWeather = false
                                            showGpxPicker = true
                                        },
                                    )
                                },
                                hikeContent = {
                                    CompanionTodayHikeContent(
                                        hikeController = hikeController,
                                        libraryState = routeLibraryUiState,
                                        selectedRouteDetails = selectedRouteDetails,
                                        activeHikeSnapshot = activeHikeSnapshot,
                                        routeWeatherUiState = routeWeatherUiState,
                                        missionPlanUiState = missionPlanUiState,
                                        onLoadRouteWeather = {
                                            snapshot,
                                            phoneGuidanceDistanceMeters,
                                            forceRefresh,
                                            plannedStartDistanceMeters,
                                            ->
                                            routeLibraryViewModel.loadRouteWeather(
                                                activeHikeSnapshot = snapshot,
                                                forceRefresh = forceRefresh,
                                                plannedStartDistanceMeters = plannedStartDistanceMeters,
                                                phoneGuidanceDistanceMeters = phoneGuidanceDistanceMeters,
                                                phoneGuidancePositionLabel =
                                                    context.getString(
                                                        R.string.phone_guidance_weather_position,
                                                    ),
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
                                                        viewModel.selectFilesForTransfer(context, listOf(routeUri))
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
                                                    viewModel.selectFilesForTransfer(context, listOf(routeUri))
                                                    activeHomeArea = CompanionHomeArea.SEND_TO_WATCH
                                                }
                                            }
                                        },
                                    )
                                },
                                navigation =
                                    MapNavigationActions(
                                        onOpenSettings = { tool ->
                                            contentSettingsTool = tool
                                            settingsReturnToGeneral = true
                                        },
                                        onMapLegend = { views ->
                                            mapLegendViews = views
                                            activeHomeArea = CompanionHomeArea.MAP_LEGEND
                                        },
                                    ),
                            )
                        }
                    }

                    CompanionHomeArea.MAP_LEGEND -> {
                        CompanionMapLegendScreen(
                            adaptive = adaptive,
                            selectedThemeLegend = selectedThemeLegend,
                            mapViews =
                                mapLegendViews.map { view ->
                                    val themeLabel = view.themeId?.let { PhoneOfflineThemeCatalog.themeFor(it).label }
                                    view.label to
                                        ThemeLegendSource(
                                            label = view.sourceLabel,
                                            links =
                                                themeLegendSources
                                                    .firstOrNull { it.label == themeLabel }
                                                    ?.links
                                                    .orEmpty(),
                                        )
                                },
                            themeLegendSources = themeLegendSources,
                            showThemeLegendMenu = showThemeLegendMenu,
                            onShowThemeLegendMenuChange = { showThemeLegendMenu = it },
                            onThemeLegendSelected = { selectedThemeLegend = it },
                            onOpenLink = { openCompanionUrl(context, it) },
                            onOpenHelp = {
                                quickGuideMode = QuickGuideMode.MAP_LEGEND
                                showHowToDialog = true
                            },
                            onBack = { activeHomeArea = CompanionHomeArea.MAP },
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
                                onClick = returnToMapMenu,
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
private fun CompanionTodayHikeContent(
    hikeController: PhoneHikeViewModel,
    libraryState: RouteLibraryUiState,
    selectedRouteDetails: RouteLibraryRouteDetails?,
    activeHikeSnapshot: PhoneActiveHikeSnapshot?,
    routeWeatherUiState: RouteWeatherUiState,
    missionPlanUiState: MissionPlanUiState,
    onLoadRouteWeather: (ActiveHikeSnapshot?, Double?, Boolean, Double) -> Unit,
    onSendSelectedRouteToWatch: () -> Unit,
) {
    val phoneGuidance by PhoneGuidanceSessionStore.state.collectAsState()
    val hike by hikeController.state.collectAsState()
    PhoneHikeWorkspace(hikeController) {
        if (libraryState.isImporting) LinearProgressIndicator(Modifier.fillMaxWidth())
        libraryState.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (hike.model?.route?.trackId == libraryState.selectedRoute?.let { "gpx/${it.id}" } &&
            kotlin.math.abs(
                (hike.model?.route?.routeOffsetMeters ?: 0.0) -
                    (
                        missionPlanUiState.selectedDay
                            ?.takeIf { it.route.id == libraryState.selectedRoute?.id }
                            ?.day
                            ?.startDistanceMeters ?: 0.0
                    ),
            ) < 1.0
        ) {
            TodayHikeCard(
                selectedRoute = libraryState.selectedRoute,
                selectedRouteDetails = selectedRouteDetails,
                activeHikeSnapshot = activeHikeSnapshot,
                phoneGuidance = phoneGuidance,
                routeWeatherUiState = routeWeatherUiState,
                missionDay = missionPlanUiState.selectedDay?.takeIf { it.route.id == libraryState.selectedRoute?.id },
                isPreparingMissionTransfer = missionPlanUiState.isPreparingTransfer,
                onLoadRouteWeather = onLoadRouteWeather,
                onSendSelectedRouteToWatch = onSendSelectedRouteToWatch,
            )
        } else {
            activeHikeSnapshot?.let { ActiveHikeBriefing(it) }
        }
    }
}

@Composable
private fun TodayHikeCard(
    selectedRoute: RouteLibraryRoute?,
    selectedRouteDetails: RouteLibraryRouteDetails?,
    activeHikeSnapshot: PhoneActiveHikeSnapshot?,
    phoneGuidance: PhoneGuidanceUiState,
    routeWeatherUiState: RouteWeatherUiState,
    missionDay: MissionPlanDayUi?,
    isPreparingMissionTransfer: Boolean,
    onLoadRouteWeather: (ActiveHikeSnapshot?, Double?, Boolean, Double) -> Unit,
    onSendSelectedRouteToWatch: () -> Unit,
) {
    val textResources = LocalResources.current
    val liveHikeSnapshot =
        activeHikeSnapshot?.takeIf { update -> update.snapshot.phase != ActiveHikePhase.IDLE }
    val matchingPhoneSession =
        phoneGuidance.session?.takeIf { session ->
            session.trackId == selectedRoute?.let { "gpx/${it.id}" } &&
                phoneGuidance.message != PhoneGuidanceMessage.FAILED &&
                (missionDay == null || abs(session.routeOffsetMeters - missionDay.day.startDistanceMeters) <= 1.0)
        }
    val phoneDistanceMeters =
        matchingPhoneSession?.let { session ->
            phoneGuidance.guidance?.originalRouteDistanceFromStartMeters
                ?: session.routeOffsetMeters + if (session.reversed) session.totalDistanceMeters else 0.0
        }
    val reversedRouteDetails =
        remember(selectedRouteDetails, matchingPhoneSession?.reversed) {
            selectedRouteDetails?.takeIf { matchingPhoneSession?.reversed == true }?.reversedForGuidance()
        }
    val phoneTrailIntelligence =
        phoneDistanceMeters?.let { distance ->
            selectedRouteDetails?.trailIntelligenceForProgress(
                originalDistanceFromStartMeters = distance,
                reversed = matchingPhoneSession.reversed,
                missionDay = missionDay?.day,
                reversedDetails = reversedRouteDetails,
            )
        }
    val trailIntelligence =
        phoneTrailIntelligence
            ?: liveHikeSnapshot?.snapshot?.let { snapshot -> selectedRouteDetails?.trailIntelligenceFor(snapshot) }
            ?: missionDay
                ?.takeIf { dayUi -> dayUi.route.id == selectedRoute?.id }
                ?.let { dayUi -> selectedRouteDetails?.trailIntelligenceFor(dayUi.day) }
    SectionCard(
        title = textResources.getString(R.string.briefing_today_s_hike),
    ) {
        liveHikeSnapshot?.let { update -> ActiveHikeBriefing(update) }
        if (phoneTrailIntelligence != null) {
            Text(
                text = textResources.getString(R.string.phone_guidance_live_progress),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
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
                text = textResources.getString(R.string.briefing_choose_a_gpx_route_to_prepare_a),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            missionDay?.let { dayUi ->
                Text(
                    text = textResources.getString(R.string.briefing_day_value_mission_plan, dayUi.day.dayNumber),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = selectedRoute.displayName,
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
                            phoneDistanceMeters,
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
                        textResources.getString(R.string.briefing_preparing_day_gpx)
                    } else if (missionDay != null) {
                        textResources.getString(R.string.briefing_send_planned_day_to_watch)
                    } else {
                        textResources.getString(R.string.briefing_send_selected_route_to_watch)
                    },
                )
            }
        }
    }
}

@Composable
private fun RouteWeatherScreen(
    route: RouteLibraryRoute?,
    isPreparingRoute: Boolean,
    uiState: RouteWeatherUiState,
    onBack: () -> Unit,
    onChooseRoute: () -> Unit,
    onLoadWeather: (Boolean) -> Unit,
) {
    val textResources = LocalResources.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = textResources.getString(R.string.common_back_to_home_content_description),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = textResources.getString(R.string.menu_route_weather),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = textResources.getString(R.string.briefing_forecasts_are_available_without_a_mission_plan),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (route == null) {
            Text(
                text = textResources.getString(R.string.briefing_choose_an_imported_gpx_route_to_load),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = route.displayName,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = todayRouteSummary(route),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isPreparingRoute) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = textResources.getString(R.string.briefing_preparing_gpx_forecast_location),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                RouteWeatherBriefing(
                    uiState = uiState,
                    onLoad = onLoadWeather,
                )
            }
        }

        OutlinedButton(
            onClick = onChooseRoute,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (route ==
                    null
                ) {
                    textResources.getString(R.string.briefing_choose_a_route)
                } else {
                    textResources.getString(R.string.briefing_choose_another_route)
                },
            )
        }
    }
}

@Composable
private fun ActiveHikeBriefing(update: PhoneActiveHikeSnapshot) {
    val textResources = LocalResources.current
    val snapshot = update.snapshot
    Text(
        text = textResources.getString(R.string.briefing_live_from_watch),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = snapshot.routeTitle ?: textResources.getString(R.string.live_hike_active_hike),
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
            snapshot.distanceRemainingMeters?.let { distance ->
                textResources.getString(R.string.briefing_value_left, distance.toKilometersText())
            },
            snapshot.estimatedRemainingSeconds?.let { duration ->
                textResources.getString(R.string.briefing_value_remaining, duration.toDouble().toDurationText())
            },
            snapshot.remainingAscentMeters?.let { ascent ->
                textResources.getString(R.string.briefing_value_m_to_climb, ascent.toInt())
            },
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
    val textResources = LocalResources.current
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
            window.ascentMeters.takeIf { it > 0.0 }?.let { ascent ->
                textResources.getString(R.string.route_library_value_m_climb, ascent.toInt())
            },
            window.descentMeters.takeIf { it > 0.0 }?.let { descent ->
                textResources.getString(R.string.briefing_value_m_descent, descent.toInt())
            },
        )
    Text(
        text = metrics.joinToString("  •  "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    intelligence.upcomingWaypoints.forEach { waypoint ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text =
                textResources.getString(
                    R.string.briefing_up_next_value_in_value,
                    waypoint.title,
                    waypoint.distanceAheadMeters.toKilometersText(),
                ),
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
    val textResources = LocalResources.current
    Text(
        text = textResources.getString(R.string.briefing_weather),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
    when {
        uiState.isLoading -> {
            Text(
                text = textResources.getString(R.string.briefing_loading_route_forecast),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        uiState.forecast == null -> {
            Text(
                text = textResources.getString(R.string.briefing_weather_is_not_loaded_for_this_route),
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
                        textResources.getString(R.string.mission_plan_rain_value, probability.roundToInt())
                    },
                    nextHour?.windGustKilometersPerHour?.let { gust ->
                        textResources.getString(R.string.mission_plan_gusts_value_km_h, gust.roundToInt())
                    },
                )
            Text(
                text =
                    "${weatherConditionText(textResources, forecast.current.weatherCode)}" +
                        metrics
                            .takeIf { values -> values.isNotEmpty() }
                            ?.let { values -> " • ${values.joinToString("  •  ")}" }
                            .orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    textResources.getString(
                        R.string.mission_plan_updated_value,
                        forecast.fetchedAtEpochMillis.toWeatherUpdatedText(),
                    ) +
                        if (uiState.isStale) {
                            textResources.getString(
                                R.string.mission_plan_cached_unable_to_update,
                            )
                        } else {
                            ""
                        },
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
    val textResources = LocalResources.current
    val context = LocalContext.current
    Text(
        text = textResources.getString(R.string.briefing_route_weather),
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
                    text = textResources.getString(R.string.briefing_loading_route_forecast),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        uiState.forecast == null -> {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = textResources.getString(R.string.briefing_load_a_forecast_for_this_route_weather),
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
                Text(textResources.getString(R.string.briefing_load_route_weather))
            }
        }

        else -> {
            val forecast = checkNotNull(uiState.forecast)
            val outlook = forecast.nextHour
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${localizedWeatherLocationLabel(
                    forecast.location.label,
                )} • ${weatherConditionText(textResources, forecast.current.weatherCode)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    textResources.getString(
                        R.string.mission_plan_updated_value,
                        forecast.fetchedAtEpochMillis.toWeatherUpdatedText(),
                    ) +
                        textResources.getQuantityString(
                            R.plurals.weather_saved_snapshots,
                            uiState.savedSnapshotCount,
                            uiState.savedSnapshotCount,
                        ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            val currentMetrics =
                listOfNotNull(
                    forecast.current.temperatureCelsius?.let { temperature -> temperature.toWeatherTemperatureText() },
                    forecast.current.apparentTemperatureCelsius?.let { apparent ->
                        textResources.getString(R.string.briefing_feels_value, apparent.toWeatherTemperatureText())
                    },
                    forecast.current.windSpeedKilometersPerHour?.let { wind ->
                        textResources.getString(R.string.briefing_wind_value_km_h, wind.roundToInt())
                    },
                    forecast.current.windGustKilometersPerHour?.let { gust ->
                        textResources.getString(R.string.mission_plan_gusts_value_km_h, gust.roundToInt())
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
                        textResources.getString(R.string.mission_plan_rain_value, probability.roundToInt())
                    },
                    outlook?.windGustKilometersPerHour?.let { gust ->
                        textResources.getString(R.string.mission_plan_gusts_value_km_h, gust.roundToInt())
                    },
                    outlook?.visibilityMeters?.let { visibility ->
                        textResources.getString(R.string.mission_plan_visibility_value, visibility.toKilometersText())
                    },
                    outlook?.freezingLevelHeightMeters?.let { height ->
                        textResources.getString(R.string.briefing_freezing_value_m, height.roundToInt())
                    },
                )
            if (nextHourMetrics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        textResources.getString(
                            R.string.briefing_next_hour_value,
                            nextHourMetrics.joinToString("  •  "),
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (forecast.daily.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = textResources.getString(R.string.briefing_outlook),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                forecast.daily.take(3).forEach { day ->
                    val dayMetrics =
                        listOfNotNull(
                            day.minimumTemperatureCelsius?.let { value ->
                                textResources.getString(R.string.briefing_value_low, value.toWeatherTemperatureText())
                            },
                            day.maximumTemperatureCelsius?.let { value ->
                                textResources.getString(R.string.briefing_value_high, value.toWeatherTemperatureText())
                            },
                            day.precipitationProbabilityPercent?.let { value ->
                                textResources.getString(R.string.mission_plan_rain_value, value.roundToInt())
                            },
                            day.windGustKilometersPerHour?.let { value ->
                                textResources.getString(R.string.mission_plan_gusts_value_km_h, value.roundToInt())
                            },
                        )
                    Text(
                        text =
                            "${day.date} • ${weatherConditionText(textResources, day.weatherCode)}" +
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
                    text = textResources.getString(R.string.briefing_showing_a_cached_forecast_unable_to_update),
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
                Text(
                    if (uiState.isCached) {
                        textResources.getString(
                            R.string.briefing_update_weather,
                        )
                    } else {
                        textResources.getString(R.string.briefing_refresh_weather)
                    },
                )
            }
        }
    }
    TextButton(onClick = { openCompanionUrl(context, OPEN_METEO_URL) }) {
        Text(textResources.getString(R.string.briefing_weather_data_by_open_meteo_com))
    }
}

private fun Double.toWeatherTemperatureText(): String = "${roundToInt()}°C"

private fun Long.toWeatherUpdatedText(): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))

@Composable
private fun TrailIntelligence.trailIntelligenceTitle(remainingMinutes: Int): String {
    val textResources = LocalResources.current
    val windowLabel =
        if (remainingMinutes >=
            30
        ) {
            textResources.getString(R.string.briefing_next_30_minutes)
        } else {
            textResources.getString(R.string.briefing_to_finish_next_value_min, remainingMinutes)
        }
    return if (context ==
        TrailIntelligenceContext.PLANNED_DAY
    ) {
        textResources.getString(R.string.briefing_day_start_value, windowLabel)
    } else {
        windowLabel
    }
}

private const val OPEN_METEO_URL = "https://open-meteo.com/"

@Composable
private fun activeHikeStatusText(
    phase: ActiveHikePhase,
    offRoute: Boolean,
): String {
    val textResources = LocalResources.current
    if (offRoute) return textResources.getString(R.string.live_hike_off_route_check_the_watch)
    return when (phase) {
        ActiveHikePhase.WAITING_FOR_LOCATION -> textResources.getString(R.string.briefing_waiting_for_a_gps_position)
        ActiveHikePhase.TO_START -> textResources.getString(R.string.briefing_heading_to_the_route_start)
        ActiveHikePhase.FOLLOWING_ROUTE -> textResources.getString(R.string.briefing_following_the_route)
        ActiveHikePhase.PAUSED -> textResources.getString(R.string.briefing_navigation_paused_on_watch)
        ActiveHikePhase.FINISHED -> textResources.getString(R.string.live_hike_route_complete)
        ActiveHikePhase.RECORDING -> textResources.getString(R.string.briefing_recording_on_watch)
        ActiveHikePhase.RECORDING_PAUSED -> textResources.getString(R.string.briefing_recording_paused_on_watch)
        ActiveHikePhase.IDLE -> textResources.getString(R.string.live_hike_no_active_hike)
    }
}

private fun todayRouteSummary(route: RouteLibraryRoute): String =
    listOf(
        route.summary.distanceMeters.toKilometersText(),
        "+${route.summary.elevationGainMeters.toInt()} m",
        route.summary.estimatedDurationSeconds.toDurationText(),
    ).joinToString("  •  ")

@Composable
private fun firstThirtyMinutesBriefing(route: RouteLibraryRoute): String {
    val textResources = LocalResources.current
    return textResources.getString(
        R.string.route_first_thirty_minutes_summary,
        route.summary.firstThirtyMinutesDistanceMeters.toKilometersText(),
        route.summary.firstThirtyMinutesAscentMeters.toInt(),
    )
}

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
private fun companionTonalIconButtonColors() =
    IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

@Composable
private fun CompanionMapLegendScreen(
    adaptive: CompanionAdaptiveSpec,
    selectedThemeLegend: ThemeLegendSource,
    mapViews: List<Pair<String, ThemeLegendSource>>,
    themeLegendSources: List<ThemeLegendSource>,
    showThemeLegendMenu: Boolean,
    onShowThemeLegendMenuChange: (Boolean) -> Unit,
    onThemeLegendSelected: (ThemeLegendSource) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenHelp: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var selectedViewIndex by rememberSaveable(mapViews) { mutableIntStateOf(0) }
    val effectiveThemeLegend = mapViews.getOrNull(selectedViewIndex)?.second ?: selectedThemeLegend

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
                        text =
                            stringResource(
                                if (mapViews.isEmpty()) R.string.map_legend_intro else R.string.map_legend_selected_map_intro,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (mapViews.isNotEmpty()) {
                        if (mapViews.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                mapViews.forEachIndexed { index, (label, _) ->
                                    FilterChip(
                                        selected = selectedViewIndex == index,
                                        onClick = { selectedViewIndex = index },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                        Text(effectiveThemeLegend.label, style = MaterialTheme.typography.titleSmall)
                    } else {
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
                    }
                    if (effectiveThemeLegend.links.isEmpty()) {
                        Text(
                            text = stringResource(R.string.map_legend_no_public_link),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        effectiveThemeLegend.links.forEach { link ->
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
        Toast.makeText(context, context.getString(R.string.briefing_unable_to_open_link), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun localizedWeatherLocationLabel(label: String): String =
    when (label) {
        "Current route position" -> stringResource(R.string.weather_location_current_route_position)
        "Planned day start" -> stringResource(R.string.weather_location_planned_day_start)
        "Route start" -> stringResource(R.string.weather_location_route_start)
        else -> label
    }
