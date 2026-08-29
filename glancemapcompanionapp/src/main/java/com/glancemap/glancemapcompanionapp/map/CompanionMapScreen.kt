@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.ensureMapLibreConfigured
import com.glancemap.glancemapcompanionapp.map.maplibre.fitGpxTrackBounds
import com.glancemap.glancemapcompanionapp.map.maplibre.mapLibreRasterStyleJson
import com.glancemap.glancemapcompanionapp.map.maplibre.renderGpxTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private val defaultMapCamera = PhoneMapCameraSnapshot(latitude = 20.0, longitude = 0.0, zoom = 2.0)
private const val RECENTER_ZOOM = 14.0
private val locationPermissions =
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

internal data class MapRuntime(
    val generation: PhoneMapLibreGeneration = PhoneMapLibreGeneration(),
    val mapView: MapView? = null,
    val map: MapLibreMap? = null,
) {
    fun beginMapView(createdMapView: MapView): MapRuntime =
        MapRuntime(
            generation = generation.nextRenderer(),
            mapView = createdMapView,
        )

    fun acceptMapReady(
        callbackGeneration: Long,
        callbackMapView: MapView,
        callbackMap: MapLibreMap,
    ): MapRuntime =
        if (generation.accepts(callbackGeneration) && mapView === callbackMapView) {
            copy(map = callbackMap)
        } else {
            this
        }

    fun acceptStyleReady(
        callbackGeneration: Long,
        callbackMapView: MapView,
        callbackMap: MapLibreMap,
    ): MapRuntime =
        if (
            generation.accepts(callbackGeneration) &&
            mapView === callbackMapView &&
            map === callbackMap
        ) {
            copy(generation = generation.onStyleReady(callbackGeneration))
        } else {
            this
        }

    fun invalidate(disposedMapView: MapView? = null): MapRuntime =
        if (disposedMapView == null || mapView === disposedMapView) {
            MapRuntime(generation = generation.nextRenderer())
        } else {
            this
        }

    fun isCurrentIn(latestRuntime: MapRuntime): Boolean =
        generation == latestRuntime.generation &&
            mapView === latestRuntime.mapView &&
            map === latestRuntime.map

    fun withCurrentLoadedStyle(
        latestRuntime: () -> MapRuntime,
        action: (MapLibreMap, MapView, Style) -> Unit,
    ) {
        val activeMap = map ?: return
        val activeMapView = mapView ?: return
        activeMap.getStyle { currentStyle ->
            if (!activeMapView.isDestroyed && isCurrentIn(latestRuntime())) {
                action(activeMap, activeMapView, currentStyle)
            }
        }
    }
}

private data class MapLocationState(
    val hasPermission: Boolean,
    val pendingRecenter: Boolean,
)

private data class GpxOverlayState(
    val overlays: List<PhoneMapGpxOverlay>,
    val segments: List<PhoneMapRouteSegment>,
    val isVisible: Boolean,
)

/** Keeps Compose-owned MapLibre, permission, and GPX overlay sequencing in one visible flow. */
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun CompanionMapScreen(
    gpxSources: List<PhoneMapGpxSource>,
    initiallyEnabledGpxId: String?,
    pois: List<PhoneMapPoi>,
    poiSources: List<PhoneMapPoiSource>,
    onPoiViewportChanged: (PhoneMapViewport) -> Unit,
    onPoiVisibilityChanged: (Boolean) -> Unit,
    onPoiDataChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val offlineMapStore = remember(context) { PhoneOfflineMapStore(context.applicationContext) }
    val mapFolderSource =
        remember(context, offlineMapStore) {
            PhoneOfflineMapFolderSource(context.applicationContext, offlineMapStore)
        }
    val gpxFolderSource = remember(context) { PhoneGpxFolderSource(context.applicationContext) }
    val offlineThemePreferences = remember(context) { PhoneOfflineThemePreferences(context.applicationContext) }
    val bundleViewModel: PhoneOfflineBundleViewModel = viewModel()
    val bundleUiState by bundleViewModel.uiState.collectAsState()
    val gpxViewModel: PhoneMapGpxViewModel = viewModel()
    val gpxUiState by gpxViewModel.uiState.collectAsState()
    var mapRuntime by remember { mutableStateOf(MapRuntime()) }
    var mapUiState by remember { mutableStateOf(PhoneMapUiState()) }
    var offlineMaps by remember { mutableStateOf(emptyList<PhoneOfflineMap>()) }
    var hasSelectedMapFolder by remember(mapFolderSource) {
        mutableStateOf(mapFolderSource.hasSelectedFolder())
    }
    var hasSelectedGpxFolder by remember(gpxFolderSource) {
        mutableStateOf(gpxFolderSource.hasSelectedFolder())
    }
    var gpxFolderScan by remember { mutableStateOf(PhoneGpxFolderScanResult()) }
    var offlineThemeConfig by remember(offlineThemePreferences) {
        mutableStateOf(offlineThemePreferences.load())
    }
    var mapCamera by remember { mutableStateOf(defaultMapCamera) }
    var showOfflineThemeSelector by remember { mutableStateOf(false) }
    var showOfflineBundleDownload by remember { mutableStateOf(false) }
    var offlineMapError by remember { mutableStateOf<PhoneOfflineMapError?>(null) }
    var hasLocationPermission by remember(context) { mutableStateOf(context.hasLocationPermission()) }
    var pendingRecenter by remember { mutableStateOf(false) }
    val completedBundle =
        (bundleUiState.download as? PhoneOfflineBundleDownloadState.Completed)?.bundle
    var hasFittedGpxOverlay by remember { mutableStateOf(false) }
    var selectedPoi by remember { mutableStateOf<PhoneMapPoi?>(null) }
    val gpxOverlays =
        remember(gpxUiState.items, mapUiState.contentVisibility.gpxTracks) {
            gpxUiState.items.enabledOverlays(mapUiState.contentVisibility.gpxTracks)
        }
    val locationState =
        MapLocationState(
            hasPermission = hasLocationPermission,
            pendingRecenter = pendingRecenter,
        )
    val gpxOverlayState =
        GpxOverlayState(
            overlays = gpxOverlays,
            segments = gpxOverlays.flatMap(PhoneMapGpxOverlay::segments),
            isVisible = mapUiState.contentVisibility.gpxTracks,
        )
    val selectLocalMapLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val imported =
                    withContext(Dispatchers.IO) {
                        offlineMapStore.import(context.contentResolver, uri)
                    }
                when (imported) {
                    is PhoneOfflineMapImportResult.Success -> {
                        Log.i(
                            PhoneOfflineMapImportDiagnostics.TAG,
                            PhoneOfflineMapImportDiagnostics.latestAttempt()?.toCaptureLine()
                                ?: "event=offline_map_import outcome=SUCCESS",
                        )
                        val importedMap = imported.map
                        offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                        mapRuntime.map?.cameraSnapshotOrNull()?.let { mapCamera = it }
                        mapRuntime = mapRuntime.invalidate()
                        mapUiState = mapUiState.copy(source = PhoneMapSource.Offline(importedMap))
                        offlineMapError = null
                    }

                    is PhoneOfflineMapImportResult.Failure -> {
                        Log.w(
                            PhoneOfflineMapImportDiagnostics.TAG,
                            PhoneOfflineMapImportDiagnostics.latestAttempt()?.toCaptureLine()
                                ?: "event=offline_map_import outcome=FAILED error=${imported.error}",
                        )
                        offlineMapError = imported.error
                    }
                }
            }
        }
    val selectMapFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            if (treeUri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val selectionError =
                    withContext(Dispatchers.IO) { mapFolderSource.selectFolder(treeUri) }
                if (selectionError != null) {
                    offlineMapError = selectionError
                    return@launch
                }
                val syncResult = withContext(Dispatchers.IO) { mapFolderSource.syncSelectedFolder() }
                offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                hasSelectedMapFolder = mapFolderSource.hasSelectedFolder()
                offlineMapError = syncResult.error
            }
        }
    val selectGpxFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            if (treeUri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val selectionError = withContext(Dispatchers.IO) { gpxFolderSource.selectFolder(treeUri) }
                if (selectionError != null) {
                    gpxFolderScan = PhoneGpxFolderScanResult(error = selectionError)
                    return@launch
                }
                gpxFolderScan = withContext(Dispatchers.IO) { gpxFolderSource.scanSelectedFolder() }
                hasSelectedGpxFolder = gpxFolderSource.hasSelectedFolder()
            }
        }
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            hasLocationPermission = permissions.values.any { granted -> granted }
            pendingRecenter = hasLocationPermission
        }

    LaunchedEffect(offlineMapStore, mapFolderSource) {
        val syncResult = withContext(Dispatchers.IO) { mapFolderSource.syncSelectedFolder() }
        offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
        hasSelectedMapFolder = mapFolderSource.hasSelectedFolder()
        offlineMapError = syncResult.error
        val selectedOfflineMap = (mapUiState.source as? PhoneMapSource.Offline)?.map
        if (selectedOfflineMap != null && selectedOfflineMap !in offlineMaps) {
            mapUiState = mapUiState.copy(source = PhoneMapSource.Online)
            offlineMapError = PhoneOfflineMapError.MISSING
        }
    }
    LaunchedEffect(gpxFolderSource) {
        gpxFolderScan = withContext(Dispatchers.IO) { gpxFolderSource.scanSelectedFolder() }
        hasSelectedGpxFolder = gpxFolderSource.hasSelectedFolder()
    }
    LaunchedEffect(mapUiState.contentVisibility.pois) {
        onPoiVisibilityChanged(mapUiState.contentVisibility.pois)
        if (!mapUiState.contentVisibility.pois) selectedPoi = null
    }
    LaunchedEffect(pois) {
        if (selectedPoi?.id !in pois.map(PhoneMapPoi::id).toSet()) selectedPoi = null
    }
    LaunchedEffect(completedBundle) {
        if (completedBundle != null) {
            offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
            onPoiDataChanged()
        }
    }
    LaunchedEffect(gpxSources, gpxFolderScan.files, initiallyEnabledGpxId) {
        gpxViewModel.synchronize(gpxSources, gpxFolderScan.files, initiallyEnabledGpxId)
    }

    val requestRecenter = {
        if (context.hasLocationPermission()) {
            hasLocationPermission = true
            pendingRecenter = true
        } else {
            locationPermissionLauncher.launch(locationPermissions)
        }
    }
    val cycleMapMode = {
        val updated = mapUiState.cycleMapMode()
        mapUiState = updated
        if (
            updated.mapMode.follow == PhoneMapFollowMode.FOLLOW_LOCATION &&
            updated.source is PhoneMapSource.Online
        ) {
            requestRecenter()
        } else {
            pendingRecenter = false
        }
    }

    val toolsState =
        MapToolsPanelState(
            maps =
                MapToolsMapsState(
                    source = mapUiState.source,
                    offlineMaps = offlineMaps,
                    hasSelectedFolder = hasSelectedMapFolder,
                    themeConfig = offlineThemeConfig,
                ),
            gpx =
                MapToolsGpxState(
                    items = gpxUiState.items,
                    isLoading = gpxUiState.isLoading,
                    globalVisible = mapUiState.contentVisibility.gpxTracks,
                    routeLibrarySourceCount = gpxSources.size,
                    hasSelectedFolder = hasSelectedGpxFolder,
                    selectedFolderName = gpxFolderScan.folderName,
                    folderError = gpxFolderScan.error,
                ),
            poi =
                MapToolsPoiState(
                    sources = poiSources,
                    globalVisible = mapUiState.contentVisibility.pois,
                ),
            general = MapToolsGeneralState(mapMode = mapUiState.mapMode),
        )
    val mapActions =
        MapToolsMapsActions(
            onSelectOnline = {
                mapUiState = mapUiState.copy(source = PhoneMapSource.Online)
                offlineMapError = null
            },
            onSelectOffline = { selectedMap ->
                coroutineScope.launch {
                    val error = withContext(Dispatchers.IO) { offlineMapStore.validate(selectedMap) }
                    if (error == null) {
                        mapRuntime.map?.cameraSnapshotOrNull()?.let { mapCamera = it }
                        mapRuntime = mapRuntime.invalidate()
                        mapUiState = mapUiState.copy(source = PhoneMapSource.Offline(selectedMap))
                        offlineMapError = null
                    } else {
                        offlineMapError = error
                    }
                }
            },
            onImportMap = { selectLocalMapLauncher.launch(arrayOf("application/octet-stream")) },
            onDownloadBundle = { showOfflineBundleDownload = true },
            onSelectFolder = { selectMapFolderLauncher.launch(null) },
            onRescanFolder = {
                coroutineScope.launch {
                    val syncResult = withContext(Dispatchers.IO) { mapFolderSource.syncSelectedFolder() }
                    offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                    hasSelectedMapFolder = mapFolderSource.hasSelectedFolder()
                    offlineMapError = syncResult.error
                }
            },
            onClearFolder = {
                mapFolderSource.clearSelectedFolder()
                hasSelectedMapFolder = false
            },
            onOpenTheme = { showOfflineThemeSelector = true },
        )

    BackHandler(
        enabled =
            mapUiState.toolPanel.mode != MapToolPanelMode.CLOSED ||
                mapUiState.toolLauncherExpanded,
    ) {
        mapUiState = mapUiState.onMapBack()
    }

    MapToolScaffold(
        state = mapUiState.toolPanel,
        launcherExpanded = mapUiState.toolLauncherExpanded,
        actions =
            MapToolScaffoldActions(
                onToolSelected = { tool -> mapUiState = mapUiState.selectTool(tool) },
                onToggleLauncher = { mapUiState = mapUiState.toggleToolLauncher() },
                onExpand = { mapUiState = mapUiState.expandTool() },
                onCollapse = { mapUiState = mapUiState.collapseTool() },
                onClose = { mapUiState = mapUiState.closeTool() },
            ),
        mapContent = {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val source = mapUiState.source) {
                    PhoneMapSource.Online -> {
                        mapViewLifecycle(
                            mapView = mapRuntime.mapView,
                            onMapViewDestroyed = { destroyedMapView ->
                                mapRuntime = mapRuntime.invalidate(destroyedMapView)
                            },
                        )
                        synchronizeMapLocation(
                            runtime = mapRuntime,
                            locationState = locationState,
                            onRecenterHandled = { pendingRecenter = false },
                            context = context,
                        )
                        synchronizeGpxOverlay(
                            runtime = mapRuntime,
                            overlayState = gpxOverlayState,
                            hasFittedGpxOverlay = hasFittedGpxOverlay,
                            onOverlayFitted = { hasFittedGpxOverlay = true },
                        )
                        synchronizePoiOverlay(
                            runtime = mapRuntime,
                            pois = pois,
                            isVisible = mapUiState.contentVisibility.pois,
                        )
                        observePoiViewport(
                            runtime = mapRuntime,
                            isVisible = mapUiState.contentVisibility.pois,
                            onViewportChanged = onPoiViewportChanged,
                        )
                        observePoiSelection(
                            runtime = mapRuntime,
                            pois = pois,
                            isVisible = mapUiState.contentVisibility.pois,
                            onPoiSelected = { selectedPoi = it },
                        )
                        observeOnlineCamera(runtime = mapRuntime, onCameraChanged = { mapCamera = it })
                        synchronizeOnlineMapControls(
                            runtime = mapRuntime,
                            command = mapUiState.cameraCommand,
                            mapMode = mapUiState.mapMode,
                            onCameraCommandHandled = { commandId ->
                                mapUiState = mapUiState.consumeCommand(commandId)
                            },
                        )
                        mapSurface(
                            initialCamera = mapCamera,
                            onMapViewCreated = { createdMapView ->
                                mapRuntime = mapRuntime.beginMapView(createdMapView)
                                mapRuntime.generation.renderer
                            },
                            onMapReady = { callbackGeneration, callbackMapView, callbackMap ->
                                mapRuntime =
                                    mapRuntime.acceptMapReady(
                                        callbackGeneration = callbackGeneration,
                                        callbackMapView = callbackMapView,
                                        callbackMap = callbackMap,
                                    )
                            },
                            onStyleReady = { callbackGeneration, callbackMapView, callbackMap ->
                                mapRuntime =
                                    mapRuntime.acceptStyleReady(
                                        callbackGeneration = callbackGeneration,
                                        callbackMapView = callbackMapView,
                                        callbackMap = callbackMap,
                                    )
                            },
                        )
                    }

                    is PhoneMapSource.Offline -> {
                        offlineMapSurface(
                            state =
                                PhoneOfflineMapSurfaceState(
                                    map = source.map,
                                    themeConfig = offlineThemeConfig,
                                    initialCamera = mapCamera,
                                    gpxOverlays = gpxOverlayState.overlays,
                                    pois = pois.takeIf { mapUiState.contentVisibility.pois }.orEmpty(),
                                    mapMode = mapUiState.mapMode,
                                    cameraCommand = mapUiState.cameraCommand,
                                ),
                            callbacks =
                                PhoneOfflineMapsforgeCallbacks(
                                    onCameraChanged = { mapCamera = it },
                                    onViewportChanged = onPoiViewportChanged,
                                    onPoiSelected = { selectedPoi = it },
                                    onCameraCommandHandled = { commandId ->
                                        mapUiState = mapUiState.consumeCommand(commandId)
                                    },
                                    onMapError = { error ->
                                        offlineMapError = error
                                        mapUiState = mapUiState.copy(source = PhoneMapSource.Online)
                                    },
                                ),
                        )
                    }
                }

                mapControls(
                    onBack = onBack,
                    onZoomIn = { mapUiState = mapUiState.requestZoom(1) },
                    onZoomOut = { mapUiState = mapUiState.requestZoom(-1) },
                    mapMode = mapUiState.mapMode,
                    onCycleMapMode = cycleMapMode,
                )

                selectedPoi?.let { poi ->
                    phoneMapPoiDetailsCard(
                        poi = poi,
                        onDismiss = { selectedPoi = null },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    )
                }
                offlineMapError?.let { error ->
                    Card(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(error.messageResource()),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        },
        panelContent = { tool, contentMode ->
            MapToolPanelContent(
                tool = tool,
                contentMode = contentMode,
                state = toolsState,
                actions =
                    MapToolsPanelActions(
                        maps = mapActions,
                        onGpxVisibilityChanged = { visible ->
                            mapUiState =
                                mapUiState.copy(
                                    contentVisibility =
                                        mapUiState.contentVisibility.copy(gpxTracks = visible),
                                )
                        },
                        onGpxItemToggled = gpxViewModel::toggle,
                        onSelectGpxFolder = { selectGpxFolderLauncher.launch(null) },
                        onRescanGpxFolder = {
                            coroutineScope.launch {
                                gpxFolderScan =
                                    withContext(Dispatchers.IO) { gpxFolderSource.scanSelectedFolder() }
                                hasSelectedGpxFolder = gpxFolderSource.hasSelectedFolder()
                            }
                        },
                        onClearGpxFolder = {
                            gpxFolderSource.clearSelectedFolder()
                            hasSelectedGpxFolder = false
                            gpxFolderScan = PhoneGpxFolderScanResult()
                        },
                        onPoiVisibilityChanged = { visible ->
                            mapUiState =
                                mapUiState.copy(
                                    contentVisibility = mapUiState.contentVisibility.copy(pois = visible),
                                )
                        },
                        onFeatureSettings = { mapUiState = mapUiState.showFeatureSettings() },
                        onCycleMapMode = cycleMapMode,
                    ),
            )
        },
    )

    if (showOfflineThemeSelector) {
        offlineThemeSelector(
            config = offlineThemeConfig,
            onDismiss = { showOfflineThemeSelector = false },
            onSelectTheme = { themeId ->
                offlineThemeConfig =
                    offlineThemePreferences.save(
                        PhoneOfflineThemeCatalog.resolve(themeId, styleId = null),
                    )
            },
            onSelectStyle = { styleId ->
                offlineThemeConfig =
                    offlineThemePreferences.save(
                        PhoneOfflineThemeConfig(
                            themeId = offlineThemeConfig.themeId,
                            styleId = styleId,
                        ),
                    )
            },
        )
    }

    if (showOfflineBundleDownload) {
        PhoneOfflineBundleDialog(
            uiState = bundleUiState,
            onDismiss = { showOfflineBundleDownload = false },
            onStart = bundleViewModel::start,
            onCancel = bundleViewModel::cancel,
        )
    }
}

@Composable
private fun offlineThemeSelector(
    config: PhoneOfflineThemeConfig,
    onDismiss: () -> Unit,
    onSelectTheme: (String) -> Unit,
    onSelectStyle: (String) -> Unit,
) {
    val selectedTheme = PhoneOfflineThemeCatalog.themeFor(config.themeId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_theme_selector_title)) },
        text = {
            Column {
                Text(stringResource(R.string.map_theme_selector_theme_label))
                PhoneOfflineThemeCatalog.themes.forEach { theme ->
                    TextButton(onClick = { onSelectTheme(theme.id) }) {
                        Text(stringResource(theme.labelRes))
                    }
                }
                Text(
                    text = stringResource(R.string.map_theme_selector_style_label),
                    modifier = Modifier.padding(top = 8.dp),
                )
                selectedTheme.styles.forEach { style ->
                    TextButton(onClick = { onSelectStyle(style.id) }) {
                        Text(stringResource(style.labelRes))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}

@Composable
private fun observeOnlineCamera(
    runtime: MapRuntime,
    onCameraChanged: (PhoneMapCameraSnapshot) -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOnCameraChanged by rememberUpdatedState(onCameraChanged)
    DisposableEffect(runtime.map) {
        val activeMap = runtime.map ?: return@DisposableEffect onDispose {}
        val listener =
            MapLibreMap.OnCameraIdleListener {
                if (runtime.isCurrentIn(currentRuntime)) {
                    activeMap.cameraSnapshotOrNull()?.let(currentOnCameraChanged)
                }
            }
        activeMap.addOnCameraIdleListener(listener)
        listener.onCameraIdle()
        onDispose { activeMap.removeOnCameraIdleListener(listener) }
    }
}

@Composable
private fun synchronizeOnlineMapControls(
    runtime: MapRuntime,
    command: PhoneMapCameraCommand?,
    mapMode: PhoneMapMode,
    onCameraCommandHandled: (Long) -> Unit,
) {
    val currentOnCameraCommandHandled by rememberUpdatedState(onCameraCommandHandled)
    LaunchedEffect(runtime.map, command) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        val pendingCommand = command ?: return@LaunchedEffect
        activeMap.animateCamera(CameraUpdateFactory.zoomBy(pendingCommand.zoomDelta.toDouble()))
        currentOnCameraCommandHandled(pendingCommand.id)
    }
    LaunchedEffect(runtime.map, mapMode.orientation) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        // Heading data is not available yet, so both orientation states safely keep North up.
        activeMap.animateCamera(CameraUpdateFactory.bearingTo(0.0))
    }
}

private fun PhoneOfflineMapError.messageResource(): Int =
    when (this) {
        PhoneOfflineMapError.MISSING -> R.string.map_offline_map_missing
        PhoneOfflineMapError.INVALID -> R.string.map_offline_map_invalid
        PhoneOfflineMapError.FILE_NOT_READABLE -> R.string.map_offline_map_file_not_readable
        PhoneOfflineMapError.FILE_NOT_MAP -> R.string.map_offline_map_file_not_map
        PhoneOfflineMapError.FOLDER_PERMISSION_LOST -> R.string.map_offline_map_folder_permission_lost
        PhoneOfflineMapError.FOLDER_SCAN_FAILED -> R.string.map_offline_map_folder_scan_failed
        PhoneOfflineMapError.COPY_FAILED -> R.string.map_offline_map_copy_failed
    }

@Composable
private fun synchronizeMapLocation(
    runtime: MapRuntime,
    locationState: MapLocationState,
    onRecenterHandled: () -> Unit,
    context: Context,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentLocationState by rememberUpdatedState(locationState)
    val currentOnRecenterHandled by rememberUpdatedState(onRecenterHandled)

    LaunchedEffect(runtime.map, runtime.generation.styleRevision, locationState.hasPermission) {
        if (!locationState.hasPermission) return@LaunchedEffect
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { activeMap, _, style ->
            if (currentLocationState.hasPermission) {
                activeMap.enableLocationPuck(style = style, context = context)
            }
        }
    }

    LaunchedEffect(
        locationState.pendingRecenter,
        runtime.map,
        runtime.generation.styleRevision,
        locationState.hasPermission,
    ) {
        if (!locationState.pendingRecenter || !locationState.hasPermission) return@LaunchedEffect
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { activeMap, _, style ->
            if (currentLocationState.pendingRecenter && currentLocationState.hasPermission) {
                activeMap.recenterOnLocation(style = style, context = context)
                currentOnRecenterHandled()
            }
        }
    }
}

@Composable
private fun synchronizeGpxOverlay(
    runtime: MapRuntime,
    overlayState: GpxOverlayState,
    hasFittedGpxOverlay: Boolean,
    onOverlayFitted: () -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOverlayState by rememberUpdatedState(overlayState)
    val currentHasFittedGpxOverlay by rememberUpdatedState(hasFittedGpxOverlay)
    val currentOnOverlayFitted by rememberUpdatedState(onOverlayFitted)

    LaunchedEffect(
        runtime.map,
        runtime.mapView,
        runtime.generation.styleRevision,
        overlayState.segments,
        overlayState.isVisible,
    ) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { activeMap, activeMapView, style ->
            val latestOverlayState = currentOverlayState
            style.renderGpxTrack(
                segments = latestOverlayState.segments,
                isVisible = latestOverlayState.isVisible,
            )
            if (
                latestOverlayState.isVisible &&
                latestOverlayState.segments.isNotEmpty() &&
                !currentHasFittedGpxOverlay
            ) {
                activeMap.fitGpxTrackBounds(
                    mapView = activeMapView,
                    segments = latestOverlayState.segments,
                    isCurrent = { runtime.isCurrentIn(currentRuntime) },
                    onFitted = currentOnOverlayFitted,
                )
            }
        }
    }
}

@Composable
private fun mapSurface(
    initialCamera: PhoneMapCameraSnapshot,
    onMapViewCreated: (MapView) -> Long,
    onMapReady: (Long, MapView, MapLibreMap) -> Unit,
    onStyleReady: (Long, MapView, MapLibreMap) -> Unit,
) {
    AndroidView(
        factory = { viewContext ->
            createMapView(
                context = viewContext,
                initialCamera = initialCamera,
                onCreated = onMapViewCreated,
                onMapReady = onMapReady,
                onStyleReady = onStyleReady,
            )
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun mapControls(
    onBack: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    mapMode: PhoneMapMode,
    onCycleMapMode: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        FilledTonalIconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_action_back),
            )
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                onClick = onZoomIn,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.map_zoom_in_content_description),
                )
            }
            FilledTonalIconButton(onClick = onZoomOut) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.map_zoom_out_content_description),
                )
            }
            FilledTonalIconButton(onClick = onCycleMapMode) {
                Icon(
                    imageVector = mapMode.icon(),
                    contentDescription =
                        stringResource(
                            R.string.map_mode_content_description,
                            stringResource(mapMode.labelResource()),
                        ),
                )
            }
        }
    }
}

private fun PhoneMapMode.icon(): ImageVector =
    when {
        follow == PhoneMapFollowMode.FOLLOW_LOCATION -> Icons.Filled.MyLocation
        orientation == PhoneMapOrientation.HEADING_UP -> Icons.Filled.Explore
        else -> Icons.Filled.Navigation
    }

private fun createMapView(
    context: Context,
    initialCamera: PhoneMapCameraSnapshot,
    onCreated: (MapView) -> Long,
    onMapReady: (Long, MapView, MapLibreMap) -> Unit,
    onStyleReady: (Long, MapView, MapLibreMap) -> Unit,
): MapView {
    ensureMapLibreConfigured(context)
    return MapView(context).also { mapView ->
        val generation = onCreated(mapView)
        mapView.onCreate(null)
        mapView.getMapAsync { map ->
            if (mapView.isDestroyed) return@getMapAsync
            onMapReady(generation, mapView, map)
            map.setStyle(
                Style.Builder().fromJson(
                    PhoneMapRendererCatalog.mainOnlineRasterProvider.mapLibreRasterStyleJson(),
                ),
            ) {
                if (!mapView.isDestroyed) {
                    map.moveCamera(initialCamera.toMapLibreCameraUpdate())
                    onStyleReady(generation, mapView, map)
                }
            }
        }
    }
}

/** Keeps MapLibre's ordered lifecycle callbacks together rather than splitting the state machine. */
@Suppress("CyclomaticComplexMethod", "DEPRECATION", "OVERRIDE_DEPRECATION")
@Composable
private fun mapViewLifecycle(
    mapView: MapView?,
    onMapViewDestroyed: (MapView) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val applicationContext = LocalContext.current.applicationContext
    val currentOnMapViewDestroyed by rememberUpdatedState(onMapViewDestroyed)

    DisposableEffect(mapView, lifecycleOwner, applicationContext) {
        if (mapView == null) {
            return@DisposableEffect onDispose {}
        }

        var started = false
        var resumed = false
        var destroyed = false

        fun start() {
            if (!destroyed && !started) {
                mapView.onStart()
                started = true
            }
        }

        fun resume() {
            if (!destroyed && !resumed) {
                mapView.onResume()
                resumed = true
            }
        }

        fun pause() {
            if (!destroyed && resumed) {
                mapView.onPause()
                resumed = false
            }
        }

        fun stop() {
            if (!destroyed && started) {
                mapView.onStop()
                started = false
            }
        }

        fun destroy() {
            if (!destroyed) {
                currentOnMapViewDestroyed(mapView)
                pause()
                stop()
                mapView.onDestroy()
                destroyed = true
            }
        }

        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> start()
                    Lifecycle.Event.ON_RESUME -> resume()
                    Lifecycle.Event.ON_PAUSE -> pause()
                    Lifecycle.Event.ON_STOP -> stop()
                    Lifecycle.Event.ON_DESTROY -> destroy()
                    else -> Unit
                }
            }
        val memoryCallbacks =
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    if (!destroyed) {
                        mapView.onLowMemory()
                    }
                }

                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                        onLowMemory()
                    }
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)
        applicationContext.registerComponentCallbacks(memoryCallbacks)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            start()
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resume()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            destroy()
        }
    }
}

@SuppressLint("MissingPermission")
private fun MapLibreMap.enableLocationPuck(
    style: Style,
    context: Context,
) {
    val locationComponent = locationComponent
    if (!locationComponent.isLocationComponentActivated) {
        locationComponent.activateLocationComponent(
            LocationComponentActivationOptions.builder(context.applicationContext, style).build(),
        )
    }
    locationComponent.isLocationComponentEnabled = true
    locationComponent.cameraMode = CameraMode.NONE
}

@SuppressLint("MissingPermission")
private fun MapLibreMap.recenterOnLocation(
    style: Style,
    context: Context,
) {
    enableLocationPuck(style = style, context = context)
    locationComponent.lastKnownLocation?.let { location ->
        animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude),
                RECENTER_ZOOM,
            ),
        )
    }
}

private fun Context.hasLocationPermission(): Boolean =
    locationPermissions.any { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun PhoneMapCameraSnapshot.toMapLibreCameraUpdate() =
    CameraUpdateFactory.newLatLngZoom(
        LatLng(latitude, longitude),
        zoom,
    )

private fun MapLibreMap.cameraSnapshotOrNull(): PhoneMapCameraSnapshot? =
    runCatching {
        val camera = cameraPosition
        val target = camera.target ?: return@runCatching null
        PhoneMapCameraSnapshot(
            latitude = target.latitude,
            longitude = target.longitude,
            zoom = camera.zoom,
        )
    }.getOrNull()
