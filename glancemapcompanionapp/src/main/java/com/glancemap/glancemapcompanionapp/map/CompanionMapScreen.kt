@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.map

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glancemap.glancemapcompanionapp.FileTransferViewModel
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.ensureMapLibreConfigured
import com.glancemap.glancemapcompanionapp.map.maplibre.fitGpxTrackBounds
import com.glancemap.glancemapcompanionapp.map.maplibre.mapLibreRasterStyleJson
import com.glancemap.glancemapcompanionapp.map.maplibre.renderDistanceMeasurement
import com.glancemap.glancemapcompanionapp.map.maplibre.renderGpxTrack
import com.glancemap.glancemapcompanionapp.map.maplibre.renderPointSelectionMarkers
import com.glancemap.glancemapcompanionapp.map.maplibre.renderRouteAnalysisMarkers
import com.glancemap.trailcore.geo.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

internal val defaultPhoneMapCamera =
    PhoneMapCameraSnapshot(latitude = 20.0, longitude = 0.0, zoom = PHONE_MAP_DEFAULT_ZOOM)
private const val PHONE_MAP_LIVE_ELEVATION_GPS_FALLBACK_METERS = 100.0
private const val PHONE_MAP_LIVE_ELEVATION_RESAMPLE_DISTANCE_METERS = 3.0
private const val PHONE_MAP_LIVE_METRICS_REFRESH_MS = 320L
private const val PHONE_STORAGE_OPERATION_STOP_TIMEOUT_MS = 5_000L
private const val PHONE_STORAGE_OPERATION_POLL_DELAY_MS = 50L
private const val PHONE_STORAGE_RENDER_STOP_DELAY_MS = 100L

private fun PhoneOfflineStorageMigrationPhase.isPhoneStorageMigrationActive(): Boolean =
    this in
        setOf(
            PhoneOfflineStorageMigrationPhase.COPYING,
            PhoneOfflineStorageMigrationPhase.VERIFYING,
            PhoneOfflineStorageMigrationPhase.SWITCHING,
            PhoneOfflineStorageMigrationPhase.CLEANUP,
        )

private fun storageChangeIsNoOp(
    target: PhoneOfflineStorageLocation,
    current: PhoneOfflineStorageLocation,
    storage: PhoneOfflineStorage,
    pending: PhoneOfflineStorageMigrationJournal?,
): Boolean = target == current && !storage.needsCanonicalMigration() && pending == null

private fun isPhoneMapRouteAnalysisAvailable(
    inspectionEnabled: Boolean,
    gpxTracksVisible: Boolean,
    routeToolsOpen: Boolean,
    poiCreationActive: Boolean,
    poiCreationPointSet: Boolean,
): Boolean =
    listOf(
        inspectionEnabled,
        gpxTracksVisible,
        !routeToolsOpen,
        !poiCreationActive,
        !poiCreationPointSet,
    ).all { condition -> condition }

private fun phoneStorageOperationsActive(
    bundleViewModel: PhoneOfflineBundleViewModel,
    fileTransferViewModel: FileTransferViewModel,
): Boolean =
    listOf(
        bundleViewModel.uiState.value.download is PhoneOfflineBundleDownloadState.Downloading,
        fileTransferViewModel.uiState.value.isTransferring,
        fileTransferViewModel.isImportingRefuges.value,
        fileTransferViewModel.isDownloadingRouting.value,
    ).any { it }

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

internal data class PhoneOnlineCameraSyncSkipKey(
    val reason: String,
    val follow: PhoneMapFollowMode,
    val orientation: PhoneMapOrientation,
)

internal fun shouldRecordPhoneOnlineCameraSyncSkip(
    previous: PhoneOnlineCameraSyncSkipKey?,
    current: PhoneOnlineCameraSyncSkipKey,
): Boolean = previous != current

private data class MapLocationState(
    val hasPermission: Boolean,
    val location: PhoneMapLocation?,
)

private data class GpxOverlayState(
    val overlays: List<PhoneMapGpxOverlay>,
    val segments: List<PhoneMapRouteSegment>,
    val isVisible: Boolean,
    val settings: PhoneMapGpxSettings,
)

private data class PhoneMapViewCallbacks(
    val onTwoFingerTap: (PhoneMapCoordinate, PhoneMapCoordinate) -> Unit,
    val onMapTap: (PhoneMapCoordinate) -> Unit,
    val onMapLongPress: (PhoneMapCoordinate) -> Unit,
    val onCreated: (MapView) -> Long,
    val onMapReady: (Long, MapView, MapLibreMap) -> Unit,
    val onStyleReady: (Long, MapView, MapLibreMap) -> Unit,
)

private sealed interface PhoneMapManagedContent {
    val displayName: String

    data class OfflineMap(
        val map: PhoneOfflineMap,
    ) : PhoneMapManagedContent {
        override val displayName: String = map.displayName
    }

    data class Gpx(
        val item: PhoneMapGpxItem,
    ) : PhoneMapManagedContent {
        override val displayName: String = item.displayName
    }

    data class Poi(
        val source: PhoneMapPoiSource,
    ) : PhoneMapManagedContent {
        override val displayName: String = source.fileName
    }
}

private fun PhoneMapManagedContent.renameValue(): String =
    when (this) {
        is PhoneMapManagedContent.OfflineMap -> map.displayName.removeKnownExtension(".map")
        is PhoneMapManagedContent.Gpx -> item.displayName.removeKnownExtension(".gpx")
        is PhoneMapManagedContent.Poi -> source.fileName.removeKnownExtension(".poi")
    }

private fun String.removeKnownExtension(extension: String): String =
    takeIf { endsWith(extension, ignoreCase = true) }
        ?.dropLast(extension.length)
        ?: this

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
    onPoiSourceVisibilityChanged: (String, Boolean) -> Unit,
    onRenamePoiSource: suspend (String, String) -> String,
    onDeletePoiSource: suspend (String) -> Unit,
    onPoiDataChanged: () -> Unit,
    onRenameRoute: suspend (String, String) -> Unit,
    onDeleteRoute: suspend (String) -> Unit,
    onRouteSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val offlineMapStore = remember(context) { PhoneOfflineMapStore(context.applicationContext) }
    val bundleStore = remember(context) { PhoneOfflineBundleStore(context.applicationContext) }
    val elevationStore = remember(context) { PhoneElevationStore(context.applicationContext) }
    val elevationRepository = remember(context) { PhoneElevationRepository(context.applicationContext) }
    val offlineStorage = remember(context) { PhoneOfflineStorage(context.applicationContext) }
    val userPoiStore = remember(context) { PhoneMapUserPoiStore(context.applicationContext) }
    val mapFolderSource =
        remember(context, offlineMapStore) {
            PhoneOfflineMapFolderSource(context.applicationContext, offlineMapStore)
        }
    val gpxFolderSource = remember(context) { PhoneGpxFolderSource(context.applicationContext) }
    val gpxSettingsPreferences = remember(context) { PhoneMapGpxSettingsPreferences(context.applicationContext) }
    val compassSensorSource = remember(context) { PhoneCompassSensorSource(context.applicationContext) }
    val sensorCapabilities = remember(context) { resolvePhoneSensorCapabilities(context.applicationContext) }
    val compassSettingsPreferences =
        remember(context) { PhoneCompassSettingsPreferences(context.applicationContext) }
    val mapLocationSource = remember(context) { PhoneMapLocationSource(context.applicationContext) }
    val generalSettingsPreferences =
        remember(context) { PhoneGeneralSettingsPreferences(context.applicationContext) }
    val offlineThemePreferences = remember(context) { PhoneOfflineThemePreferences(context.applicationContext) }
    val mapSettingsPreferences = remember(context) { PhoneMapSettingsPreferences(context.applicationContext) }
    val mapSourcePreferences = remember(context) { PhoneMapSourcePreferences(context.applicationContext) }
    val poiSettingsPreferences = remember(context) { PhoneMapPoiSettingsPreferences(context.applicationContext) }
    val bundleViewModel: PhoneOfflineBundleViewModel = viewModel()
    val bundleUiState by bundleViewModel.uiState.collectAsState()
    val fileTransferViewModel: FileTransferViewModel = viewModel()
    val gpxViewModel: PhoneMapGpxViewModel = viewModel()
    val routeToolsViewModel: PhoneRouteToolsViewModel = viewModel()
    val gpxUiState by gpxViewModel.uiState.collectAsState()
    val routeToolsState by routeToolsViewModel.uiState.collectAsState()
    val compassState by compassSensorSource.state.collectAsState()
    val mapLocation by mapLocationSource.location.collectAsState()
    var mapRuntime by remember { mutableStateOf(MapRuntime()) }
    var comparisonMapRuntime by remember { mutableStateOf(MapRuntime()) }
    var mapUiState by remember { mutableStateOf(PhoneMapUiState()) }
    var mapSourcePreference by remember(mapSourcePreferences) {
        mutableStateOf(mapSourcePreferences.load())
    }
    val selectedOnlineSource =
        mapSourcePreference.onlineSource.takeIf(PhoneMapRendererCatalog::isComparisonOnlineSourceAvailable)
            ?: PhoneOnlineMapSource.OPEN_TOPO
    val selectedOnlineProvider =
        requireNotNull(PhoneMapRendererCatalog.providerForComparisonOnlineSource(selectedOnlineSource))
    var pendingOfflineMap by remember { mutableStateOf<PhoneOfflineMap?>(null) }
    var onlineGestureState by remember { mutableStateOf(PhoneOnlineGestureState()) }
    var generalSettings by remember(generalSettingsPreferences) {
        mutableStateOf(generalSettingsPreferences.load())
    }
    var compassSettings by remember(compassSettingsPreferences) {
        mutableStateOf(compassSettingsPreferences.load())
    }
    var distanceMeasurement by remember { mutableStateOf<PhoneMapDistanceMeasurement?>(null) }
    var gpxSettings by remember(gpxSettingsPreferences) { mutableStateOf(gpxSettingsPreferences.load()) }
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
    var mapSettings by remember(mapSettingsPreferences) {
        mutableStateOf(mapSettingsPreferences.load())
    }
    var poiSettings by remember(poiSettingsPreferences) {
        mutableStateOf(poiSettingsPreferences.load())
    }
    var mapCamera by remember { mutableStateOf(defaultPhoneMapCamera) }
    val storageMigration = remember(context) { PhoneOfflineStorageMigration(context.applicationContext) }
    var storageMigrationState by remember(storageMigration) {
        mutableStateOf(
            storageMigration.pending()?.let { journal ->
                PhoneOfflineStorageMigrationState(
                    phase = journal.phase,
                    source = journal.source,
                    target = journal.target,
                    message = "A previous data move can be resumed.",
                )
            } ?: PhoneOfflineStorageMigrationState(),
        )
    }
    var terrainDataVersion by remember { mutableStateOf(0L) }
    var hasElevationData by remember { mutableStateOf(false) }
    var liveMapElevation by remember { mutableStateOf<Double?>(null) }
    var liveMapPosition by remember { mutableStateOf<PhoneMapLiveMetricsPosition?>(null) }
    var showOfflineThemeSelector by remember { mutableStateOf(false) }
    var showOfflineBundleDownload by remember { mutableStateOf(false) }
    var offlineMapError by remember { mutableStateOf<PhoneOfflineMapError?>(null) }
    var mapLocationMessage by remember { mutableStateOf<Int?>(null) }
    var hasLocationPermission by remember(context) { mutableStateOf(context.hasPhoneMapLocationPermission()) }
    var shouldCenterOnInitialLocation by remember { mutableStateOf(true) }
    val completedBundle =
        (bundleUiState.download as? PhoneOfflineBundleDownloadState.Completed)?.bundle
    var hasFittedGpxOverlay by remember { mutableStateOf(false) }
    var selectedPoi by remember { mutableStateOf<PhoneMapPoi?>(null) }
    var poiCreationActive by remember { mutableStateOf(false) }
    var poiCreationPoint by remember { mutableStateOf<PhoneMapCoordinate?>(null) }
    var poiNameInput by remember { mutableStateOf("") }
    var poiCreationBusy by remember { mutableStateOf(false) }
    var poiCreationError by remember { mutableStateOf(false) }
    var poiCreationMessage by remember { mutableStateOf<String?>(null) }
    var contentRenameTarget by remember { mutableStateOf<PhoneMapManagedContent?>(null) }
    var contentDeleteTarget by remember { mutableStateOf<PhoneMapManagedContent?>(null) }
    var contentNameInput by remember { mutableStateOf("") }
    var contentActionError by remember { mutableStateOf<String?>(null) }
    var contentActionBusy by remember { mutableStateOf(false) }
    var selectedGpxAnalysis by remember { mutableStateOf<PhoneMapGpxItem?>(null) }
    var routeAnalysis by remember { mutableStateOf<PhoneMapRouteAnalysis?>(null) }
    var routeAnalysisSelectingPointB by remember { mutableStateOf(false) }
    val pointSelectionMarkers =
        phoneMapPointSelectionMarkers(poiCreationPoint, routeToolsState) +
            phoneMapDistanceMeasurementMarkers(distanceMeasurement)
    val routePointSelectionPhase = routeToolsState.pointSelectionPhase()
    val gpxOverlays =
        remember(gpxUiState.items, mapUiState.contentVisibility.gpxTracks) {
            gpxUiState.items.enabledOverlays(mapUiState.contentVisibility.gpxTracks)
        }
    val switchToOfflineMap: (PhoneOfflineMap) -> Unit = { selectedMap ->
        mapSourcePreference = mapSourcePreferences.saveOffline(selectedMap)
        mapRuntime.map?.cameraSnapshotOrNull()?.let { mapCamera = it }
        liveMapPosition = null
        mapRuntime = mapRuntime.invalidate()
        pendingOfflineMap = selectedMap
        offlineMapError = null
        mapLocationMessage = null
    }
    val selectOnlineMap: (PhoneOnlineMapSource) -> Unit = { requestedSource ->
        val onlineSource =
            requestedSource.takeIf(PhoneMapRendererCatalog::isComparisonOnlineSourceAvailable)
                ?: PhoneOnlineMapSource.OPEN_TOPO
        mapSourcePreference = mapSourcePreferences.saveOnline(onlineSource)
        liveMapPosition = null
        pendingOfflineMap = null
        mapUiState =
            mapUiState
                .copy(
                    source = PhoneMapSource.Online,
                    comparison =
                        mapUiState.comparison.copy(
                            layer =
                                mapUiState.comparison.layer.takeUnless { layer ->
                                    layer == PhoneMapComparisonLayer.Online(onlineSource)
                                },
                        ),
                ).clearUnavailableComparison(offlineMaps)
        offlineMapError = null
        mapLocationMessage = null
    }
    val switchToOnlineMap = { selectOnlineMap(selectedOnlineSource) }
    phoneMapCompassLifecycle(compassSensorSource)
    phoneMapLocationLifecycle(
        source = mapLocationSource,
        hasLocationPermission = hasLocationPermission,
        onPermissionChanged = { hasLocationPermission = it },
    )
    LaunchedEffect(pendingOfflineMap) {
        val selectedMap = pendingOfflineMap ?: return@LaunchedEffect
        withFrameNanos { }
        if (pendingOfflineMap != selectedMap) return@LaunchedEffect
        mapUiState =
            mapUiState
                .copy(source = PhoneMapSource.Offline(selectedMap))
                .clearUnavailableComparison(offlineMaps)
        pendingOfflineMap = null
    }
    LaunchedEffect(compassSettings) {
        compassSensorSource.configure(compassSettings)
    }
    LaunchedEffect(mapSettings.northReferenceMode, mapLocation) {
        compassSensorSource.setNorthReferenceMode(mapSettings.northReferenceMode)
        compassSensorSource.updateLocation(mapLocation)
    }
    LaunchedEffect(elevationStore) {
        hasElevationData = withContext(Dispatchers.IO) { elevationStore.hasData() }
    }
    val currentLiveMapPosition by rememberUpdatedState(liveMapPosition)
    LaunchedEffect(
        mapSettings.liveElevationEnabled,
        mapUiState.mapMode.isDetachedFromLocation,
        mapLocation?.fixElapsedRealtimeMillis,
        terrainDataVersion,
    ) {
        if (!mapSettings.liveElevationEnabled || !mapUiState.mapMode.isDetachedFromLocation) {
            liveMapElevation = null
            return@LaunchedEffect
        }

        var lastElevationSampleTarget: PhoneMapCoordinate? = null
        while (isActive) {
            val target = currentLiveMapPosition?.target
            if (target == null) {
                delay(PHONE_MAP_LIVE_METRICS_REFRESH_MS)
                continue
            }
            val previousTarget = lastElevationSampleTarget
            val movedMeters =
                if (previousTarget == null) {
                    Double.POSITIVE_INFINITY
                } else {
                    phoneMapDistanceMeters(previousTarget, target)
                }
            val shouldResampleElevation =
                previousTarget == null ||
                    movedMeters >= PHONE_MAP_LIVE_ELEVATION_RESAMPLE_DISTANCE_METERS
            if (shouldResampleElevation) {
                val sampledElevation =
                    withContext(Dispatchers.IO) {
                        elevationRepository.elevationAt(target.latitude, target.longitude)
                    }
                liveMapElevation =
                    sampledElevation
                        ?: mapLocation
                            ?.takeIf { location ->
                                phoneMapDistanceMeters(
                                    PhoneMapCoordinate(location.latitude, location.longitude),
                                    target,
                                ) <= PHONE_MAP_LIVE_ELEVATION_GPS_FALLBACK_METERS
                            }?.altitudeMeters
                lastElevationSampleTarget = target
            }
            delay(PHONE_MAP_LIVE_METRICS_REFRESH_MS)
        }
    }
    val compassPresentation =
        remember(mapUiState.mapMode, compassState.headingDegrees, compassState.isRenderable) {
            phoneMapCompassPresentation(
                mapMode = mapUiState.mapMode,
                headingDegrees = compassState.headingDegrees.takeIf { compassState.isRenderable },
            )
        }
    val locationState =
        MapLocationState(
            hasPermission = hasLocationPermission,
            location = mapLocation,
        )
    val gpxOverlayState =
        GpxOverlayState(
            overlays = gpxOverlays,
            segments = gpxOverlays.flatMap(PhoneMapGpxOverlay::segments),
            isVisible = mapUiState.contentVisibility.gpxTracks,
            settings = gpxSettings,
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
                        switchToOfflineMap(importedMap)
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
    val selectElevationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val imported =
                    withContext(Dispatchers.IO) {
                        elevationStore.import(context.contentResolver, uris)
                    }
                if (imported > 0) {
                    hasElevationData = true
                    terrainDataVersion += 1L
                    elevationRepository.invalidate()
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
        }

    LaunchedEffect(hasLocationPermission, mapLocation) {
        if (!shouldCenterOnInitialLocation || !hasLocationPermission || mapLocation == null) {
            return@LaunchedEffect
        }
        shouldCenterOnInitialLocation = false
        mapUiState = mapUiState.copy(mapMode = mapUiState.mapMode.recenterOnLocation())
    }

    LaunchedEffect(offlineMapStore, mapFolderSource) {
        val syncResult = withContext(Dispatchers.IO) { mapFolderSource.syncSelectedFolder() }
        val discoveredMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
        offlineMaps = discoveredMaps
        hasSelectedMapFolder = mapFolderSource.hasSelectedFolder()
        offlineMapError = syncResult.error
        if (mapSourcePreference.mode == PhoneMapSourcePreferenceMode.OFFLINE) {
            discoveredMaps
                .firstOrNull { map -> map.displayName == mapSourcePreference.offlineMapName }
                ?.let(switchToOfflineMap)
        }
        val selectedOfflineMap = (mapUiState.source as? PhoneMapSource.Offline)?.map
        if (selectedOfflineMap != null && selectedOfflineMap !in offlineMaps) {
            switchToOnlineMap()
            offlineMapError = PhoneOfflineMapError.MISSING
        }
        mapUiState = mapUiState.clearUnavailableComparison(offlineMaps)
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
    LaunchedEffect(
        selectedPoi?.id,
        poiSettings.popupAutoCloseEnabled,
        poiSettings.popupTimeoutSeconds,
    ) {
        if (selectedPoi == null || !poiSettings.popupAutoCloseEnabled) return@LaunchedEffect
        delay(poiSettings.popupTimeoutSeconds * 1_000L)
        selectedPoi = null
    }
    LaunchedEffect(completedBundle) {
        if (completedBundle != null) {
            offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
            hasElevationData = withContext(Dispatchers.IO) { elevationStore.hasData() }
            terrainDataVersion += 1L
            elevationRepository.invalidate()
            onPoiDataChanged()
        }
    }
    LaunchedEffect(gpxSources, gpxFolderScan.files, initiallyEnabledGpxId) {
        gpxViewModel.synchronize(gpxSources, gpxFolderScan.files, initiallyEnabledGpxId)
    }
    LaunchedEffect(routeToolsState.savedRouteId) {
        if (routeToolsState.savedRouteId != null) onRouteSaved()
    }
    LaunchedEffect(mapSettings.distanceMeasurementEnabled) {
        if (!mapSettings.distanceMeasurementEnabled) distanceMeasurement = null
    }
    LaunchedEffect(mapUiState.mapMode, mapSettings.autoRecenterEnabled, mapSettings.autoRecenterDelaySeconds) {
        if (!mapSettings.autoRecenterEnabled || !mapUiState.mapMode.isDetachedFromLocation) return@LaunchedEffect
        delay(mapSettings.autoRecenterDelaySeconds * 1_000L)
        if (mapUiState.mapMode.isDetachedFromLocation && mapLocation != null) {
            mapUiState = mapUiState.copy(mapMode = mapUiState.mapMode.recenterOnLocation())
            mapLocationMessage = null
        }
    }

    val onMapModePressed = {
        if (mapUiState.mapMode.isDetachedFromLocation) {
            if (!hasLocationPermission) {
                locationPermissionLauncher.launch(phoneMapLocationPermissions)
            } else if (mapLocation == null) {
                mapLocationMessage = R.string.map_location_waiting
            } else {
                mapUiState = mapUiState.copy(mapMode = mapUiState.mapMode.recenterOnLocation())
                mapLocationMessage = null
            }
        } else {
            mapUiState = mapUiState.toggleMapOrientation()
        }
    }

    val onStorageChangeRequested: (PhoneOfflineStorageLocation) -> Unit = onStorageChangeRequested@{ target ->
        val current = offlineStorage.location()
        val pendingMigration = storageMigration.pending()
        val migrationInProgress = storageMigrationState.phase.isPhoneStorageMigrationActive()
        if (migrationInProgress || storageChangeIsNoOp(target, current, offlineStorage, pendingMigration)) {
            return@onStorageChangeRequested
        }
        val selectedOfflineMapName = (mapUiState.source as? PhoneMapSource.Offline)?.map?.displayName
        coroutineScope.launch {
            storageMigrationState =
                PhoneOfflineStorageMigrationState(
                    phase = PhoneOfflineStorageMigrationPhase.COPYING,
                    source = current,
                    target = target,
                    message = null,
                )
            bundleViewModel.cancel()
            fileTransferViewModel.cancelTransfer()
            fileTransferViewModel.cancelPoiImport()
            fileTransferViewModel.cancelRoutingDownload()
            if (selectedOfflineMapName != null) {
                mapUiState = mapUiState.copy(source = PhoneMapSource.Online)
                mapRuntime = mapRuntime.invalidate()
            }
            val operationsStopped =
                withTimeoutOrNull(PHONE_STORAGE_OPERATION_STOP_TIMEOUT_MS) {
                    while (phoneStorageOperationsActive(bundleViewModel, fileTransferViewModel)) {
                        delay(PHONE_STORAGE_OPERATION_POLL_DELAY_MS)
                    }
                } != null
            if (!operationsStopped) {
                storageMigrationState =
                    PhoneOfflineStorageMigrationState(
                        phase = PhoneOfflineStorageMigrationPhase.FAILED,
                        source = current,
                        target = target,
                        message = "Active data operations did not stop in time.",
                    )
                return@launch
            }
            delay(PHONE_STORAGE_RENDER_STOP_DELAY_MS)
            val result =
                storageMigration.move(target) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        storageMigrationState =
                            PhoneOfflineStorageMigrationState(
                                phase = progress.phase,
                                source = progress.source,
                                target = progress.target,
                                copiedFiles = progress.copiedFiles,
                                totalFiles = progress.totalFiles,
                                requiredSpaceBytes = progress.requiredSpaceBytes,
                                availableSpaceBytes = progress.availableSpaceBytes,
                            )
                    }
                }
            when (result) {
                is PhoneOfflineStorageMigrationResult.Success -> {
                    storageMigrationState =
                        PhoneOfflineStorageMigrationState(
                            phase = PhoneOfflineStorageMigrationPhase.COMPLETE,
                            source = result.source,
                            target = result.target,
                            copiedFiles = result.movedFiles,
                            totalFiles = result.movedFiles,
                        )
                    bundleViewModel.refreshInstalledBundles()
                    offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                    hasElevationData = withContext(Dispatchers.IO) { elevationStore.hasData() }
                    terrainDataVersion += 1L
                    elevationRepository.invalidate()
                    onPoiDataChanged()
                    onRouteSaved()
                    selectedOfflineMapName
                        ?.let { name -> offlineMaps.firstOrNull { map -> map.displayName == name } }
                        ?.let { migratedMap ->
                            switchToOfflineMap(migratedMap)
                        }
                }

                is PhoneOfflineStorageMigrationResult.Failure -> {
                    storageMigrationState =
                        PhoneOfflineStorageMigrationState(
                            phase = PhoneOfflineStorageMigrationPhase.FAILED,
                            source = current,
                            target = target,
                            message = result.message,
                        )
                }
            }
        }
    }

    val openContentRename: (PhoneMapManagedContent) -> Unit = { target ->
        contentRenameTarget = target
        contentNameInput = target.renameValue()
        contentActionError = null
    }
    val openContentDelete: (PhoneMapManagedContent) -> Unit = { target ->
        contentDeleteTarget = target
        contentActionError = null
    }
    val renameContent: suspend (PhoneMapManagedContent, String) -> Unit = { target, newName ->
        when (target) {
            is PhoneMapManagedContent.OfflineMap -> {
                val renamed = withContext(Dispatchers.IO) { offlineMapStore.rename(target.map, newName) }
                bundleStore.replaceManagedFileName(
                    oldFileName = target.map.displayName,
                    newFileName = renamed.displayName,
                    isMap = true,
                )
                mapSourcePreference =
                    mapSourcePreferences.replaceOfflineMapName(target.map.displayName, renamed.displayName)
                offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                mapUiState =
                    mapUiState.copy(
                        comparison =
                            mapUiState.comparison.copy(
                                layer =
                                    when (mapUiState.comparison.layer) {
                                        is PhoneMapComparisonLayer.Offline -> {
                                            val comparison =
                                                mapUiState.comparison.layer as PhoneMapComparisonLayer.Offline
                                            if (comparison.map == target.map) {
                                                PhoneMapComparisonLayer.Offline(renamed)
                                            } else {
                                                comparison
                                            }
                                        }
                                        else -> mapUiState.comparison.layer
                                    },
                            ),
                    )
                if ((mapUiState.source as? PhoneMapSource.Offline)?.map == target.map) {
                    switchToOfflineMap(renamed)
                }
                bundleViewModel.refreshInstalledBundles()
            }

            is PhoneMapManagedContent.Gpx -> {
                val folderFile = gpxFolderScan.files.firstOrNull { file -> file.id == target.item.id }
                if (folderFile != null) {
                    withContext(Dispatchers.IO) { gpxFolderSource.rename(folderFile, newName) }
                    gpxFolderScan = withContext(Dispatchers.IO) { gpxFolderSource.scanSelectedFolder() }
                } else {
                    onRenameRoute(target.item.id, newName)
                }
            }

            is PhoneMapManagedContent.Poi -> {
                val renamed = onRenamePoiSource(target.source.fileName, newName)
                bundleStore.replaceManagedFileName(
                    oldFileName = target.source.fileName,
                    newFileName = renamed,
                    isMap = false,
                )
                bundleViewModel.refreshInstalledBundles()
            }
        }
    }
    val deleteContent: suspend (PhoneMapManagedContent) -> Unit = { target ->
        when (target) {
            is PhoneMapManagedContent.OfflineMap -> {
                if ((mapUiState.source as? PhoneMapSource.Offline)?.map == target.map) {
                    switchToOnlineMap()
                }
                withContext(Dispatchers.IO) { offlineMapStore.delete(target.map) }
                mapSourcePreference = mapSourcePreferences.forgetOfflineMapName(target.map.displayName)
                offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                mapUiState = mapUiState.clearUnavailableComparison(offlineMaps)
                bundleViewModel.refreshInstalledBundles()
            }

            is PhoneMapManagedContent.Gpx -> {
                val folderFile = gpxFolderScan.files.firstOrNull { file -> file.id == target.item.id }
                if (folderFile != null) {
                    withContext(Dispatchers.IO) { gpxFolderSource.delete(folderFile) }
                    gpxFolderScan = withContext(Dispatchers.IO) { gpxFolderSource.scanSelectedFolder() }
                } else {
                    onDeleteRoute(target.item.id)
                }
            }

            is PhoneMapManagedContent.Poi -> {
                onDeletePoiSource(target.source.fileName)
                bundleViewModel.refreshInstalledBundles()
            }
        }
    }

    val toolsState =
        MapToolsPanelState(
            maps =
                MapToolsMapsState(
                    source = mapUiState.source,
                    onlineSources = PhoneMapRendererCatalog.comparisonOnlineSources(),
                    selectedOnlineSource = selectedOnlineSource,
                    offlineMaps = offlineMaps,
                    offlineMapAvailability =
                        offlineMaps.associate { map ->
                            map.displayName to
                                phoneOfflineMapAvailability(
                                    map = map,
                                    fallbackHasElevationData = hasElevationData,
                                    bundles = bundleUiState.installedBundles,
                                    healthByAreaId = bundleUiState.statusByAreaId,
                                )
                        },
                    preferredOfflineMapName = mapSourcePreference.offlineMapName,
                    hasSelectedFolder = hasSelectedMapFolder,
                    hasElevationData = hasElevationData,
                    themeConfig = offlineThemeConfig,
                    settings = mapSettings,
                    compassSettings = compassSettings,
                    compassState = compassState,
                ),
            gpx =
                MapToolsGpxState(
                    items = gpxUiState.items,
                    isLoading = gpxUiState.isLoading,
                    globalVisible = mapUiState.contentVisibility.gpxTracks,
                    settings = gpxSettings,
                    routeLibrarySourceCount = gpxSources.size,
                    hasSelectedFolder = hasSelectedGpxFolder,
                    selectedFolderName = gpxFolderScan.folderName,
                    folderError = gpxFolderScan.error,
                ),
            poi =
                MapToolsPoiState(
                    sources = poiSources,
                    globalVisible = mapUiState.contentVisibility.pois,
                    settings = poiSettings,
                ),
            layer =
                MapToolsLayerState(
                    base = mapUiState.source,
                    baseOnlineSource = selectedOnlineSource,
                    offlineMaps = offlineMaps,
                    onlineSources = PhoneMapRendererCatalog.comparisonOnlineSources(),
                    comparison = mapUiState.comparison,
                    hasElevationData = hasElevationData,
                    mapSettings = mapSettings,
                ),
            general =
                MapToolsGeneralState(
                    mapMode = mapUiState.mapMode,
                    settings = generalSettings,
                    sensorCapabilities = sensorCapabilities,
                    storageLocation = offlineStorage.location(),
                    externalStorageAvailable = offlineStorage.isExternalAvailable(),
                    storageNeedsCanonicalMigration = offlineStorage.needsCanonicalMigration(),
                    storageMigration = storageMigrationState,
                ),
        )
    val mapActions =
        MapToolsMapsActions(
            onSelectOnline = selectOnlineMap,
            onSelectOffline = { selectedMap ->
                coroutineScope.launch {
                    val error = withContext(Dispatchers.IO) { offlineMapStore.validate(selectedMap) }
                    if (error == null) {
                        switchToOfflineMap(selectedMap)
                    } else {
                        offlineMapError = error
                    }
                }
            },
            onRenameOfflineMap = { map -> openContentRename(PhoneMapManagedContent.OfflineMap(map)) },
            onDeleteOfflineMap = { map -> openContentDelete(PhoneMapManagedContent.OfflineMap(map)) },
            onImportMap = { selectLocalMapLauncher.launch(arrayOf("application/octet-stream")) },
            onImportElevation = {
                selectElevationLauncher.launch(
                    arrayOf("application/octet-stream", "application/gzip", "application/zip"),
                )
            },
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
            onOpenSettingsSection = { section ->
                mapUiState =
                    mapUiState.copy(
                        toolPanel = mapUiState.toolPanel.showFeatureSettingsSection(section),
                    )
            },
            onSettingsChanged = { settings ->
                val previousDemSource = mapSettings.demSource
                mapSettings = mapSettingsPreferences.save(settings)
                if (previousDemSource != mapSettings.demSource) {
                    terrainDataVersion += 1L
                    elevationRepository.invalidate()
                    hasElevationData = elevationStore.hasData()
                }
            },
            onCompassSettingsChanged = { settings ->
                compassSettings = compassSettingsPreferences.save(settings)
            },
            onCalibrateCompass = compassSensorSource::recalibrate,
        )

    val routePointSelectionCenter =
        liveMapPosition?.target
            ?: PhoneMapCoordinate(
                latitude = mapCamera.latitude,
                longitude = mapCamera.longitude,
            )
    val onSelectRoutePointAtCenter = {
        routeToolsViewModel.selectMapPoint(
            GeoPoint(
                latitude = routePointSelectionCenter.latitude,
                longitude = routePointSelectionCenter.longitude,
            ),
        )
    }
    val onOpenRouteTools = {
        routeAnalysis = null
        routeAnalysisSelectingPointB = false
        poiCreationActive = false
        poiCreationPoint = null
        mapUiState = mapUiState.closeTool().copy(toolLauncherExpanded = false)
        routeToolsViewModel.open(gpxUiState.items)
    }
    val onAddPoi = {
        routeAnalysis = null
        routeAnalysisSelectingPointB = false
        routeToolsViewModel.cancel()
        selectedPoi = null
        poiCreationActive = true
        poiCreationPoint = null
        poiNameInput = ""
        poiCreationError = false
        poiCreationMessage = null
        mapUiState = mapUiState.closeTool().copy(toolLauncherExpanded = false)
    }
    val onMapTap = { point: PhoneMapCoordinate ->
        if (poiCreationActive) {
            poiCreationActive = false
            poiCreationPoint = point
            poiNameInput = ""
            poiCreationError = false
        } else if (routeToolsState.isOpen) {
            routeToolsViewModel.selectMapPoint(
                GeoPoint(latitude = point.latitude, longitude = point.longitude),
            )
        }
    }
    val onMapLongPress: (PhoneMapCoordinate) -> Unit = fun(point) {
        val canAnalyzeRoute =
            isPhoneMapRouteAnalysisAvailable(
                inspectionEnabled = gpxSettings.inspectionEnabled,
                gpxTracksVisible = mapUiState.contentVisibility.gpxTracks,
                routeToolsOpen = routeToolsState.isOpen,
                poiCreationActive = poiCreationActive,
                poiCreationPointSet = poiCreationPoint != null,
            )
        if (canAnalyzeRoute) {
            val selectingPointB = routeAnalysisSelectingPointB
            val allowedTrackId = if (selectingPointB) routeAnalysis?.trackId else null
            val enabledItems = gpxUiState.items.filter { item -> item.enabled }
            coroutineScope.launch(Dispatchers.Default) {
                val hit =
                    findClosestPhoneMapRoutePosition(
                        press = point,
                        items = enabledItems,
                        allowedTrackId = allowedTrackId,
                    )?.takeIf { value ->
                        value.distanceToTrackMeters <= PHONE_ROUTE_ANALYSIS_PRESS_THRESHOLD_METERS
                    }
                if (hit != null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (
                            !isPhoneMapRouteAnalysisAvailable(
                                inspectionEnabled = gpxSettings.inspectionEnabled,
                                gpxTracksVisible = mapUiState.contentVisibility.gpxTracks,
                                routeToolsOpen = routeToolsState.isOpen,
                                poiCreationActive = poiCreationActive,
                                poiCreationPointSet = poiCreationPoint != null,
                            )
                        ) {
                            return@withContext
                        }
                        if (selectingPointB != routeAnalysisSelectingPointB) return@withContext
                        if (selectingPointB) {
                            val current = routeAnalysis
                            val currentItem =
                                current?.let { value ->
                                    gpxUiState.items.firstOrNull { item -> item.id == value.trackId }
                                }
                            if (current != null && currentItem != null) {
                                routeAnalysis =
                                    buildPhoneMapRouteAnalysis(
                                        pointA =
                                            PhoneMapRouteAnalysisHit(
                                                item = currentItem,
                                                position = current.pointAPosition,
                                                coordinate = current.pointA,
                                                distanceToTrackMeters = 0.0,
                                            ),
                                        pointB = hit,
                                        settings = gpxSettings,
                                        generalSettings = generalSettings,
                                    )
                                routeAnalysisSelectingPointB = false
                            }
                        } else {
                            selectedPoi = null
                            routeAnalysis =
                                buildPhoneMapRouteAnalysis(
                                    pointA = hit,
                                    settings = gpxSettings,
                                    generalSettings = generalSettings,
                                )
                            routeAnalysisSelectingPointB = false
                        }
                    }
                }
            }
        }
    }
    val savePoi = {
        val point = poiCreationPoint
        if (point != null && !poiCreationBusy) {
            coroutineScope.launch {
                poiCreationBusy = true
                val result =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            userPoiStore.create(
                                latitude = point.latitude,
                                longitude = point.longitude,
                                name = poiNameInput,
                            )
                        }
                    }
                poiCreationBusy = false
                result
                    .onSuccess { savedName ->
                        poiCreationPoint = null
                        poiNameInput = ""
                        poiCreationError = false
                        poiCreationMessage = savedName
                        onPoiDataChanged()
                    }.onFailure {
                        poiCreationError = true
                    }
            }
        }
    }
    val onToolPanelBack = { mapUiState = mapUiState.onMapBack() }
    val onMapBack = {
        when {
            selectedGpxAnalysis != null -> selectedGpxAnalysis = null
            routeAnalysis != null -> {
                routeAnalysis = null
                routeAnalysisSelectingPointB = false
            }
            poiCreationPoint != null -> {
                poiCreationPoint = null
                poiCreationError = false
            }
            poiCreationActive -> poiCreationActive = false
            else -> onToolPanelBack()
        }
    }

    BackHandler(
        enabled =
            mapUiState.toolPanel.mode != MapToolPanelMode.CLOSED ||
                mapUiState.toolLauncherExpanded ||
                poiCreationActive ||
                poiCreationPoint != null ||
                selectedGpxAnalysis != null ||
                routeAnalysis != null,
    ) {
        onMapBack()
    }
    val comparisonOverlayAlpha = mapUiState.comparison.overlayAlpha()
    val selectedOfflineComparison = mapUiState.comparison.layer as? PhoneMapComparisonLayer.Offline
    val offlineComparison = selectedOfflineComparison?.takeIf { comparisonOverlayAlpha > 0f }
    val onlineComparison = mapUiState.comparison.layer as? PhoneMapComparisonLayer.Online
    val onlineComparisonActive = onlineComparison != null && comparisonOverlayAlpha > 0f

    MapToolScaffold(
        state = mapUiState.toolPanel,
        launcherExpanded = mapUiState.toolLauncherExpanded,
        actions =
            MapToolScaffoldActions(
                onToolSelected = { tool -> mapUiState = mapUiState.selectTool(tool) },
                onToggleLauncher = { mapUiState = mapUiState.toggleToolLauncher() },
                onAddPoi = onAddPoi,
                onOpenGpxTools = onOpenRouteTools,
                onExpand = { mapUiState = mapUiState.expandTool() },
                onCollapse = { mapUiState = mapUiState.collapseTool() },
                onHeaderSwipe = { swipe ->
                    mapUiState = mapUiState.copy(toolPanel = mapUiState.toolPanel.onHeaderSwipe(swipe))
                },
                onBack = onToolPanelBack,
                onClose = { mapUiState = mapUiState.closeTool() },
                onFeatureSettings = { mapUiState = mapUiState.showFeatureSettings() },
            ),
        mapContent = {
            Box(modifier = Modifier.fillMaxSize()) {
                if (storageMigrationState.phase.isPhoneStorageMigrationActive() || pendingOfflineMap != null) {
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                    when (val source = mapUiState.source) {
                        PhoneMapSource.Online -> {
                            mapViewLifecycle(
                                mapView = mapRuntime.mapView,
                                onMapViewDestroyed = { destroyedMapView ->
                                    mapRuntime = mapRuntime.invalidate(destroyedMapView)
                                },
                            )
                            synchronizeOnlineMapPresentation(
                                runtime = mapRuntime,
                                locationState = locationState,
                                mapMode = mapUiState.mapMode,
                                mapSettings = mapSettings,
                                userGestureActive = onlineGestureState.isActive,
                                compassSource = compassSensorSource,
                                compassPresentation = compassPresentation,
                            )
                            synchronizeGpxOverlay(
                                runtime = mapRuntime,
                                overlayState = gpxOverlayState,
                                hasFittedGpxOverlay = hasFittedGpxOverlay,
                                onOverlayFitted = { hasFittedGpxOverlay = true },
                            )
                            synchronizeRouteAnalysisOverlay(
                                runtime = mapRuntime,
                                analysis = routeAnalysis,
                            )
                            synchronizePoiOverlay(
                                runtime = mapRuntime,
                                pois = pois,
                                isVisible = mapUiState.contentVisibility.pois,
                                settings = poiSettings,
                            )
                            synchronizePointSelectionOverlay(
                                runtime = mapRuntime,
                                markers = pointSelectionMarkers,
                            )
                            synchronizeDistanceMeasurementOverlay(
                                runtime = mapRuntime,
                                measurement = distanceMeasurement,
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
                            observeOnlineLiveMapPosition(
                                runtime = mapRuntime,
                                enabled =
                                    mapSettings.liveElevationEnabled ||
                                        mapSettings.liveDistanceEnabled ||
                                        routePointSelectionPhase != null,
                                location = mapLocation,
                                markerAnchor = mapSettings.markerAnchor,
                                onPositionChanged = { liveMapPosition = it },
                            )
                            observeOnlineUserPan(
                                runtime = mapRuntime,
                                onGestureActiveChanged = { active ->
                                    onlineGestureState =
                                        onlineGestureState.withActive(PhoneOnlineGestureType.PAN, active)
                                },
                                onUserPan = { bearing ->
                                    mapUiState =
                                        mapUiState.copy(
                                            mapMode = mapUiState.mapMode.detachFromLocation(bearing),
                                        )
                                },
                            )
                            observeOnlineUserRotation(
                                runtime = mapRuntime,
                                onGestureActiveChanged = { active ->
                                    onlineGestureState =
                                        onlineGestureState.withActive(PhoneOnlineGestureType.ROTATE, active)
                                },
                                onUserRotation = { bearing ->
                                    mapUiState =
                                        mapUiState.copy(
                                            mapMode = mapUiState.mapMode.detachAfterManualRotation(bearing),
                                        )
                                },
                            )
                            synchronizeOnlineMapControls(
                                runtime = mapRuntime,
                                command = mapUiState.cameraCommand,
                                mapSettings = mapSettings,
                                onCameraCommandHandled = { commandId ->
                                    mapUiState = mapUiState.consumeCommand(commandId)
                                },
                            )
                            if (offlineComparison != null || onlineComparisonActive) {
                                synchronizeComparisonOnlineCamera(
                                    runtime = mapRuntime,
                                    camera = mapCamera,
                                )
                            }
                            mapSurface(
                                initialCamera = mapCamera,
                                onlineProvider = selectedOnlineProvider,
                                onMapTap = onMapTap,
                                onMapLongPress = onMapLongPress,
                                onTwoFingerTap = { first, second ->
                                    if (mapSettings.distanceMeasurementEnabled) {
                                        distanceMeasurement = PhoneMapDistanceMeasurement(first, second)
                                    }
                                },
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
                            onlineComparison?.takeIf { onlineComparisonActive }?.let { comparison ->
                                mapViewLifecycle(
                                    mapView = comparisonMapRuntime.mapView,
                                    onMapViewDestroyed = { destroyedMapView ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.invalidate(destroyedMapView)
                                    },
                                )
                                synchronizeComparisonOnlineCamera(
                                    runtime = comparisonMapRuntime,
                                    camera = mapCamera,
                                )
                                observeOnlineCamera(
                                    runtime = comparisonMapRuntime,
                                    onCameraChanged = { mapCamera = it },
                                )
                                synchronizePointSelectionOverlay(
                                    runtime = comparisonMapRuntime,
                                    markers = pointSelectionMarkers,
                                )
                                synchronizeDistanceMeasurementOverlay(
                                    runtime = comparisonMapRuntime,
                                    measurement = distanceMeasurement,
                                )
                                observeOnlineUserPan(
                                    runtime = comparisonMapRuntime,
                                    onGestureActiveChanged = { active ->
                                        onlineGestureState =
                                            onlineGestureState.withActive(PhoneOnlineGestureType.PAN, active)
                                    },
                                    onUserPan = { bearing ->
                                        mapUiState =
                                            mapUiState.copy(
                                                mapMode = mapUiState.mapMode.detachFromLocation(bearing),
                                            )
                                    },
                                )
                                observeOnlineUserRotation(
                                    runtime = comparisonMapRuntime,
                                    onGestureActiveChanged = { active ->
                                        onlineGestureState =
                                            onlineGestureState.withActive(PhoneOnlineGestureType.ROTATE, active)
                                    },
                                    onUserRotation = { bearing ->
                                        mapUiState =
                                            mapUiState.copy(
                                                mapMode =
                                                    mapUiState.mapMode.detachAfterManualRotation(bearing),
                                            )
                                    },
                                )
                                mapSurface(
                                    initialCamera = mapCamera,
                                    onlineProvider =
                                        requireNotNull(
                                            PhoneMapRendererCatalog.providerForComparisonOnlineSource(
                                                comparison.source,
                                            ),
                                        ),
                                    onMapTap = onMapTap,
                                    onMapLongPress = onMapLongPress,
                                    onTwoFingerTap = { first, second ->
                                        if (mapSettings.distanceMeasurementEnabled) {
                                            distanceMeasurement = PhoneMapDistanceMeasurement(first, second)
                                        }
                                    },
                                    onMapViewCreated = { createdMapView ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.beginMapView(createdMapView)
                                        comparisonMapRuntime.generation.renderer
                                    },
                                    onMapReady = { callbackGeneration, callbackMapView, callbackMap ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.acceptMapReady(
                                                callbackGeneration = callbackGeneration,
                                                callbackMapView = callbackMapView,
                                                callbackMap = callbackMap,
                                            )
                                    },
                                    onStyleReady = { callbackGeneration, callbackMapView, callbackMap ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.acceptStyleReady(
                                                callbackGeneration = callbackGeneration,
                                                callbackMapView = callbackMapView,
                                                callbackMap = callbackMap,
                                            )
                                    },
                                    modifier = Modifier.fillMaxSize().alpha(comparisonOverlayAlpha),
                                )
                            }
                            offlineComparison?.let { comparison ->
                                offlineMapSurface(
                                    state =
                                        PhoneOfflineMapSurfaceState(
                                            map = comparison.map,
                                            themeConfig = offlineThemeConfig,
                                            initialCamera = mapCamera,
                                            cameraOverride = mapCamera,
                                            mapSettings = mapSettings,
                                            terrainDataVersion = 0L,
                                            hasTerrainData = false,
                                            gpxOverlays = emptyList(),
                                            pointSelectionMarkers = pointSelectionMarkers,
                                            distanceMeasurement = distanceMeasurement,
                                            pois = emptyList(),
                                            mapMode =
                                                PhoneMapMode(
                                                    follow = PhoneMapFollowMode.FREE,
                                                    manualBearingDegrees = mapCamera.bearingDegrees,
                                                ),
                                            compassPresentation =
                                                phoneMapCompassPresentation(
                                                    mapMode =
                                                        PhoneMapMode(
                                                            follow = PhoneMapFollowMode.FREE,
                                                            manualBearingDegrees = mapCamera.bearingDegrees,
                                                        ),
                                                    headingDegrees = null,
                                                ),
                                            cameraCommand = null,
                                        ),
                                    callbacks =
                                        PhoneOfflineMapsforgeCallbacks(
                                            onCameraChanged = { mapCamera = it },
                                            onLiveMapPositionChanged = {},
                                            onViewportChanged = {},
                                            onPoiSelected = {},
                                            onMapTap = onMapTap,
                                            onMapLongPress = onMapLongPress,
                                            onTwoFingerTap = { first, second ->
                                                if (mapSettings.distanceMeasurementEnabled) {
                                                    distanceMeasurement = PhoneMapDistanceMeasurement(first, second)
                                                }
                                            },
                                            onUserPan = { bearing ->
                                                mapUiState =
                                                    mapUiState.copy(
                                                        mapMode =
                                                            mapUiState.mapMode.detachFromLocation(bearing),
                                                    )
                                            },
                                            onUserRotation = { bearing ->
                                                mapUiState =
                                                    mapUiState.copy(
                                                        mapMode =
                                                            mapUiState.mapMode.detachAfterManualRotation(bearing),
                                                    )
                                            },
                                            onCameraCommandHandled = {},
                                            onMapError = { error -> offlineMapError = error },
                                            onLocationFollowUnavailable = {},
                                        ),
                                    modifier = Modifier.fillMaxSize().alpha(comparisonOverlayAlpha),
                                )
                            }
                        }

                        is PhoneMapSource.Offline -> {
                            offlineMapSurface(
                                state =
                                    PhoneOfflineMapSurfaceState(
                                        map = source.map,
                                        themeConfig =
                                            if (mapSettings.nightModeEnabled) {
                                                PhoneOfflineThemeCatalog.resolve(
                                                    PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID,
                                                    PhoneOfflineThemeCatalog.MAPSFORGE_DARK_STYLE_ID,
                                                )
                                            } else {
                                                offlineThemeConfig
                                            },
                                        initialCamera = mapCamera,
                                        mapSettings = mapSettings,
                                        terrainDataVersion = terrainDataVersion,
                                        hasTerrainData = hasElevationData,
                                        cameraOverride = mapCamera.takeIf { onlineComparisonActive },
                                        gpxOverlays =
                                            if (onlineComparisonActive) emptyList() else gpxOverlayState.overlays,
                                        gpxSettings = gpxOverlayState.settings,
                                        routeAnalysis = routeAnalysis.takeUnless { onlineComparisonActive },
                                        pointSelectionMarkers = pointSelectionMarkers,
                                        distanceMeasurement = distanceMeasurement,
                                        pois =
                                            if (onlineComparisonActive) {
                                                emptyList()
                                            } else {
                                                pois.takeIf { mapUiState.contentVisibility.pois }.orEmpty()
                                            },
                                        poiSettings = poiSettings,
                                        mapMode =
                                            if (onlineComparisonActive) {
                                                PhoneMapMode(
                                                    follow = PhoneMapFollowMode.FREE,
                                                    manualBearingDegrees = mapCamera.bearingDegrees,
                                                )
                                            } else {
                                                mapUiState.mapMode
                                            },
                                        compassPresentation =
                                            if (onlineComparisonActive) {
                                                phoneMapCompassPresentation(
                                                    mapMode =
                                                        PhoneMapMode(
                                                            follow = PhoneMapFollowMode.FREE,
                                                            manualBearingDegrees = mapCamera.bearingDegrees,
                                                        ),
                                                    headingDegrees = null,
                                                )
                                            } else {
                                                compassPresentation
                                            },
                                        location =
                                            locationState.location.takeIf {
                                                locationState.hasPermission && !onlineComparisonActive
                                            },
                                        hasLocationPermission =
                                            locationState.hasPermission && !onlineComparisonActive,
                                        cameraCommand = mapUiState.cameraCommand.takeUnless { onlineComparisonActive },
                                    ),
                                callbacks =
                                    PhoneOfflineMapsforgeCallbacks(
                                        onCameraChanged = { camera ->
                                            if (!onlineComparisonActive) mapCamera = camera
                                        },
                                        onLiveMapPositionChanged = { position ->
                                            if (!onlineComparisonActive) liveMapPosition = position
                                        },
                                        onViewportChanged = { viewport ->
                                            if (!onlineComparisonActive) onPoiViewportChanged(viewport)
                                        },
                                        onPoiSelected = { poi ->
                                            if (!onlineComparisonActive) selectedPoi = poi
                                        },
                                        onMapTap = { point -> if (!onlineComparisonActive) onMapTap(point) },
                                        onMapLongPress = { point ->
                                            if (!onlineComparisonActive) onMapLongPress(point)
                                        },
                                        onUserPan = { bearing ->
                                            if (!onlineComparisonActive) {
                                                mapUiState =
                                                    mapUiState.copy(
                                                        mapMode = mapUiState.mapMode.detachFromLocation(bearing),
                                                    )
                                            }
                                        },
                                        onUserRotation = { bearing ->
                                            if (!onlineComparisonActive) {
                                                mapUiState =
                                                    mapUiState.copy(
                                                        mapMode =
                                                            mapUiState.mapMode.detachAfterManualRotation(bearing),
                                                    )
                                            }
                                        },
                                        onCameraCommandHandled = { commandId ->
                                            if (!onlineComparisonActive) {
                                                mapUiState = mapUiState.consumeCommand(commandId)
                                            }
                                        },
                                        onTwoFingerTap = { first, second ->
                                            if (
                                                !onlineComparisonActive &&
                                                mapSettings.distanceMeasurementEnabled
                                            ) {
                                                distanceMeasurement = PhoneMapDistanceMeasurement(first, second)
                                            }
                                        },
                                        onMapError = { error ->
                                            offlineMapError = error
                                            pendingOfflineMap = null
                                            mapUiState =
                                                mapUiState
                                                    .copy(source = PhoneMapSource.Online)
                                                    .clearUnavailableComparison(offlineMaps)
                                        },
                                        onLocationFollowUnavailable = {
                                            mapUiState =
                                                mapUiState.copy(
                                                    mapMode = mapUiState.mapMode.detachFromLocation(),
                                                )
                                            mapLocationMessage = R.string.map_location_outside_offline_map
                                        },
                                    ),
                            )
                            if (onlineComparisonActive) {
                                mapViewLifecycle(
                                    mapView = comparisonMapRuntime.mapView,
                                    onMapViewDestroyed = { destroyedMapView ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.invalidate(destroyedMapView)
                                    },
                                )
                                synchronizeOnlineMapPresentation(
                                    runtime = comparisonMapRuntime,
                                    locationState = locationState,
                                    mapMode = mapUiState.mapMode,
                                    mapSettings =
                                        mapSettings.copy(
                                            northIndicatorMode = PhoneMapNorthIndicatorMode.NEVER,
                                        ),
                                    userGestureActive = onlineGestureState.isActive,
                                    compassSource = compassSensorSource,
                                    compassPresentation = compassPresentation,
                                )
                                synchronizeGpxOverlay(
                                    runtime = comparisonMapRuntime,
                                    overlayState = gpxOverlayState,
                                    hasFittedGpxOverlay = hasFittedGpxOverlay,
                                    onOverlayFitted = { hasFittedGpxOverlay = true },
                                )
                                synchronizeRouteAnalysisOverlay(
                                    runtime = comparisonMapRuntime,
                                    analysis = routeAnalysis,
                                )
                                synchronizePoiOverlay(
                                    runtime = comparisonMapRuntime,
                                    pois = pois,
                                    isVisible = mapUiState.contentVisibility.pois,
                                    settings = poiSettings,
                                )
                                synchronizePointSelectionOverlay(
                                    runtime = comparisonMapRuntime,
                                    markers = pointSelectionMarkers,
                                )
                                synchronizeDistanceMeasurementOverlay(
                                    runtime = comparisonMapRuntime,
                                    measurement = distanceMeasurement,
                                )
                                observePoiViewport(
                                    runtime = comparisonMapRuntime,
                                    isVisible = mapUiState.contentVisibility.pois,
                                    onViewportChanged = onPoiViewportChanged,
                                )
                                observePoiSelection(
                                    runtime = comparisonMapRuntime,
                                    pois = pois,
                                    isVisible = mapUiState.contentVisibility.pois,
                                    onPoiSelected = { selectedPoi = it },
                                )
                                observeOnlineCamera(
                                    runtime = comparisonMapRuntime,
                                    onCameraChanged = { mapCamera = it },
                                )
                                observeOnlineLiveMapPosition(
                                    runtime = comparisonMapRuntime,
                                    enabled =
                                        mapSettings.liveElevationEnabled ||
                                            mapSettings.liveDistanceEnabled ||
                                            routePointSelectionPhase != null,
                                    location = mapLocation,
                                    markerAnchor = mapSettings.markerAnchor,
                                    onPositionChanged = { liveMapPosition = it },
                                )
                                observeOnlineUserPan(
                                    runtime = comparisonMapRuntime,
                                    onGestureActiveChanged = { active ->
                                        onlineGestureState =
                                            onlineGestureState.withActive(PhoneOnlineGestureType.PAN, active)
                                    },
                                    onUserPan = { bearing ->
                                        mapUiState =
                                            mapUiState.copy(
                                                mapMode = mapUiState.mapMode.detachFromLocation(bearing),
                                            )
                                    },
                                )
                                observeOnlineUserRotation(
                                    runtime = comparisonMapRuntime,
                                    onGestureActiveChanged = { active ->
                                        onlineGestureState =
                                            onlineGestureState.withActive(PhoneOnlineGestureType.ROTATE, active)
                                    },
                                    onUserRotation = { bearing ->
                                        mapUiState =
                                            mapUiState.copy(
                                                mapMode =
                                                    mapUiState.mapMode.detachAfterManualRotation(bearing),
                                            )
                                    },
                                )
                                synchronizeOnlineMapControls(
                                    runtime = comparisonMapRuntime,
                                    command = mapUiState.cameraCommand,
                                    mapSettings = mapSettings,
                                    onCameraCommandHandled = { commandId ->
                                        mapUiState = mapUiState.consumeCommand(commandId)
                                    },
                                )
                                mapSurface(
                                    initialCamera = mapCamera,
                                    onlineProvider =
                                        requireNotNull(
                                            PhoneMapRendererCatalog.providerForComparisonOnlineSource(
                                                requireNotNull(onlineComparison).source,
                                            ),
                                        ),
                                    onMapTap = onMapTap,
                                    onMapLongPress = onMapLongPress,
                                    onTwoFingerTap = { first, second ->
                                        if (mapSettings.distanceMeasurementEnabled) {
                                            distanceMeasurement = PhoneMapDistanceMeasurement(first, second)
                                        }
                                    },
                                    onMapViewCreated = { createdMapView ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.beginMapView(createdMapView)
                                        comparisonMapRuntime.generation.renderer
                                    },
                                    onMapReady = { callbackGeneration, callbackMapView, callbackMap ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.acceptMapReady(
                                                callbackGeneration = callbackGeneration,
                                                callbackMapView = callbackMapView,
                                                callbackMap = callbackMap,
                                            )
                                    },
                                    onStyleReady = { callbackGeneration, callbackMapView, callbackMap ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.acceptStyleReady(
                                                callbackGeneration = callbackGeneration,
                                                callbackMapView = callbackMapView,
                                                callbackMap = callbackMap,
                                            )
                                    },
                                    modifier = Modifier.fillMaxSize().alpha(comparisonOverlayAlpha),
                                )
                            }
                        }
                    }
                }

                if (mapSettings.nightModeEnabled) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.32f)),
                    )
                }
                val northIndicatorVisible =
                    mapSettings.northIndicatorMode.isVisibleFor(
                        mapUiState.mapMode,
                        compassState.isRenderable,
                    )
                mapControls(
                    onBack = onBack,
                    onZoomIn = { mapUiState = mapUiState.requestZoom(1) },
                    onZoomOut = { mapUiState = mapUiState.requestZoom(-1) },
                    camera = mapCamera,
                    mapMode = mapUiState.mapMode,
                    mapSettings = mapSettings,
                    isMetric = generalSettings.isMetric,
                    onCycleMapMode = onMapModePressed,
                    northIndicatorVisible = northIndicatorVisible,
                    compassPresentation = compassPresentation,
                    showModeControl = routePointSelectionPhase == null,
                )

                if (routePointSelectionPhase != null) {
                    phoneMapRouteSelectionCrosshair()
                }

                if (poiCreationActive) {
                    PhoneMapPopupCard(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(16.dp),
                        onDismiss = { poiCreationActive = false },
                    ) {
                        Text(text = stringResource(R.string.map_poi_tap_to_add))
                    }
                }

                routePointSelectionPhase?.let { phase ->
                    PhoneMapPopupCard(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(16.dp),
                        title = stringResource(R.string.map_route_tools_title),
                        onDismiss = routeToolsViewModel::cancel,
                    ) {
                        routeToolsState.pointSelectionProgress()?.let { progress ->
                            Text(
                                text =
                                    progress.total?.let { total ->
                                        stringResource(
                                            R.string.map_route_tools_step_progress,
                                            progress.currentStep,
                                            total,
                                        )
                                    } ?: stringResource(
                                        R.string.map_route_tools_points_progress,
                                        progress.completed,
                                    ),
                            )
                        }
                        Text(text = stringResource(routeToolsState.pointSelectionHintResource(phase)))
                    }
                }

                selectedPoi?.let { poi ->
                    phoneMapPoiDetailsCard(
                        poi = poi,
                        isMetric = generalSettings.isMetric,
                        onDismiss = { selectedPoi = null },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    )
                }
                distanceMeasurement?.let { measurement ->
                    phoneMapDistanceMeasurementCard(
                        measurement = measurement,
                        isMetric = generalSettings.isMetric,
                        onClear = { distanceMeasurement = null },
                    )
                }
                phoneMapLiveMetrics(
                    location = mapLocation,
                    elevationMeters = liveMapElevation,
                    position = liveMapPosition,
                    mapMode = mapUiState.mapMode,
                    mapSettings = mapSettings,
                    isMetric = generalSettings.isMetric,
                )
                offlineMapError?.let { error ->
                    PhoneMapPopupCard(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp),
                        onDismiss = { offlineMapError = null },
                    ) {
                        Text(text = stringResource(error.messageResource()))
                    }
                }
                mapLocationMessage?.takeIf { offlineMapError == null }?.let { messageResource ->
                    PhoneMapPopupCard(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp),
                        onDismiss = { mapLocationMessage = null },
                    ) {
                        Text(text = stringResource(messageResource))
                    }
                }
                routeToolsState.message?.takeIf { !routeToolsState.isOpen }?.let { message ->
                    PhoneMapPopupCard(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 72.dp, start = 16.dp, end = 16.dp),
                        onDismiss = routeToolsViewModel::dismissMessage,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = message, modifier = Modifier.weight(1f))
                            TextButton(onClick = routeToolsViewModel::dismissMessage) {
                                Text(stringResource(R.string.common_action_dismiss))
                            }
                        }
                    }
                }
                routeAnalysis?.let { analysis ->
                    PhoneMapRouteAnalysisPopup(
                        analysis = analysis,
                        isMetric = generalSettings.isMetric,
                        selectingPointB = routeAnalysisSelectingPointB,
                        onDismiss = {
                            routeAnalysis = null
                            routeAnalysisSelectingPointB = false
                        },
                        onSelectPointB = { routeAnalysisSelectingPointB = true },
                    )
                }
                poiCreationMessage?.let { savedName ->
                    PhoneMapPopupCard(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 72.dp, start = 16.dp, end = 16.dp),
                        onDismiss = { poiCreationMessage = null },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.map_poi_added, savedName),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { poiCreationMessage = null }) {
                                Text(stringResource(R.string.common_action_dismiss))
                            }
                        }
                    }
                }
                poiCreationPoint?.let { selectedPoint ->
                    PhoneMapPopupDialog(
                        title = stringResource(R.string.map_poi_add_title),
                        onDismiss = {
                            if (!poiCreationBusy) {
                                poiCreationPoint = null
                                poiCreationError = false
                            }
                        },
                        dismissEnabled = !poiCreationBusy,
                        text = {
                            Column {
                                Text(
                                    stringResource(
                                        R.string.map_poi_selected_location,
                                        selectedPoint.latitude,
                                        selectedPoint.longitude,
                                    ),
                                )
                                TextButton(
                                    onClick = {
                                        poiCreationPoint = null
                                        poiCreationActive = true
                                        poiCreationError = false
                                    },
                                    enabled = !poiCreationBusy,
                                ) {
                                    Text(stringResource(R.string.map_poi_change_location))
                                }
                                Text(stringResource(R.string.map_poi_name_hint))
                                OutlinedTextField(
                                    value = poiNameInput,
                                    onValueChange = {
                                        poiNameInput = it
                                        poiCreationError = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !poiCreationBusy,
                                    isError = poiCreationError,
                                )
                                if (poiCreationError) {
                                    Text(stringResource(R.string.map_poi_save_failed))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = savePoi, enabled = !poiCreationBusy) {
                                Text(
                                    stringResource(
                                        if (poiCreationBusy) {
                                            R.string.map_poi_saving
                                        } else {
                                            R.string.map_poi_save
                                        },
                                    ),
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    if (!poiCreationBusy) {
                                        poiCreationPoint = null
                                        poiCreationError = false
                                    }
                                },
                                enabled = !poiCreationBusy,
                            ) {
                                Text(stringResource(R.string.map_poi_cancel))
                            }
                        },
                    )
                }
                PhoneRouteToolsDialog(
                    state = routeToolsState,
                    currentLocationAvailable = mapLocation != null && hasLocationPermission,
                    actions =
                        PhoneRouteToolsActions(
                            onChooseMode = routeToolsViewModel::chooseMode,
                            onSelectModificationMode = routeToolsViewModel::chooseModificationMode,
                            onSelectRoute = routeToolsViewModel::selectRoute,
                            onCoordinatesChanged = routeToolsViewModel::updateCoordinates,
                            onResetMapPoints = routeToolsViewModel::resetMapPoints,
                            onCreate = { routeToolsViewModel.create(mapLocation) },
                            onDismiss = routeToolsViewModel::cancel,
                        ),
                )
                if (routePointSelectionPhase != null) {
                    phoneMapRouteSelectionControls(
                        canUndo = routeToolsState.canUndoLastMapPoint(),
                        onUndo = routeToolsViewModel::undoLastMapPoint,
                        onSelectCenter = onSelectRoutePointAtCenter,
                    )
                }
            }
        },
        panelContent = { tool, contentMode, featureSettingsSection ->
            MapToolPanelContent(
                tool = tool,
                contentMode = contentMode,
                featureSettingsSection = featureSettingsSection,
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
                        onOpenGpxAnalysis = { item -> selectedGpxAnalysis = item },
                        onRenameGpxItem = { item -> openContentRename(PhoneMapManagedContent.Gpx(item)) },
                        onDeleteGpxItem = { item -> openContentDelete(PhoneMapManagedContent.Gpx(item)) },
                        onOpenRouteTools = onOpenRouteTools,
                        onGpxSettingsChanged = { settings ->
                            gpxSettings = gpxSettingsPreferences.save(settings)
                        },
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
                        onPoiSourceVisibilityChanged = onPoiSourceVisibilityChanged,
                        onRenamePoiSource = { source -> openContentRename(PhoneMapManagedContent.Poi(source)) },
                        onDeletePoiSource = { source -> openContentDelete(PhoneMapManagedContent.Poi(source)) },
                        onPoiSettingsChanged = { settings ->
                            poiSettings = poiSettingsPreferences.save(settings)
                        },
                        onComparisonLayerChanged = { layer ->
                            val offlineLayer = layer as? PhoneMapComparisonLayer.Offline
                            if (offlineLayer == null) {
                                mapUiState = mapUiState.selectComparisonLayer(layer)
                            } else {
                                coroutineScope.launch {
                                    val error =
                                        withContext(Dispatchers.IO) {
                                            offlineMapStore.validate(offlineLayer.map)
                                        }
                                    if (error == null) {
                                        mapUiState = mapUiState.selectComparisonLayer(offlineLayer)
                                    } else {
                                        offlineMapError = error
                                    }
                                }
                            }
                        },
                        onComparisonTransparencyChanged = { percent ->
                            mapUiState = mapUiState.setComparisonTransparency(percent)
                        },
                        onLayerMapSettingsChanged = { settings ->
                            mapSettings = mapSettingsPreferences.save(settings)
                        },
                        onFeatureSettings = { mapUiState = mapUiState.showFeatureSettings() },
                        onFeatureSettingsSection = { section ->
                            mapUiState =
                                mapUiState.copy(
                                    toolPanel = mapUiState.toolPanel.showFeatureSettingsSection(section),
                                )
                        },
                        onCycleMapMode = onMapModePressed,
                        onGeneralSettingsChanged = { settings ->
                            generalSettings = generalSettingsPreferences.save(settings)
                        },
                        onOpenBundleDownload = { showOfflineBundleDownload = true },
                        onStorageChangeRequested = onStorageChangeRequested,
                    ),
            )
        },
    )

    selectedGpxAnalysis?.let { item ->
        PhoneMapGpxAnalysisDialog(
            item = item,
            settings = gpxSettings,
            generalSettings = generalSettings,
            onDismiss = { selectedGpxAnalysis = null },
        )
    }

    val renameFailedMessage = stringResource(R.string.map_content_rename_failed)
    val deleteFailedMessage = stringResource(R.string.map_content_delete_failed)

    contentRenameTarget?.let { target ->
        PhoneMapPopupDialog(
            title = stringResource(R.string.map_content_rename_title),
            onDismiss = {
                if (!contentActionBusy) {
                    contentRenameTarget = null
                    contentActionError = null
                }
            },
            dismissEnabled = !contentActionBusy,
            text = {
                Column {
                    OutlinedTextField(
                        value = contentNameInput,
                        onValueChange = {
                            contentNameInput = it
                            contentActionError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !contentActionBusy,
                        isError = contentActionError != null,
                    )
                    contentActionError?.let { message -> Text(message) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            contentActionBusy = true
                            contentActionError = null
                            runCatching { renameContent(target, contentNameInput) }
                                .onSuccess {
                                    contentRenameTarget = null
                                    contentActionError = null
                                }.onFailure { error ->
                                    contentActionError =
                                        error.message?.takeIf(String::isNotBlank)
                                            ?: renameFailedMessage
                                }
                            contentActionBusy = false
                        }
                    },
                    enabled = !contentActionBusy,
                ) {
                    Text(stringResource(R.string.map_content_action_rename))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        contentRenameTarget = null
                        contentActionError = null
                    },
                    enabled = !contentActionBusy,
                ) {
                    Text(stringResource(R.string.map_poi_cancel))
                }
            },
        )
    }

    contentDeleteTarget?.let { target ->
        PhoneMapPopupDialog(
            title = stringResource(R.string.map_content_delete_title),
            onDismiss = {
                if (!contentActionBusy) {
                    contentDeleteTarget = null
                    contentActionError = null
                }
            },
            dismissEnabled = !contentActionBusy,
            text = {
                Column {
                    Text(stringResource(R.string.map_content_delete_message, target.displayName))
                    contentActionError?.let { message -> Text(message) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            contentActionBusy = true
                            contentActionError = null
                            runCatching { deleteContent(target) }
                                .onSuccess {
                                    contentDeleteTarget = null
                                    contentActionError = null
                                }.onFailure { error ->
                                    contentActionError =
                                        error.message?.takeIf(String::isNotBlank)
                                            ?: deleteFailedMessage
                                }
                            contentActionBusy = false
                        }
                    },
                    enabled = !contentActionBusy,
                ) {
                    Text(stringResource(R.string.map_content_action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        contentDeleteTarget = null
                        contentActionError = null
                    },
                    enabled = !contentActionBusy,
                ) {
                    Text(stringResource(R.string.map_poi_cancel))
                }
            },
        )
    }

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
            onStart = { selection ->
                mapSettings = mapSettingsPreferences.save(mapSettings.copy(demSource = selection.demSource))
                bundleViewModel.start(selection)
            },
            onPause = bundleViewModel::pause,
            onStop = bundleViewModel::stop,
            onCancel = bundleViewModel::cancel,
            onResume = bundleViewModel::resume,
            onCheckForUpdates = bundleViewModel::checkForUpdates,
            onRefreshSelected = bundleViewModel::refreshSelected,
            onToggleRefreshSelection = bundleViewModel::toggleRefreshSelection,
            onClearUpdateChecks = bundleViewModel::clearUpdateChecks,
            initialDemSource = mapSettings.demSource,
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
    PhoneMapPopupDialog(
        title = stringResource(R.string.map_theme_selector_title),
        onDismiss = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mapToolsMapSettingPicker(
                    label = stringResource(R.string.map_theme_selector_theme_label),
                    selectedLabel = selectedTheme.label,
                    options = PhoneOfflineThemeCatalog.themes.map { theme -> theme.id to theme.label },
                    onSelect = onSelectTheme,
                )
                mapToolsMapSettingPicker(
                    label = stringResource(R.string.map_theme_selector_style_label),
                    selectedLabel =
                        selectedTheme.styles
                            .firstOrNull { style -> style.id == config.styleId }
                            ?.label
                            ?: "Default",
                    options = selectedTheme.styles.map { style -> style.id to style.label },
                    onSelect = onSelectStyle,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}

@Composable
private fun phoneMapCompassLifecycle(source: PhoneCompassSensorSource) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(source, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> source.start()
                    Lifecycle.Event.ON_PAUSE -> source.stop()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            source.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            source.stop()
        }
    }
}

@Composable
private fun phoneMapLocationLifecycle(
    source: PhoneMapLocationSource,
    hasLocationPermission: Boolean,
    onPermissionChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPermissionChanged by rememberUpdatedState(onPermissionChanged)
    DisposableEffect(source, lifecycleOwner, hasLocationPermission) {
        fun updateRegistration() {
            val permissionGranted = context.hasPhoneMapLocationPermission()
            if (permissionGranted != hasLocationPermission) {
                currentOnPermissionChanged(permissionGranted)
            }
            if (permissionGranted && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                source.start()
            } else {
                source.stop()
            }
        }

        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME,
                    Lifecycle.Event.ON_PAUSE,
                    -> updateRegistration()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateRegistration()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            source.stop()
        }
    }
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
private fun observeOnlineLiveMapPosition(
    runtime: MapRuntime,
    enabled: Boolean,
    location: PhoneMapLocation?,
    markerAnchor: PhoneMapMarkerAnchor,
    onPositionChanged: (PhoneMapLiveMetricsPosition) -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentLocation by rememberUpdatedState(location)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    DisposableEffect(runtime.map, enabled, location?.fixElapsedRealtimeMillis, markerAnchor) {
        if (!enabled) return@DisposableEffect onDispose {}
        val activeMap = runtime.map ?: return@DisposableEffect onDispose {}
        val activeMapView = runtime.mapView ?: return@DisposableEffect onDispose {}

        fun publish() {
            if (!runtime.isCurrentIn(currentRuntime) || activeMap.width <= 0 || activeMap.height <= 0) return
            val markerAnchorChanged = activeMap.applyPhoneMapMarkerAnchor(markerAnchor)
            val target =
                runCatching {
                    activeMap.projection.fromScreenLocation(
                        PointF(activeMap.width / 2f, activeMap.height / 2f),
                    )
                }.getOrNull() ?: return
            val userScreenPoint =
                currentLocation?.let { user ->
                    runCatching {
                        val point =
                            activeMap.projection.toScreenLocation(
                                LatLng(user.latitude, user.longitude),
                            )
                        PhoneMapScreenPoint(point.x, point.y)
                    }.getOrNull()
                }
            currentOnPositionChanged(
                PhoneMapLiveMetricsPosition(
                    target = PhoneMapCoordinate(target.latitude, target.longitude),
                    userScreenPoint = userScreenPoint,
                    origin =
                        currentLocation?.let { user ->
                            PhoneMapCoordinate(user.latitude, user.longitude)
                        },
                ),
            )
            if (markerAnchorChanged) {
                activeMapView.post { publish() }
            }
        }

        val listener = MapLibreMap.OnCameraMoveListener { publish() }
        activeMap.addOnCameraMoveListener(listener)
        publish()
        onDispose { activeMap.removeOnCameraMoveListener(listener) }
    }
}

@Composable
private fun observeOnlineUserPan(
    runtime: MapRuntime,
    onGestureActiveChanged: (Boolean) -> Unit,
    onUserPan: (Float) -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOnGestureActiveChanged by rememberUpdatedState(onGestureActiveChanged)
    val currentOnUserPan by rememberUpdatedState(onUserPan)
    DisposableEffect(runtime.map) {
        val activeMap = runtime.map ?: return@DisposableEffect onDispose {}
        val listener =
            object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    if (runtime.isCurrentIn(currentRuntime)) {
                        currentOnGestureActiveChanged(true)
                        recordOnlineGestureEvent("online_pan_begin", activeMap)
                    }
                }

                override fun onMove(detector: MoveGestureDetector) {
                    if (runtime.isCurrentIn(currentRuntime)) {
                        currentOnUserPan(activeMap.cameraPosition.bearing.toFloat())
                    }
                }

                override fun onMoveEnd(detector: MoveGestureDetector) {
                    if (runtime.isCurrentIn(currentRuntime)) {
                        recordOnlineGestureEvent("online_pan_end", activeMap)
                        currentOnGestureActiveChanged(false)
                    }
                }
            }
        activeMap.addOnMoveListener(listener)
        onDispose {
            activeMap.removeOnMoveListener(listener)
            currentOnGestureActiveChanged(false)
        }
    }
}

@Composable
private fun observeOnlineUserRotation(
    runtime: MapRuntime,
    onGestureActiveChanged: (Boolean) -> Unit,
    onUserRotation: (Float) -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOnGestureActiveChanged by rememberUpdatedState(onGestureActiveChanged)
    val currentOnUserRotation by rememberUpdatedState(onUserRotation)
    DisposableEffect(runtime.map) {
        val activeMap = runtime.map ?: return@DisposableEffect onDispose {}
        activeMap.uiSettings.setRotateGesturesEnabled(true)
        val listener =
            object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(detector: RotateGestureDetector) {
                    if (runtime.isCurrentIn(currentRuntime)) {
                        currentOnGestureActiveChanged(true)
                        recordOnlineGestureEvent("online_rotate_begin", activeMap)
                    }
                }

                override fun onRotate(detector: RotateGestureDetector) {
                    if (runtime.isCurrentIn(currentRuntime)) {
                        currentOnUserRotation(activeMap.cameraPosition.bearing.toFloat())
                    }
                }

                override fun onRotateEnd(detector: RotateGestureDetector) {
                    if (runtime.isCurrentIn(currentRuntime)) {
                        recordOnlineGestureEvent("online_rotate_end", activeMap)
                        currentOnGestureActiveChanged(false)
                    }
                }
            }
        activeMap.addOnRotateListener(listener)
        onDispose {
            activeMap.removeOnRotateListener(listener)
            currentOnGestureActiveChanged(false)
        }
    }
}

@Composable
private fun synchronizeOnlineMapControls(
    runtime: MapRuntime,
    command: PhoneMapCameraCommand?,
    mapSettings: PhoneMapSettings,
    onCameraCommandHandled: (Long) -> Unit,
) {
    val currentOnCameraCommandHandled by rememberUpdatedState(onCameraCommandHandled)
    LaunchedEffect(runtime.map, runtime.mapView, command, mapSettings) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        val camera = activeMap.cameraPosition
        val target = camera.target ?: return@LaunchedEffect
        val viewportWidthPx = activeMap.width.toDouble()
        if (viewportWidthPx > 0.0) {
            val minimumZoom =
                phoneMapZoomForScale(
                    latitudeDegrees = target.latitude,
                    scaleMeters = mapSettings.zoomMinScaleMeters,
                    viewportWidthPx = viewportWidthPx,
                )
            val maximumZoom =
                phoneMapZoomForScale(
                    latitudeDegrees = target.latitude,
                    scaleMeters = mapSettings.zoomMaxScaleMeters,
                    viewportWidthPx = viewportWidthPx,
                )
            if (minimumZoom != null && maximumZoom != null && minimumZoom <= maximumZoom) {
                activeMap.setMinZoomPreference(minimumZoom)
                activeMap.setMaxZoomPreference(maximumZoom)
                if (
                    target.latitude == defaultPhoneMapCamera.latitude &&
                    target.longitude == defaultPhoneMapCamera.longitude &&
                    camera.zoom == PHONE_MAP_DEFAULT_ZOOM
                ) {
                    phoneMapZoomForScale(
                        latitudeDegrees = target.latitude,
                        scaleMeters = mapSettings.zoomDefaultScaleMeters,
                        viewportWidthPx = viewportWidthPx,
                    )?.let { defaultZoom ->
                        activeMap.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder(camera).zoom(defaultZoom).build(),
                            ),
                        )
                    }
                }
            }
        }
        val pendingCommand = command ?: return@LaunchedEffect
        val requestedZoom =
            (activeMap.cameraPosition.zoom + pendingCommand.zoomDelta)
                .coerceIn(activeMap.minZoomLevel, activeMap.maxZoomLevel)
        activeMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(activeMap.cameraPosition).zoom(requestedZoom).build(),
            ),
        )
        currentOnCameraCommandHandled(pendingCommand.id)
    }
}

@Composable
private fun synchronizeComparisonOnlineCamera(
    runtime: MapRuntime,
    camera: PhoneMapCameraSnapshot,
) {
    LaunchedEffect(runtime.map, camera) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        val current = activeMap.cameraSnapshotOrNull()
        if (current == null || phoneMapComparisonCameraNeedsSync(current, camera)) {
            activeMap.moveCamera(camera.toMapLibreCameraUpdate())
        }
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
@Suppress("LongMethod", "LongParameterList") // Renderer orchestration keeps the state and SDK adapters explicit.
private fun synchronizeOnlineMapPresentation(
    runtime: MapRuntime,
    locationState: MapLocationState,
    mapMode: PhoneMapMode,
    mapSettings: PhoneMapSettings,
    userGestureActive: Boolean,
    compassSource: PhoneCompassSensorSource,
    compassPresentation: PhoneMapCompassPresentation,
) {
    val context = LocalContext.current
    val currentRuntime by rememberUpdatedState(runtime)
    val currentLocationState by rememberUpdatedState(locationState)
    val currentMapMode by rememberUpdatedState(mapMode)
    val currentUserGestureActive by rememberUpdatedState(userGestureActive)
    var lastCameraSyncSkip by remember { mutableStateOf<PhoneOnlineCameraSyncSkipKey?>(null) }

    LaunchedEffect(
        runtime.map,
        runtime.generation.styleRevision,
        locationState.hasPermission,
        compassPresentation.markerScreenRotationDegrees != null,
        mapSettings.markerStyle,
        mapSettings.gpsAccuracyCircleEnabled,
    ) {
        if (!locationState.hasPermission) return@LaunchedEffect
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { activeMap, _, style ->
            if (currentLocationState.hasPermission) {
                activeMap.enableLocationPuck(
                    style = style,
                    context = context,
                    compassSource = compassSource,
                    compassRenderable = compassPresentation.markerScreenRotationDegrees != null,
                    mapSettings = mapSettings,
                )
            }
        }
    }

    LaunchedEffect(
        runtime.map,
        runtime.generation.styleRevision,
        locationState.hasPermission,
        locationState.location,
        mapSettings.markerAnchor,
    ) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        if (!locationState.hasPermission || !activeMap.locationComponent.isLocationComponentActivated) {
            return@LaunchedEffect
        }
        activeMap.applyPhoneMapMarkerAnchor(mapSettings.markerAnchor)
        locationState.location?.let { location ->
            activeMap.locationComponent.forceLocationUpdate(location.toAndroidLocation())
        }
    }

    LaunchedEffect(
        runtime.map,
        runtime.generation.styleRevision,
        locationState.hasPermission,
        locationState.location,
        mapMode,
        userGestureActive,
        compassPresentation,
    ) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        if (!locationState.hasPermission || !activeMap.locationComponent.isLocationComponentActivated) {
            return@LaunchedEffect
        }
        when {
            currentUserGestureActive -> {
                recordOnlineCameraSyncSkippedIfChanged(
                    previous = lastCameraSyncSkip,
                    current =
                        PhoneOnlineCameraSyncSkipKey(
                            reason = "user_gesture",
                            follow = currentMapMode.follow,
                            orientation = currentMapMode.orientation,
                        ),
                    onRecorded = { lastCameraSyncSkip = it },
                )
            }
            currentMapMode.follow == PhoneMapFollowMode.FREE -> {
                recordOnlineCameraSyncSkippedIfChanged(
                    previous = lastCameraSyncSkip,
                    current =
                        PhoneOnlineCameraSyncSkipKey(
                            reason = "free_mode",
                            follow = currentMapMode.follow,
                            orientation = currentMapMode.orientation,
                        ),
                    onRecorded = { lastCameraSyncSkip = it },
                )
            }
            else -> {
                lastCameraSyncSkip = null
                activeMap.applyCompassCamera(
                    location = locationState.location,
                    follow = currentMapMode.follow,
                    compassPresentation = compassPresentation,
                )
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
        overlayState.settings,
    ) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { activeMap, activeMapView, style ->
            val latestOverlayState = currentOverlayState
            style.renderGpxTrack(
                segments = latestOverlayState.segments,
                isVisible = latestOverlayState.isVisible,
                settings = latestOverlayState.settings,
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
private fun synchronizeRouteAnalysisOverlay(
    runtime: MapRuntime,
    analysis: PhoneMapRouteAnalysis?,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    LaunchedEffect(runtime.map, runtime.generation.styleRevision, analysis) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { _, _, style ->
            style.renderRouteAnalysisMarkers(analysis)
        }
    }
}

@Composable
private fun synchronizePointSelectionOverlay(
    runtime: MapRuntime,
    markers: List<PhoneMapPointSelectionMarker>,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    LaunchedEffect(runtime.map, runtime.generation.styleRevision, markers) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { _, _, style ->
            style.renderPointSelectionMarkers(markers)
        }
    }
}

@Composable
private fun synchronizeDistanceMeasurementOverlay(
    runtime: MapRuntime,
    measurement: PhoneMapDistanceMeasurement?,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    LaunchedEffect(runtime.map, runtime.generation.styleRevision, measurement) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { _, _, style ->
            style.renderDistanceMeasurement(measurement)
        }
    }
}

@Composable
@Suppress("LongParameterList") // The Android map surface callbacks intentionally stay explicit.
private fun mapSurface(
    initialCamera: PhoneMapCameraSnapshot,
    onlineProvider: RasterOnlineMapProvider = PhoneMapRendererCatalog.mainOnlineRasterProvider,
    onMapTap: (PhoneMapCoordinate) -> Unit,
    onMapLongPress: (PhoneMapCoordinate) -> Unit,
    onTwoFingerTap: (PhoneMapCoordinate, PhoneMapCoordinate) -> Unit,
    onMapViewCreated: (MapView) -> Long,
    onMapReady: (Long, MapView, MapLibreMap) -> Unit,
    onStyleReady: (Long, MapView, MapLibreMap) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val latestOnTwoFingerTap = rememberUpdatedState(onTwoFingerTap)
    val latestOnMapTap = rememberUpdatedState(onMapTap)
    val latestOnMapLongPress = rememberUpdatedState(onMapLongPress)
    key(onlineProvider.id) {
        AndroidView(
            factory = { viewContext ->
                createMapView(
                    context = viewContext,
                    initialCamera = initialCamera,
                    onlineProvider = onlineProvider,
                    callbacks =
                        PhoneMapViewCallbacks(
                            onTwoFingerTap = { first, second -> latestOnTwoFingerTap.value(first, second) },
                            onMapTap = { point -> latestOnMapTap.value(point) },
                            onMapLongPress = { point -> latestOnMapLongPress.value(point) },
                            onCreated = onMapViewCreated,
                            onMapReady = onMapReady,
                            onStyleReady = onStyleReady,
                        ),
                )
            },
            modifier = modifier,
        )
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList") // Controls are one compact UI cluster; wrappers add no state boundary.
private fun mapControls(
    onBack: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    camera: PhoneMapCameraSnapshot,
    mapMode: PhoneMapMode,
    mapSettings: PhoneMapSettings,
    isMetric: Boolean,
    onCycleMapMode: () -> Unit,
    northIndicatorVisible: Boolean,
    compassPresentation: PhoneMapCompassPresentation,
    showModeControl: Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportWidthPx = maxWidth.value * LocalDensity.current.density
        val viewportWidth = maxWidth
        val scaleIndicator =
            remember(camera.latitude, camera.zoom, viewportWidthPx, isMetric) {
                calculatePhoneMapScaleIndicator(
                    latitudeDegrees = camera.latitude,
                    zoom = camera.zoom,
                    viewportWidthPx = viewportWidthPx.toDouble(),
                    isMetric = isMetric,
                )
            }

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
                    .statusBarsPadding()
                    .padding(
                        end = 16.dp,
                        top = 16.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (northIndicatorVisible) {
                phoneMapNorthIndicator(compassPresentation)
            }
            if (
                mapSettings.zoomButtonsMode != PhoneMapZoomButtonsMode.HIDE_BOTH &&
                mapSettings.zoomButtonsMode != PhoneMapZoomButtonsMode.HIDE_PLUS
            ) {
                FilledTonalIconButton(
                    onClick = onZoomIn,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.map_zoom_in_content_description),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (mapSettings.zoomButtonsMode != PhoneMapZoomButtonsMode.HIDE_BOTH) {
                FilledTonalIconButton(
                    onClick = onZoomOut,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = stringResource(R.string.map_zoom_out_content_description),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        scaleIndicator?.let { indicator ->
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 16.dp),
            ) {
                phoneMapScaleBar(
                    indicator = indicator,
                    width = viewportWidth * indicator.widthRatio,
                )
            }
        }

        if (showModeControl) {
            FilledTonalIconButton(
                onClick = onCycleMapMode,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp),
            ) {
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

@Composable
private fun BoxScope.phoneMapRouteSelectionCrosshair() {
    val density = LocalDensity.current
    val radius = with(density) { 14.dp.toPx() }
    val armLength = with(density) { 22.dp.toPx() }
    val strokeWidth = with(density) { 2.5.dp.toPx() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = Color.Black.copy(alpha = 0.45f),
            radius = radius + with(density) { 6.dp.toPx() },
            center = center,
        )
        drawCircle(
            color = Color(0xFFF7C948),
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        drawLine(
            color = Color(0xFFF7C948),
            start = Offset(center.x - armLength, center.y),
            end = Offset(center.x + armLength, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFFF7C948),
            start = Offset(center.x, center.y - armLength),
            end = Offset(center.x, center.y + armLength),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Color(0xFFF7C948),
            radius = with(density) { 2.5.dp.toPx() },
            center = center,
        )
    }
}

@Composable
private fun BoxScope.phoneMapRouteSelectionControls(
    canUndo: Boolean,
    onUndo: () -> Unit,
    onSelectCenter: () -> Unit,
) {
    Row(
        modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.map_route_tools_undo_point_content_description),
            )
        }
        FilledTonalIconButton(
            onClick = onSelectCenter,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.map_route_tools_select_center_content_description),
            )
        }
    }
}

private fun PhoneMapMode.icon(): ImageVector =
    when {
        isDetachedFromLocation -> Icons.Filled.MyLocation
        orientation == PhoneMapOrientation.HEADING_UP -> Icons.Filled.Explore
        else -> Icons.Filled.Navigation
    }

@Composable
private fun phoneMapNorthIndicator(compassPresentation: PhoneMapCompassPresentation) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .rotate(compassPresentation.northIndicatorScreenRotationDegrees),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(org.maplibre.android.R.drawable.maplibre_compass_icon),
            contentDescription = stringResource(R.string.map_north_indicator_content_description),
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = stringResource(R.string.map_north_indicator_label),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            color = Color.Black,
            fontSize = 8.sp,
        )
    }
}

@Composable
private fun BoxScope.phoneMapDistanceMeasurementCard(
    measurement: PhoneMapDistanceMeasurement,
    isMetric: Boolean,
    onClear: () -> Unit,
) {
    PhoneMapPopupCard(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp),
        onDismiss = onClear,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        stringResource(
                            R.string.map_distance_measurement_title,
                        ) +
                            ": " +
                            formatPhoneMapMeasuredDistance(measurement.distanceMeters, isMetric),
                )
                Text(stringResource(R.string.map_distance_measurement_hint))
            }
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.map_distance_measurement_clear))
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // This small HUD helper receives the already-resolved map inputs explicitly.
private fun BoxScope.phoneMapLiveMetrics(
    location: PhoneMapLocation?,
    elevationMeters: Double?,
    position: PhoneMapLiveMetricsPosition?,
    mapMode: PhoneMapMode,
    mapSettings: PhoneMapSettings,
    isMetric: Boolean,
) {
    if (!mapMode.isDetachedFromLocation || position == null) return
    val liveDistance =
        if (mapSettings.liveDistanceEnabled) {
            phoneMapLiveDistanceMeters(
                position = position,
                fallbackOrigin = location?.let { user -> PhoneMapCoordinate(user.latitude, user.longitude) },
            )
        } else {
            null
        }
    val liveElevationEnabled = mapSettings.liveElevationEnabled
    val liveDistanceEnabled = mapSettings.liveDistanceEnabled
    if (!liveElevationEnabled && !liveDistanceEnabled) return

    phoneMapLiveMetricsGuide(
        position = position,
        showDistanceLine = liveDistanceEnabled,
    )
    if (liveElevationEnabled) {
        phoneMapLiveElevationCard(elevationMeters, isMetric)
    }
    liveDistance?.let { distance ->
        phoneMapLiveDistanceCard(distance, isMetric)
    }
}

@Composable
private fun BoxScope.phoneMapLiveMetricsGuide(
    position: PhoneMapLiveMetricsPosition,
    showDistanceLine: Boolean,
) {
    val density = LocalDensity.current
    val reticleRadius = with(density) { 12.dp.toPx() }
    val reticleStroke = with(density) { 2.dp.toPx() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        if (showDistanceLine) {
            position.userScreenPoint?.let { userPoint ->
                drawLine(
                    color = Color(0xFF8B5CF6).copy(alpha = 0.9f),
                    start = Offset(userPoint.x, userPoint.y),
                    end = center,
                    strokeWidth = reticleStroke,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
                )
            }
        }
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f),
            radius = reticleRadius,
            center = center,
        )
        drawCircle(
            color = Color(0xFF8B5CF6),
            radius = reticleRadius,
            center = center,
            style = Stroke(width = reticleStroke),
        )
        drawLine(
            color = Color(0xFF8B5CF6),
            start = Offset(center.x - reticleRadius * 1.35f, center.y),
            end = Offset(center.x + reticleRadius * 1.35f, center.y),
            strokeWidth = reticleStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFF8B5CF6),
            start = Offset(center.x, center.y - reticleRadius * 1.35f),
            end = Offset(center.x, center.y + reticleRadius * 1.35f),
            strokeWidth = reticleStroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun BoxScope.phoneMapLiveElevationCard(
    elevationMeters: Double?,
    isMetric: Boolean,
) {
    Card(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
    ) {
        Text(
            stringResource(
                R.string.map_live_elevation_value,
                formatPhoneMapElevation(elevationMeters ?: Double.NaN, isMetric),
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BoxScope.phoneMapLiveDistanceCard(
    distanceMeters: Double,
    isMetric: Boolean,
) {
    Card(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 148.dp),
    ) {
        Text(
            stringResource(
                R.string.map_live_distance_value,
                formatPhoneMapMeasuredDistance(distanceMeters, isMetric),
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private fun formatPhoneMapElevation(
    meters: Double,
    isMetric: Boolean,
): String {
    if (!meters.isFinite()) return "—"
    return if (isMetric) {
        "${meters.roundToInt()} m"
    } else {
        "${(meters * 3.28084).roundToInt()} ft"
    }
}

@Composable
private fun phoneMapScaleBar(
    indicator: PhoneMapScaleIndicator,
    width: Dp,
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = indicator.label,
            color = Color.White,
            modifier =
                Modifier
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Box(
            modifier =
                Modifier
                    .padding(top = 2.dp)
                    .width(width)
                    .height(10.dp),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val centerY = size.height / 2f
                val barWidth = 4.dp.toPx()
                val tickWidth = 3.dp.toPx()
                drawLine(
                    color = Color.White.copy(alpha = 0.75f),
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = barWidth,
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.95f),
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = 2.dp.toPx(),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.75f),
                    start = Offset(tickWidth / 2f, 0f),
                    end = Offset(tickWidth / 2f, size.height),
                    strokeWidth = tickWidth,
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.75f),
                    start = Offset(size.width - tickWidth / 2f, 0f),
                    end = Offset(size.width - tickWidth / 2f, size.height),
                    strokeWidth = tickWidth,
                )
            }
        }
    }
}

private fun createMapView(
    context: Context,
    initialCamera: PhoneMapCameraSnapshot,
    onlineProvider: RasterOnlineMapProvider,
    callbacks: PhoneMapViewCallbacks,
): MapView {
    ensureMapLibreConfigured(context)
    var mapForProjection: MapLibreMap? = null
    return MapView(context).also { mapView ->
        val twoFingerTapDetector =
            PhoneTwoFingerTapDetector(context) { x1, y1, x2, y2 ->
                mapForProjection?.let { map ->
                    runCatching {
                        val first = map.projection.fromScreenLocation(PointF(x1, y1))
                        val second = map.projection.fromScreenLocation(PointF(x2, y2))
                        callbacks.onTwoFingerTap(
                            PhoneMapCoordinate(first.latitude, first.longitude),
                            PhoneMapCoordinate(second.latitude, second.longitude),
                        )
                    }
                }
            }
        val longPressDetector =
            phoneMapLongPressDetector(context) { x, y ->
                mapForProjection?.let { map ->
                    runCatching {
                        map.projection.fromScreenLocation(PointF(x, y))
                    }.onSuccess { point ->
                        callbacks.onMapLongPress(PhoneMapCoordinate(point.latitude, point.longitude))
                    }
                }
            }
        mapView.setOnTouchListener { _, event ->
            twoFingerTapDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                longPressDetector.cancelPhoneMapLongPress(event)
            }
            if (event.pointerCount == 1) {
                longPressDetector.onTouchEvent(event)
            }
            false
        }
        val generation = callbacks.onCreated(mapView)
        mapView.onCreate(null)
        mapView.getMapAsync { map ->
            if (mapView.isDestroyed) return@getMapAsync
            mapForProjection = map
            map.uiSettings.setCompassEnabled(false)
            callbacks.onMapReady(generation, mapView, map)
            map.addOnMapClickListener { point ->
                callbacks.onMapTap(PhoneMapCoordinate(point.latitude, point.longitude))
                false
            }
            map.setStyle(
                Style.Builder().fromJson(
                    onlineProvider.mapLibreRasterStyleJson(),
                ),
            ) {
                if (!mapView.isDestroyed) {
                    map.moveCamera(initialCamera.toMapLibreCameraUpdate())
                    callbacks.onStyleReady(generation, mapView, map)
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
    compassSource: PhoneCompassSensorSource,
    compassRenderable: Boolean,
    mapSettings: PhoneMapSettings,
) {
    val locationComponent = locationComponent
    if (!locationComponent.isLocationComponentActivated) {
        locationComponent.activateLocationComponent(
            LocationComponentActivationOptions.builder(context.applicationContext, style).build(),
        )
    }
    locationComponent.isLocationComponentEnabled = true
    locationComponent.cameraMode = CameraMode.NONE
    locationComponent.compassEngine = compassSource
    locationComponent.renderMode = if (compassRenderable) RenderMode.COMPASS else RenderMode.NORMAL
    val options =
        locationComponent
            .getLocationComponentOptions()
            .toBuilder()
            .foregroundDrawable(
                if (mapSettings.markerStyle == PhoneMapMarkerStyle.TRIANGLE) {
                    R.drawable.map_location_marker_triangle
                } else {
                    R.drawable.map_location_marker_dot
                },
            ).accuracyAlpha(if (mapSettings.gpsAccuracyCircleEnabled) 0.25f else 0f)
            .build()
    locationComponent.applyStyle(options)
}

private fun MapLibreMap.applyPhoneMapMarkerAnchor(anchor: PhoneMapMarkerAnchor): Boolean {
    val heightPx = height.toInt()
    if (heightPx <= 0) return false
    val topPadding =
        if (anchor == PhoneMapMarkerAnchor.LOWER) {
            (heightPx * 0.64f).toInt()
        } else {
            0
        }
    val currentPadding = padding
    val paddingNeedsUpdate =
        currentPadding[0] != 0 ||
            currentPadding[1] != topPadding ||
            currentPadding[2] != 0 ||
            currentPadding[3] != 0
    if (paddingNeedsUpdate) {
        setPadding(0, topPadding, 0, 0)
    }
    return paddingNeedsUpdate
}

private fun MapLibreMap.applyCompassCamera(
    location: PhoneMapLocation?,
    follow: PhoneMapFollowMode,
    compassPresentation: PhoneMapCompassPresentation,
) {
    if (follow == PhoneMapFollowMode.FREE) return
    val current = cameraPosition
    val next =
        CameraPosition
            .Builder(current)
            .bearing(compassPresentation.mapBearingDegrees.toDouble())
            .apply {
                if (follow == PhoneMapFollowMode.FOLLOW_LOCATION && location != null) {
                    target(LatLng(location.latitude, location.longitude))
                }
            }.build()
    val targetChanged = next.target != current.target
    val zoomChanged = next.zoom != current.zoom
    val bearingChanged =
        phoneMapBearingNeedsSync(
            currentDegrees = current.bearing.toFloat(),
            targetDegrees = next.bearing.toFloat(),
        )
    if (targetChanged || zoomChanged || bearingChanged) {
        moveCamera(CameraUpdateFactory.newCameraPosition(next))
    }
}

private fun recordOnlineGestureEvent(
    event: String,
    map: MapLibreMap,
) {
    val camera = map.cameraPosition
    PhoneDebugCapture.log(
        PHONE_MAP_LIBRE_DIAGNOSTICS_TAG,
        "event=$event bearing=${camera.bearing.toFloat()} zoom=${camera.zoom}",
    )
}

private fun recordOnlineCameraSyncSkippedIfChanged(
    previous: PhoneOnlineCameraSyncSkipKey?,
    current: PhoneOnlineCameraSyncSkipKey,
    onRecorded: (PhoneOnlineCameraSyncSkipKey) -> Unit,
) {
    if (!shouldRecordPhoneOnlineCameraSyncSkip(previous, current)) return
    PhoneDebugCapture.log(
        PHONE_MAP_LIBRE_DIAGNOSTICS_TAG,
        "event=online_camera_sync_skipped reason=${current.reason} " +
            "follow=${current.follow} orientation=${current.orientation}",
    )
    onRecorded(current)
}

private const val PHONE_MAP_LIBRE_DIAGNOSTICS_TAG = "PhoneMapLibre"

private fun PhoneMapLocation.toAndroidLocation(): android.location.Location =
    android.location.Location("phone-map").apply {
        latitude = this@toAndroidLocation.latitude
        longitude = this@toAndroidLocation.longitude
    }

private fun PhoneMapCameraSnapshot.toMapLibreCameraUpdate() =
    CameraUpdateFactory.newCameraPosition(
        CameraPosition
            .Builder()
            .target(LatLng(latitude, longitude))
            .zoom(zoom)
            .bearing(bearingDegrees.toDouble())
            .build(),
    )

private fun MapLibreMap.cameraSnapshotOrNull(): PhoneMapCameraSnapshot? =
    runCatching {
        val camera = cameraPosition
        val target = camera.target ?: return@runCatching null
        PhoneMapCameraSnapshot(
            latitude = target.latitude,
            longitude = target.longitude,
            zoom = camera.zoom,
            bearingDegrees = camera.bearing.toFloat(),
        )
    }.getOrNull()
