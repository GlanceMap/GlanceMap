@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
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

private data class MapRuntime(
    val map: MapLibreMap?,
    val mapView: MapView?,
    val style: Style?,
)

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
    val offlineThemePreferences = remember(context) { PhoneOfflineThemePreferences(context.applicationContext) }
    val bundleViewModel: PhoneOfflineBundleViewModel = viewModel()
    val bundleUiState by bundleViewModel.uiState.collectAsState()
    val gpxViewModel: PhoneMapGpxViewModel = viewModel()
    val gpxUiState by gpxViewModel.uiState.collectAsState()
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var mapUiState by remember { mutableStateOf(PhoneMapUiState()) }
    var offlineMaps by remember { mutableStateOf(emptyList<PhoneOfflineMap>()) }
    var hasSelectedMapFolder by remember(mapFolderSource) {
        mutableStateOf(mapFolderSource.hasSelectedFolder())
    }
    var offlineThemeConfig by remember(offlineThemePreferences) {
        mutableStateOf(offlineThemePreferences.load())
    }
    var mapCamera by remember { mutableStateOf(defaultMapCamera) }
    var showOfflineThemeSelector by remember { mutableStateOf(false) }
    var showOfflineBundleDownload by remember { mutableStateOf(false) }
    var featureSettingsTool by remember { mutableStateOf<MapTool?>(null) }
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
    val mapRuntime = MapRuntime(map = map, mapView = mapView, style = style)
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
                        val importedMap = imported.map
                        offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                        map?.cameraSnapshotOrNull()?.let { mapCamera = it }
                        mapView = null
                        map = null
                        style = null
                        mapUiState = mapUiState.copy(source = PhoneMapSource.Offline(importedMap))
                        offlineMapError = null
                    }

                    is PhoneOfflineMapImportResult.Failure -> offlineMapError = imported.error
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
    LaunchedEffect(gpxSources, initiallyEnabledGpxId) {
        gpxViewModel.synchronize(gpxSources, initiallyEnabledGpxId)
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
                ),
            poi =
                MapToolsPoiState(
                    sources = poiSources,
                    globalVisible = mapUiState.contentVisibility.pois,
                ),
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
                        map?.cameraSnapshotOrNull()?.let { mapCamera = it }
                        mapView = null
                        map = null
                        style = null
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

    BackHandler(enabled = mapUiState.toolPanel.mode != MapToolPanelMode.CLOSED) {
        mapUiState = mapUiState.onToolBack()
    }

    MapToolScaffold(
        state = mapUiState.toolPanel,
        actions =
            MapToolScaffoldActions(
                onToolSelected = { tool -> mapUiState = mapUiState.selectTool(tool) },
                onExpand = { mapUiState = mapUiState.expandTool() },
                onCollapse = { mapUiState = mapUiState.collapseTool() },
                onClose = { mapUiState = mapUiState.onToolBack().onToolBack() },
            ),
        mapContent = {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val source = mapUiState.source) {
                    PhoneMapSource.Online -> {
                        mapViewLifecycle(mapView)
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
                            style = style,
                            pois = pois,
                            isVisible = mapUiState.contentVisibility.pois,
                        )
                        observePoiViewport(
                            map = map,
                            isVisible = mapUiState.contentVisibility.pois,
                            onViewportChanged = onPoiViewportChanged,
                        )
                        observePoiSelection(
                            map = map,
                            pois = pois,
                            isVisible = mapUiState.contentVisibility.pois,
                            onPoiSelected = { selectedPoi = it },
                        )
                        observeOnlineCamera(map = map, onCameraChanged = { mapCamera = it })
                        mapSurface(
                            initialCamera = mapCamera,
                            onMapViewCreated = { mapView = it },
                            onMapReady = { map = it },
                            onStyleReady = { style = it },
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
                                ),
                            callbacks =
                                PhoneOfflineMapsforgeCallbacks(
                                    onCameraChanged = { mapCamera = it },
                                    onViewportChanged = onPoiViewportChanged,
                                    onPoiSelected = { selectedPoi = it },
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
                    onRecenter = {
                        if (context.hasLocationPermission()) {
                            hasLocationPermission = true
                            pendingRecenter = true
                        } else {
                            locationPermissionLauncher.launch(locationPermissions)
                        }
                    },
                    showOnlineControls = mapUiState.source is PhoneMapSource.Online,
                )

                selectedPoi?.let { poi ->
                    phoneMapPoiDetailsCard(
                        poi = poi,
                        onDismiss = { selectedPoi = null },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
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
        panelContent = { tool ->
            MapToolPanelContent(
                tool = tool,
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
                        onPoiVisibilityChanged = { visible ->
                            mapUiState =
                                mapUiState.copy(
                                    contentVisibility = mapUiState.contentVisibility.copy(pois = visible),
                                )
                        },
                        onFeatureSettings = { settingsTool -> featureSettingsTool = settingsTool },
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
    featureSettingsTool?.let { tool ->
        MapToolFeatureSettingsDialog(tool = tool, onDismiss = { featureSettingsTool = null })
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
    map: MapLibreMap?,
    onCameraChanged: (PhoneMapCameraSnapshot) -> Unit,
) {
    val currentOnCameraChanged by rememberUpdatedState(onCameraChanged)
    DisposableEffect(map) {
        val activeMap = map ?: return@DisposableEffect onDispose {}
        val listener =
            MapLibreMap.OnCameraIdleListener {
                activeMap.cameraSnapshotOrNull()?.let(currentOnCameraChanged)
            }
        activeMap.addOnCameraIdleListener(listener)
        listener.onCameraIdle()
        onDispose { activeMap.removeOnCameraIdleListener(listener) }
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
    LaunchedEffect(runtime.map, runtime.style, locationState.hasPermission) {
        if (!locationState.hasPermission) return@LaunchedEffect
        val activeMap = runtime.map ?: return@LaunchedEffect
        val activeStyle = runtime.style ?: return@LaunchedEffect
        activeMap.enableLocationPuck(style = activeStyle, context = context)
    }

    LaunchedEffect(
        locationState.pendingRecenter,
        runtime.map,
        runtime.style,
        locationState.hasPermission,
    ) {
        if (!locationState.pendingRecenter || !locationState.hasPermission) return@LaunchedEffect
        val activeMap = runtime.map ?: return@LaunchedEffect
        val activeStyle = runtime.style ?: return@LaunchedEffect
        activeMap.recenterOnLocation(style = activeStyle, context = context)
        onRecenterHandled()
    }
}

@Composable
private fun synchronizeGpxOverlay(
    runtime: MapRuntime,
    overlayState: GpxOverlayState,
    hasFittedGpxOverlay: Boolean,
    onOverlayFitted: () -> Unit,
) {
    LaunchedEffect(
        runtime.map,
        runtime.mapView,
        runtime.style,
        overlayState.segments,
        overlayState.isVisible,
    ) {
        val activeStyle = runtime.style ?: return@LaunchedEffect
        activeStyle.renderGpxTrack(
            segments = overlayState.segments,
            isVisible = overlayState.isVisible,
        )
        val activeMap = runtime.map ?: return@LaunchedEffect
        val activeMapView = runtime.mapView ?: return@LaunchedEffect
        if (overlayState.isVisible && overlayState.segments.isNotEmpty() && !hasFittedGpxOverlay) {
            activeMap.fitGpxTrackBounds(
                mapView = activeMapView,
                segments = overlayState.segments,
                onFitted = onOverlayFitted,
            )
        }
    }
}

@Composable
private fun mapSurface(
    initialCamera: PhoneMapCameraSnapshot,
    onMapViewCreated: (MapView) -> Unit,
    onMapReady: (MapLibreMap) -> Unit,
    onStyleReady: (Style) -> Unit,
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
    onRecenter: () -> Unit,
    showOnlineControls: Boolean,
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
        if (showOnlineControls) {
            FilledTonalIconButton(
                onClick = onRecenter,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.map_recenter_content_description),
                )
            }
        }
    }
}

private fun createMapView(
    context: Context,
    initialCamera: PhoneMapCameraSnapshot,
    onCreated: (MapView) -> Unit,
    onMapReady: (MapLibreMap) -> Unit,
    onStyleReady: (Style) -> Unit,
): MapView {
    ensureMapLibreConfigured(context)
    return MapView(context).also { mapView ->
        onCreated(mapView)
        mapView.onCreate(null)
        mapView.getMapAsync { map ->
            if (mapView.isDestroyed) return@getMapAsync
            onMapReady(map)
            map.setStyle(
                Style.Builder().fromJson(
                    PhoneMapRendererCatalog.mainOnlineRasterProvider.mapLibreRasterStyleJson(),
                ),
            ) { style ->
                if (!mapView.isDestroyed) {
                    map.moveCamera(initialCamera.toMapLibreCameraUpdate())
                    onStyleReady(style)
                }
            }
        }
    }
}

/** Keeps MapLibre's ordered lifecycle callbacks together rather than splitting the state machine. */
@Suppress("CyclomaticComplexMethod", "DEPRECATION", "OVERRIDE_DEPRECATION")
@Composable
private fun mapViewLifecycle(mapView: MapView?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val applicationContext = LocalContext.current.applicationContext

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
