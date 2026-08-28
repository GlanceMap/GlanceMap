@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.ensureMapLibreConfigured
import com.glancemap.glancemapcompanionapp.map.maplibre.fitGpxTrackBounds
import com.glancemap.glancemapcompanionapp.map.maplibre.mapLibreRasterStyleJson
import com.glancemap.glancemapcompanionapp.map.maplibre.renderGpxTrack
import com.glancemap.trailcore.map.MapContentVisibility
import com.glancemap.trailcore.map.MapMode
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
    val track: PhoneMapGpxTrack?,
    val segments: List<PhoneMapRouteSegment>,
    val isVisible: Boolean,
)

private data class MapContentControlState(
    val gpxTracksVisible: Boolean?,
    val poisVisible: Boolean,
)

private data class MapFolderActions(
    val hasSelectedFolder: Boolean,
    val onSelectFolder: () -> Unit,
    val onRescanFolder: () -> Unit,
    val onClearFolder: () -> Unit,
)

private data class MapSourceSelectorActions(
    val onDismiss: () -> Unit,
    val onSelectOnline: () -> Unit,
    val onSelectOffline: (PhoneOfflineMap) -> Unit,
    val onImportMap: () -> Unit,
    val folder: MapFolderActions,
)

/** Keeps Compose-owned MapLibre, permission, and GPX overlay sequencing in one visible flow. */
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
@Composable
internal fun CompanionMapScreen(
    gpxTrack: PhoneMapGpxTrack?,
    pois: List<PhoneMapPoi>,
    onPoiViewportChanged: (PhoneMapViewport) -> Unit,
    onPoiVisibilityChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val offlineMapStore = remember(context) { PhoneOfflineMapStore(context.applicationContext) }
    val mapFolderSource =
        remember(context, offlineMapStore) {
            PhoneOfflineMapFolderSource(context.applicationContext, offlineMapStore)
        }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var mapSource by remember { mutableStateOf<PhoneMapSource>(PhoneMapSource.Online) }
    var offlineMaps by remember { mutableStateOf(emptyList<PhoneOfflineMap>()) }
    var hasSelectedMapFolder by remember(mapFolderSource) {
        mutableStateOf(mapFolderSource.hasSelectedFolder())
    }
    var mapCamera by remember { mutableStateOf(defaultMapCamera) }
    var showMapSourceSelector by remember { mutableStateOf(false) }
    var offlineMapError by remember { mutableStateOf<PhoneOfflineMapError?>(null) }
    var hasLocationPermission by remember(context) { mutableStateOf(context.hasLocationPermission()) }
    var pendingRecenter by remember { mutableStateOf(false) }
    var contentVisibility by remember { mutableStateOf(MapContentVisibility()) }
    var fittedGpxTrackId by remember { mutableStateOf<String?>(null) }
    var selectedPoi by remember { mutableStateOf<PhoneMapPoi?>(null) }
    val gpxSegments = remember(gpxTrack) { gpxTrack?.toRouteSegments().orEmpty() }
    val hasRenderableGpxTrack = gpxSegments.isNotEmpty()
    val mapRuntime = MapRuntime(map = map, mapView = mapView, style = style)
    val locationState =
        MapLocationState(
            hasPermission = hasLocationPermission,
            pendingRecenter = pendingRecenter,
        )
    val gpxOverlayState =
        GpxOverlayState(
            track = gpxTrack,
            segments = gpxSegments,
            isVisible = contentVisibility.gpxTracks,
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
                        mapSource = PhoneMapSource.Offline(importedMap)
                        offlineMapError = null
                        showMapSourceSelector = false
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
        val selectedOfflineMap = (mapSource as? PhoneMapSource.Offline)?.map
        if (selectedOfflineMap != null && selectedOfflineMap !in offlineMaps) {
            mapSource = PhoneMapSource.Online
            offlineMapError = PhoneOfflineMapError.MISSING
        }
    }
    LaunchedEffect(contentVisibility.pois, mapSource.mode) {
        onPoiVisibilityChanged(contentVisibility.pois && mapSource.mode == MapMode.ONLINE)
        if (!contentVisibility.pois) selectedPoi = null
    }
    LaunchedEffect(pois) {
        if (selectedPoi?.id !in pois.map(PhoneMapPoi::id).toSet()) selectedPoi = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val source = mapSource) {
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
                    fittedGpxTrackId = fittedGpxTrackId,
                    onTrackFitted = { fittedGpxTrackId = it },
                )
                synchronizePoiOverlay(
                    style = style,
                    pois = pois,
                    isVisible = contentVisibility.pois,
                )
                observePoiViewport(
                    map = map,
                    isVisible = contentVisibility.pois,
                    onViewportChanged = onPoiViewportChanged,
                )
                observePoiSelection(
                    map = map,
                    pois = pois,
                    isVisible = contentVisibility.pois,
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
                    map = source.map,
                    initialCamera = mapCamera,
                    onCameraChanged = { mapCamera = it },
                    onMapError = { error ->
                        offlineMapError = error
                        mapSource = PhoneMapSource.Online
                    },
                )
                Card(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.map_offline_overlays_unavailable),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        mapControls(
            mapSourceLabel =
                when (val source = mapSource) {
                    PhoneMapSource.Online -> stringResource(R.string.map_source_online)
                    is PhoneMapSource.Offline ->
                        stringResource(R.string.map_source_offline, source.map.displayName)
                },
            contentState =
                MapContentControlState(
                    gpxTracksVisible = contentVisibility.gpxTracks.takeIf { hasRenderableGpxTrack },
                    poisVisible = contentVisibility.pois,
                ),
            onBack = onBack,
            onMapSourceClick = { showMapSourceSelector = true },
            onRecenter = {
                if (context.hasLocationPermission()) {
                    hasLocationPermission = true
                    pendingRecenter = true
                } else {
                    locationPermissionLauncher.launch(locationPermissions)
                }
            },
            showOnlineControls = mapSource is PhoneMapSource.Online,
            onGpxVisibilityToggle =
                if (hasRenderableGpxTrack) {
                    {
                        contentVisibility =
                            contentVisibility.copy(gpxTracks = !contentVisibility.gpxTracks)
                    }
                } else {
                    null
                },
            onPoiVisibilityToggle = {
                contentVisibility = contentVisibility.copy(pois = !contentVisibility.pois)
            },
        )

        if (mapSource is PhoneMapSource.Online) {
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

    if (showMapSourceSelector) {
        mapSourceSelector(
            offlineMaps = offlineMaps,
            actions =
                MapSourceSelectorActions(
                    onDismiss = { showMapSourceSelector = false },
                    onSelectOnline = {
                        mapSource = PhoneMapSource.Online
                        offlineMapError = null
                        showMapSourceSelector = false
                    },
                    onSelectOffline = { selectedMap ->
                        coroutineScope.launch {
                            val error = withContext(Dispatchers.IO) { offlineMapStore.validate(selectedMap) }
                            if (error == null) {
                                map?.cameraSnapshotOrNull()?.let { mapCamera = it }
                                mapView = null
                                map = null
                                style = null
                                mapSource = PhoneMapSource.Offline(selectedMap)
                                offlineMapError = null
                                showMapSourceSelector = false
                            } else {
                                offlineMapError = error
                            }
                        }
                    },
                    onImportMap = {
                        selectLocalMapLauncher.launch(arrayOf("application/octet-stream"))
                    },
                    folder =
                        MapFolderActions(
                            hasSelectedFolder = hasSelectedMapFolder,
                            onSelectFolder = { selectMapFolderLauncher.launch(null) },
                            onRescanFolder = {
                                coroutineScope.launch {
                                    val syncResult =
                                        withContext(Dispatchers.IO) {
                                            mapFolderSource.syncSelectedFolder()
                                        }
                                    offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                                    hasSelectedMapFolder = mapFolderSource.hasSelectedFolder()
                                    offlineMapError = syncResult.error
                                }
                            },
                            onClearFolder = {
                                mapFolderSource.clearSelectedFolder()
                                hasSelectedMapFolder = false
                            },
                        ),
                ),
        )
    }
}

@Composable
private fun mapSourceSelector(
    offlineMaps: List<PhoneOfflineMap>,
    actions: MapSourceSelectorActions,
) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.map_source_selector_title)) },
        text = {
            Column {
                TextButton(onClick = actions.onSelectOnline) {
                    Text(stringResource(R.string.map_source_select_online))
                }
                if (offlineMaps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.map_source_no_offline_maps),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    offlineMaps.forEach { map ->
                        TextButton(onClick = { actions.onSelectOffline(map) }) {
                            Text(stringResource(R.string.map_source_select_offline, map.displayName))
                        }
                    }
                }
                TextButton(onClick = actions.onImportMap) {
                    Text(stringResource(R.string.map_source_import_local_map))
                }
                TextButton(onClick = actions.folder.onSelectFolder) {
                    Text(stringResource(R.string.map_source_select_map_folder))
                }
                if (actions.folder.hasSelectedFolder) {
                    Text(
                        text = stringResource(R.string.map_source_map_folder_selected),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    TextButton(onClick = actions.folder.onRescanFolder) {
                        Text(stringResource(R.string.map_source_rescan_map_folder))
                    }
                    TextButton(onClick = actions.folder.onClearFolder) {
                        Text(stringResource(R.string.map_source_clear_map_folder))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.common_action_close)) }
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
    fittedGpxTrackId: String?,
    onTrackFitted: (String) -> Unit,
) {
    LaunchedEffect(
        runtime.map,
        runtime.mapView,
        runtime.style,
        overlayState.track?.id,
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
        val trackId = overlayState.track?.id ?: return@LaunchedEffect
        if (overlayState.isVisible && overlayState.segments.isNotEmpty() && fittedGpxTrackId != trackId) {
            activeMap.fitGpxTrackBounds(
                mapView = activeMapView,
                segments = overlayState.segments,
                onFitted = { onTrackFitted(trackId) },
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
@Suppress("LongParameterList")
private fun mapControls(
    mapSourceLabel: String,
    contentState: MapContentControlState,
    onBack: () -> Unit,
    onMapSourceClick: () -> Unit,
    onRecenter: () -> Unit,
    showOnlineControls: Boolean,
    onGpxVisibilityToggle: (() -> Unit)?,
    onPoiVisibilityToggle: () -> Unit,
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
            FilledTonalButton(onClick = onMapSourceClick) { Text(mapSourceLabel) }
            if (showOnlineControls) {
                FilledTonalIconButton(onClick = onRecenter) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = stringResource(R.string.map_recenter_content_description),
                    )
                }
                if (onGpxVisibilityToggle != null) {
                    mapContentVisibilityButton(
                        isVisible = contentState.gpxTracksVisible == true,
                        onClick = onGpxVisibilityToggle,
                        hideContentDescription = R.string.map_gpx_hide_content_description,
                        showContentDescription = R.string.map_gpx_show_content_description,
                    )
                }
                mapContentVisibilityButton(
                    isVisible = contentState.poisVisible,
                    onClick = onPoiVisibilityToggle,
                    hideContentDescription = R.string.map_poi_hide_content_description,
                    showContentDescription = R.string.map_poi_show_content_description,
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
