@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.map

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glancemap.glancemapcompanionapp.FileTransferViewModel
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.discovery.PublishedTrail
import com.glancemap.glancemapcompanionapp.discovery.TrailDiscoveryContent
import com.glancemap.glancemapcompanionapp.discovery.TrailDiscoveryQuery
import com.glancemap.glancemapcompanionapp.discovery.TrailDiscoverySearchActions
import com.glancemap.glancemapcompanionapp.discovery.TrailDiscoveryViewModel
import com.glancemap.glancemapcompanionapp.discovery.discoveryCurrentLocationQuery
import com.glancemap.glancemapcompanionapp.ensureMapLibreConfigured
import com.glancemap.glancemapcompanionapp.hike.PhoneHikeMapEffects
import com.glancemap.glancemapcompanionapp.hike.PhoneHikeUiState
import com.glancemap.glancemapcompanionapp.hike.PhoneHikeViewModel
import com.glancemap.glancemapcompanionapp.hike.phoneHikeMapMarkers
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganization
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganizationStore
import com.glancemap.glancemapcompanionapp.map.maplibre.MapLibreBasemapOpacity
import com.glancemap.glancemapcompanionapp.map.maplibre.fitGpxTrackBounds
import com.glancemap.glancemapcompanionapp.map.maplibre.mapLibreOnlineStyle
import com.glancemap.glancemapcompanionapp.map.maplibre.phoneMapGpxFitIsStillEligible
import com.glancemap.glancemapcompanionapp.map.maplibre.phoneMapHillshadeOpacity
import com.glancemap.glancemapcompanionapp.map.maplibre.prepareOnlineAttribution
import com.glancemap.glancemapcompanionapp.map.maplibre.renderDistanceMeasurement
import com.glancemap.glancemapcompanionapp.map.maplibre.renderGpxOverlays
import com.glancemap.glancemapcompanionapp.map.maplibre.renderPointSelectionMarkers
import com.glancemap.glancemapcompanionapp.map.maplibre.renderRouteAnalysisMarkers
import com.glancemap.glancemapcompanionapp.map.maplibre.setOnlineHillshade
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.guidance.TrailGuidanceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.log2

internal val defaultPhoneMapCamera =
    PhoneMapCameraSnapshot(latitude = 20.0, longitude = 0.0, zoom = PHONE_MAP_DEFAULT_ZOOM)
private const val PHONE_MAP_LIVE_METRICS_REFRESH_MS = 320L
private const val PHONE_POI_SELECTION_PREVIEW_DELAY_MS = 700L
private const val PHONE_STORAGE_OPERATION_STOP_TIMEOUT_MS = 5_000L
private const val PHONE_STORAGE_OPERATION_POLL_DELAY_MS = 50L
private const val PHONE_STORAGE_RENDER_STOP_DELAY_MS = 100L
private const val PHONE_MAP_FEATURE_DIAGNOSTICS_TAG = "PhoneMapFeature"
private const val PHONE_MAP_NANOS_PER_MILLISECOND = 1_000_000L
private const val PHONE_ROUTE_TOOLS_PREVIEW_OVERLAY_ID = "route-tools-preview"

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
): Boolean = target == current && !storage.needsCanonicalMigration() && (pending == null || pending.target != target)

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
    val basemapOpacity: MapLibreBasemapOpacity? = null,
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
            copy(
                generation = generation.onStyleReady(callbackGeneration),
                basemapOpacity = callbackMap.style?.let(::MapLibreBasemapOpacity),
            )
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

    fun isRendererCurrentIn(latestRuntime: MapRuntime): Boolean =
        generation.renderer == latestRuntime.generation.renderer &&
            mapView === latestRuntime.mapView &&
            map === latestRuntime.map

    fun acceptsRendererCallback(
        callbackGeneration: Long,
        callbackMapView: MapView,
    ): Boolean =
        phoneMapLibreRendererCallbackIsCurrent(
            callbackGeneration = callbackGeneration,
            currentGeneration = generation.renderer,
            callbackRenderer = callbackMapView,
            currentRenderer = mapView,
        )

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

private fun offlineMapDiagnosticRenderer(
    map: PhoneOfflineMap,
    role: PhoneOfflineMapRendererRole,
    rendererRefs: Map<PhoneOfflineMapRendererRole, PhoneOfflineMapRendererRef>,
): PhoneMapDiagnosticRenderer {
    val offlineMapId = map.opaqueDiagnosticId()
    val renderer = rendererRefs[role]?.takeIf { ref -> ref.identity.offlineMapId == offlineMapId }
    return if (renderer == null) {
        PhoneMapDiagnosticRenderer("mapsforge-$offlineMapId", 0L, PhoneMapDiagnosticRendererStatus.PENDING)
    } else {
        PhoneMapDiagnosticRenderer(
            identity = "mapsforge-${renderer.identity.rendererId}",
            generation = renderer.identity.generation,
            status =
                when (renderer.status) {
                    PhoneOfflineMapRendererStatus.ACTIVE -> PhoneMapDiagnosticRendererStatus.ACTIVE
                    PhoneOfflineMapRendererStatus.DISPOSED -> PhoneMapDiagnosticRendererStatus.DISPOSED
                },
        )
    }
}

private fun mapLibreDiagnosticRenderer(
    identity: String,
    generation: Long,
    mapView: MapView?,
): PhoneMapDiagnosticRenderer =
    PhoneMapDiagnosticRenderer(
        identity = identity,
        generation = generation,
        status =
            when {
                mapView == null -> PhoneMapDiagnosticRendererStatus.PENDING
                mapView.isDestroyed -> PhoneMapDiagnosticRendererStatus.DISPOSED
                else -> PhoneMapDiagnosticRendererStatus.ACTIVE
            },
    )

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

private data class GpxFitCallbacks(
    val onFitted: () -> Unit,
    val onCameraInput: () -> Unit,
)

private data class GpxFitRequest(
    val segments: List<PhoneMapRouteSegment>,
    val isRequested: Boolean,
    val isOwner: Boolean,
)

private data class PhoneMapLibreRendererRef(
    val identity: PhoneMapLibreRendererIdentity,
    val generation: Long,
    val mapView: MapView,
    val source: PhoneOnlineMapSource,
    val providerId: String,
) {
    fun isCurrentIn(
        runtime: MapRuntime,
        activeSource: PhoneMapLibreActiveSource,
        expectedRole: PhoneMapLibreRendererRole,
    ): Boolean =
        identity.role == expectedRole &&
            runtime.acceptsRendererCallback(generation, mapView) &&
            source == activeSource.source &&
            providerId == activeSource.providerId
}

private data class PhoneMapLibreRenderFailure(
    val renderer: PhoneMapLibreRendererRef,
    val category: PhoneMapLibreErrorCategory,
)

private data class PhoneMapViewCallbacks(
    val onTwoFingerTap: (PhoneMapCoordinate, PhoneMapCoordinate) -> Unit,
    val onMeasurementPointMoved: (Int, PhoneMapCoordinate) -> Unit = { _, _ -> },
    val onMapTap: (PhoneMapCoordinate) -> Unit,
    val onMapLongPress: (PhoneMapCoordinate) -> Unit,
    val onCreated: (MapView) -> Long,
    val isRendererCurrent: (PhoneMapLibreRendererRef) -> Boolean,
    val onMapReady: (Long, MapView, MapLibreMap) -> Unit,
    val onStyleReady: (Long, MapView, MapLibreMap) -> Unit,
    val onRendererFailure: (PhoneMapLibreRenderFailure) -> Unit,
    val onRendererRecovered: (PhoneMapLibreRendererRef, PhoneMapLibreErrorCategory) -> Unit,
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
    gpxSourcesLoading: Boolean,
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
    onPlannedRouteFinished: ((String?) -> Unit)? = null,
    onPlanAcrossDays: ((String?) -> Unit)? = null,
    onOpenMenu: () -> Unit,
    isVisible: Boolean = true,
    initialTool: MapTool? = null,
    initialToolSettings: Boolean = false,
    initialDiscoveryRequest: Long? = null,
    navigation: MapNavigationActions = MapNavigationActions(),
    hikeController: PhoneHikeViewModel? = null,
    hikeContent: @Composable () -> Unit = {},
    hikeHeaderAction: @Composable () -> Unit = {},
    libraryContent: @Composable (MapToolsPanelState, MapToolsPanelActions) -> Unit = { _, _ -> },
    libraryHeaderContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val migrationTextResources = LocalResources.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val mapActive = isVisible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val coroutineScope = rememberCoroutineScope()
    val hikeUi = hikeController?.state?.collectAsStateWithLifecycle()?.value ?: remember { PhoneHikeUiState() }
    var hikeMapViewport by remember { mutableStateOf(IntSize.Zero) }
    val offlineMapStore = remember(context) { PhoneOfflineMapStore(context.applicationContext) }
    val bundleStore = remember(context) { PhoneOfflineBundleStore(context.applicationContext) }
    val elevationStore = remember(context) { PhoneElevationStore(context.applicationContext) }
    val elevationFolderSource = remember(context) { PhoneElevationFolderSource(context.applicationContext) }
    val routingFolderSource = remember(context) { PhoneRoutingFolderSource(context.applicationContext) }
    val elevationRepository = remember(context) { PhoneElevationRepository(context.applicationContext) }
    val onlineElevationRepository = remember(context) { PhoneOnlineElevationRepository(context.applicationContext) }
    val offlineStorage = remember(context) { PhoneOfflineStorage(context.applicationContext) }
    val userPoiStore = remember(context) { PhoneMapUserPoiStore(context.applicationContext) }
    val mapFolderSource =
        remember(context, offlineMapStore) {
            PhoneOfflineMapFolderSource(context.applicationContext, offlineMapStore)
        }
    val gpxFolderSource = remember(context) { PhoneGpxFolderSource(context.applicationContext) }
    val gpxSettingsPreferences = remember(context) { PhoneMapGpxSettingsPreferences(context.applicationContext) }
    val gpxCustomizationPreferences = remember(context) { PhoneGpxCustomizationPreferences(context.applicationContext) }
    val initialGpxCustomization = remember(gpxCustomizationPreferences) { gpxCustomizationPreferences.load() }
    val gpxCustomization by gpxCustomizationPreferences.changes.collectAsStateWithLifecycle(initialGpxCustomization)
    val libraryOrganizationStore = remember(context) { PhoneLibraryOrganizationStore(context.applicationContext) }
    val libraryOrganization by libraryOrganizationStore.changes.collectAsStateWithLifecycle(PhoneLibraryOrganization())
    val compassSensorSource = remember(context) { PhoneCompassSensorSource(context.applicationContext) }
    val sensorCapabilities = remember(context) { resolvePhoneSensorCapabilities(context.applicationContext) }
    val compassSettingsPreferences =
        remember(context) { PhoneCompassSettingsPreferences(context.applicationContext) }
    val mapLocationSource = remember(context) { PhoneMapLocationSource(context.applicationContext) }
    val generalSettingsPreferences =
        remember(context) { PhoneGeneralSettingsPreferences(context.applicationContext) }
    val recordingSettingsPreferences =
        remember(context) { PhoneRecordingSettingsPreferences(context.applicationContext) }
    val offlineThemePreferences = remember(context) { PhoneOfflineThemePreferences(context.applicationContext) }
    val mapSettingsPreferences = remember(context) { PhoneMapSettingsPreferences(context.applicationContext) }
    val mapSourcePreferences = remember(context) { PhoneMapSourcePreferences(context.applicationContext) }
    val poiSettingsPreferences = remember(context) { PhoneMapPoiSettingsPreferences(context.applicationContext) }
    val bundleViewModel: PhoneOfflineBundleViewModel = viewModel()
    val bundleUiState by bundleViewModel.uiState.collectAsStateWithLifecycle()
    val fileTransferViewModel: FileTransferViewModel = viewModel()
    val gpxViewModel: PhoneMapGpxViewModel = viewModel()
    val routeToolsViewModel: PhoneRouteToolsViewModel = viewModel()
    val recordingState by PhoneRecordingSessionStore.state.collectAsStateWithLifecycle()
    val guidanceState by PhoneGuidanceSessionStore.state.collectAsStateWithLifecycle()
    val recordingSettings by PhoneRecordingSettingsState.state.collectAsStateWithLifecycle()
    val discoveryViewModel: TrailDiscoveryViewModel = viewModel()
    val discoverySaveState by discoveryViewModel.saveState.collectAsStateWithLifecycle()
    var discoveryOpen by rememberSaveable { mutableStateOf(initialDiscoveryRequest != null) }
    var discoveryPickingPoint by rememberSaveable { mutableStateOf(false) }
    var discoveryNearMePending by rememberSaveable { mutableStateOf(false) }
    var discoverySearchPoint by remember { mutableStateOf<PhoneMapCoordinate?>(null) }
    var discoveredPreview by remember { mutableStateOf<PublishedTrail?>(null) }
    LaunchedEffect(discoverySaveState.revision) {
        if (discoverySaveState.revision > 0) onRouteSaved()
    }
    val searchViewModel: PhoneMapSearchViewModel = viewModel()
    val searchState by searchViewModel.controller.state.collectAsStateWithLifecycle()
    var onlinePlaceSearchEnabled by remember(generalSettingsPreferences) {
        mutableStateOf(generalSettingsPreferences.isOnlineSearchEnabled())
    }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchSelection by rememberSaveable(stateSaver = phoneMapSearchResultSaver) {
        mutableStateOf<PhoneMapSearchResult?>(null)
    }
    DisposableEffect(searchViewModel) {
        onDispose { searchViewModel.controller.cancel() }
    }
    val gpxUiState by gpxViewModel.uiState.collectAsStateWithLifecycle()
    val hikeTrackId =
        hikeUi.model
            ?.route
            ?.trackId
            ?.removePrefix("gpx/")
    val hikeTrackEnabled = gpxUiState.items.firstOrNull { it.id == hikeTrackId }?.enabled != false
    val routeToolsState by routeToolsViewModel.uiState.collectAsStateWithLifecycle()
    val compassState by compassSensorSource.state.collectAsStateWithLifecycle()
    val mapSourceLocation by mapLocationSource.location.collectAsStateWithLifecycle()
    var mapRuntime by remember { mutableStateOf(MapRuntime()) }
    var comparisonMapRuntime by remember { mutableStateOf(MapRuntime()) }
    val activeMapRuntime = if (mapActive) mapRuntime else MapRuntime()
    val activeComparisonMapRuntime = if (mapActive) comparisonMapRuntime else MapRuntime()
    val offlineRendererRefs by PhoneOfflineMapRendererDiagnostics.rendererRefs.collectAsStateWithLifecycle()
    val phoneDebugCaptureState = PhoneDebugCapture.state.collectAsState()
    // Log counters change per entry; the map only needs capture lifecycle changes.
    val captureSessionId by remember { derivedStateOf { phoneDebugCaptureState.value.sessionId } }
    val captureActive by remember { derivedStateOf { phoneDebugCaptureState.value.active } }
    LaunchedEffect(context) {
        // Restore any service-owned REC or phone-guidance session; this never creates a new session.
        PhoneRecordingService.request(context, PhoneRecordingService.ACTION_RESTORE)
    }
    LaunchedEffect(recordingSettingsPreferences) {
        PhoneRecordingSettingsState.publish(recordingSettingsPreferences.load())
    }
    LaunchedEffect(recordingState.message) {
        if (recordingState.message == PhoneRecordingMessage.SAVED) onRouteSaved()
    }
    var mapUiState by rememberSaveable(stateSaver = phoneMapUiStateSaver) {
        val initialState = PhoneMapUiState(toolPanel = mapSettingsPreferences.loadToolPanelSizing())
        mutableStateOf(initialTool?.let { initialState.openToolPage(it, initialToolSettings) } ?: initialState)
    }
    val mapLocation =
        (recordingState.latestLocation.takeIf { recordingState.locationUpdatesActive } ?: mapSourceLocation)
            .takeUnless { mapUiState.mapMode.isOffline }
    val settingsPage = initialToolSettings || initialTool == MapTool.SETTINGS
    var handledInitialTool by rememberSaveable { mutableStateOf(initialTool) }
    var handledToolSettings by rememberSaveable { mutableStateOf(initialToolSettings) }
    LaunchedEffect(initialTool, initialToolSettings) {
        if (initialTool != handledInitialTool || initialToolSettings != handledToolSettings) {
            handledInitialTool = initialTool
            handledToolSettings = initialToolSettings
            if (initialTool != null) mapUiState = mapUiState.openToolPage(initialTool, initialToolSettings)
        }
    }
    LaunchedEffect(initialDiscoveryRequest) {
        if (initialDiscoveryRequest != null) {
            discoveryOpen = true
            mapUiState = mapUiState.openToolPage(MapTool.GPX, featureSettings = false)
        }
    }
    LaunchedEffect(discoveryOpen, mapUiState.toolPanel.activeTool, mapUiState.toolPanel.mode) {
        if (
            !discoveryOpen ||
            mapUiState.toolPanel.activeTool != MapTool.GPX ||
            mapUiState.toolPanel.mode == MapToolPanelMode.CLOSED
        ) {
            discoveryPickingPoint = false
            discoveryNearMePending = false
        }
    }
    LaunchedEffect(onlinePlaceSearchEnabled, poiSources) {
        searchViewModel.controller.setContext(onlinePlaceSearchEnabled, poiSources.enabledFileNames())
    }
    LaunchedEffect(hikeUi.revealOnMap) {
        if (hikeUi.revealOnMap) {
            mapUiState = mapUiState.copy(contentVisibility = mapUiState.contentVisibility.copy(gpxTracks = true))
            if (!hikeTrackEnabled) hikeTrackId?.let(gpxViewModel::toggle)
            hikeController?.consumeMapReveal()
        }
    }
    LaunchedEffect(hikeUi.pickingCheckpoint, mapUiState.toolPanel) {
        if (hikeUi.pickingCheckpoint) {
            if (mapUiState.toolPanel.activeTool != MapTool.HIKE ||
                mapUiState.toolPanel.mode == MapToolPanelMode.CLOSED
            ) {
                hikeController?.cancelCheckpoint()
            } else if (mapUiState.toolPanel.mode == MapToolPanelMode.EXPANDED) {
                mapUiState = mapUiState.copy(toolPanel = mapUiState.toolPanel.collapse())
            }
        }
    }
    var mapSourcePreference by remember(mapSourcePreferences) {
        mutableStateOf(mapSourcePreferences.load())
    }
    val selectedOnlineSource =
        effectiveOnlineMapSource(
            preferred = mapSourcePreference.onlineSource,
            isAvailable = PhoneMapRendererCatalog::isOnlineSourceAvailable,
        )
    val selectedOnlineProvider =
        PhoneMapRendererCatalog.providerForOnlineSource(selectedOnlineSource)
            ?: PhoneMapRendererCatalog.mainOnlineRasterProvider
    var pendingOfflineMap by remember { mutableStateOf<PhoneOfflineMap?>(null) }
    var onlineGestureState by remember { mutableStateOf(PhoneOnlineGestureState()) }
    var generalSettings by remember(generalSettingsPreferences) {
        mutableStateOf(generalSettingsPreferences.load())
    }
    var compassSettings by remember(compassSettingsPreferences) {
        mutableStateOf(compassSettingsPreferences.load())
    }
    var distanceMeasurement by rememberSaveable(stateSaver = phoneMapDistanceMeasurementSaver) {
        mutableStateOf<PhoneMapDistanceMeasurement?>(null)
    }
    var gpxSettings by remember(gpxSettingsPreferences) { mutableStateOf(gpxSettingsPreferences.load()) }
    var offlineMaps by remember { mutableStateOf(emptyList<PhoneOfflineMap>()) }
    var offlineMapsLoaded by remember { mutableStateOf(false) }
    var offlineMapDiscoveryFinished by remember { mutableStateOf(false) }
    var offlineMapLoadingCount by remember { mutableIntStateOf(0) }

    suspend fun <T> withOfflineMapLoading(action: suspend () -> T): T {
        offlineMapLoadingCount += 1
        return try {
            action()
        } finally {
            offlineMapLoadingCount -= 1
        }
    }

    var hasSelectedMapFolder by remember(mapFolderSource) {
        mutableStateOf(mapFolderSource.hasSelectedFolder())
    }
    var hasSelectedGpxFolder by remember(gpxFolderSource) {
        mutableStateOf(gpxFolderSource.hasSelectedFolder())
    }
    var gpxFolderScan by remember { mutableStateOf(PhoneGpxFolderScanResult()) }
    var gpxFolderScanLoaded by remember { mutableStateOf(false) }
    var hasSelectedElevationFolder by remember(elevationFolderSource) {
        mutableStateOf(elevationFolderSource.hasSelectedFolder())
    }
    var elevationFolderSync by remember { mutableStateOf(PhoneElevationFolderSyncResult()) }
    var hasSelectedRoutingFolder by remember(routingFolderSource) {
        mutableStateOf(routingFolderSource.hasSelectedFolder())
    }
    var routingFolderSync by remember { mutableStateOf(PhoneRoutingFolderSyncResult()) }
    var routingDataVersion by remember { mutableStateOf(0L) }
    var offlineThemeConfig by remember(offlineThemePreferences) {
        mutableStateOf(offlineThemePreferences.load())
    }
    var mapSettings by remember(mapSettingsPreferences) {
        mutableStateOf(mapSettingsPreferences.load())
    }
    var mapToolbarHidden by rememberSaveable(mapSettings.tapToToggleToolbarEnabled) { mutableStateOf(false) }
    val mapToolbarVisible = !mapSettings.tapToToggleToolbarEnabled || !mapToolbarHidden
    var comparisonThemeConfig by remember(offlineThemePreferences) {
        mutableStateOf(offlineThemePreferences.loadComparison())
    }

    fun currentBaseThemeConfig(): PhoneOfflineThemeConfig =
        if (mapSettings.nightModeEnabled) {
            PhoneOfflineThemeCatalog.resolve(
                PhoneOfflineThemeCatalog.MAPSFORGE_THEME_ID,
                PhoneOfflineThemeCatalog.MAPSFORGE_DARK_STYLE_ID,
            )
        } else {
            offlineThemeConfig
        }
    val effectiveBaseThemeConfig = currentBaseThemeConfig()
    val changeBaseTheme: (PhoneOfflineThemeConfig) -> Unit = { config ->
        offlineThemeConfig = offlineThemePreferences.save(config)
        if (mapSettings.nightModeEnabled) {
            mapSettings = mapSettingsPreferences.save(mapSettings.copy(nightModeEnabled = false))
        }
    }
    var poiSettings by remember(poiSettingsPreferences) {
        mutableStateOf(poiSettingsPreferences.load())
    }
    var cameraState by rememberSaveable(stateSaver = phoneMapCameraStateSaver) {
        mutableStateOf(PhoneMapCameraState(defaultPhoneMapCamera))
    }
    val mapCamera = cameraState.camera
    var terrainPreview by remember { mutableStateOf<PhoneTerrainPreviewData?>(null) }
    val storageMigration = remember(context) { PhoneOfflineStorageMigration(context.applicationContext) }
    var storageMigrationState by remember(storageMigration) {
        mutableStateOf(
            storageMigration.pending()?.let { journal ->
                PhoneOfflineStorageMigrationState(
                    phase =
                        if (journal.phase == PhoneOfflineStorageMigrationPhase.COPYING ||
                            journal.phase == PhoneOfflineStorageMigrationPhase.VERIFYING
                        ) {
                            PhoneOfflineStorageMigrationPhase.CANCELLED
                        } else {
                            journal.phase
                        },
                    source = journal.source,
                    target = journal.target,
                    message =
                        migrationTextResources.getString(
                            R.string.storage_migration_a_previous_data_move_can_be_resumed,
                        ),
                )
            } ?: PhoneOfflineStorageMigrationState(),
        )
    }
    var terrainDataVersion by remember { mutableStateOf(0L) }
    var storageMigrationJob by remember(storageMigration) { mutableStateOf<Job?>(null) }
    var hasElevationData by remember { mutableStateOf(false) }
    var liveMapElevation by remember { mutableStateOf<Double?>(null) }
    var liveMapPosition by remember { mutableStateOf<PhoneMapLiveMetricsPosition?>(null) }
    var showOfflineThemeSelector by remember { mutableStateOf(false) }
    var showOfflineBundleDownload by rememberSaveable { mutableStateOf(false) }
    var elevationBundleAreaId by remember { mutableStateOf<String?>(null) }
    var offlineMapError by remember { mutableStateOf<PhoneOfflineMapError?>(null) }
    var missingStartupOfflineMap by rememberSaveable { mutableStateOf(false) }
    var mapLibreRenderErrors by remember { mutableStateOf(PhoneMapLibreRenderErrorState()) }
    var mapLocationMessage by remember { mutableStateOf<Int?>(null) }
    var hasLocationPermission by remember(context) { mutableStateOf(context.hasPhoneMapLocationPermission()) }
    var shouldCenterOnInitialLocation by rememberSaveable { mutableStateOf(true) }
    val completedBundle =
        (bundleUiState.download as? PhoneOfflineBundleDownloadState.Completed)?.bundle
    var hasFittedGpxOverlay by rememberSaveable { mutableStateOf(false) }
    var selectedPoi by remember { mutableStateOf<PhoneMapPoi?>(null) }
    var poiCreationActive by rememberSaveable { mutableStateOf(false) }
    var poiCreationPoint by remember { mutableStateOf<PhoneMapCoordinate?>(null) }
    var poiCreationDialogVisible by remember { mutableStateOf(false) }
    var poiNameInput by remember { mutableStateOf("") }
    var poiCreationBusy by remember { mutableStateOf(false) }
    var poiCreationError by remember { mutableStateOf(false) }
    var contentRenameTarget by remember { mutableStateOf<PhoneMapManagedContent?>(null) }
    var contentDeleteTarget by remember { mutableStateOf<PhoneMapManagedContent?>(null) }
    var contentNameInput by remember { mutableStateOf("") }
    var contentActionError by remember { mutableStateOf<String?>(null) }
    var contentActionBusy by remember { mutableStateOf(false) }
    var selectedGpxAnalysisId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGpxAnalysis = gpxUiState.items.firstOrNull { it.id == selectedGpxAnalysisId }
    var savedRouteAnalysisSelection by rememberSaveable(stateSaver = phoneMapRouteAnalysisSelectionSaver) {
        mutableStateOf<PhoneMapRouteAnalysisSavedSelection?>(null)
    }
    var routeAnalysis by remember { mutableStateOf<PhoneMapRouteAnalysis?>(null) }
    var routeAnalysisSelectingPointB by rememberSaveable { mutableStateOf(false) }
    var routeAnalysisTrackRevision by remember { mutableStateOf<PhoneMapRouteAnalysisTrackRevision?>(null) }
    var routeAnalysisRequestState by remember { mutableStateOf(PhoneMapRouteAnalysisRequestState()) }
    var routeAnalysisPendingRequest by remember { mutableStateOf<PhoneMapRouteAnalysisRequest?>(null) }
    var routeAnalysisJob by remember { mutableStateOf<Job?>(null) }
    val cancelRouteAnalysisJob = {
        routeAnalysisJob?.cancel()
        routeAnalysisJob = null
        routeAnalysisPendingRequest = null
    }
    val invalidateRouteAnalysisRequest = {
        cancelRouteAnalysisJob()
        routeAnalysisRequestState = routeAnalysisRequestState.invalidate()
    }
    val clearRouteAnalysis = {
        invalidateRouteAnalysisRequest()
        routeAnalysis = null
        savedRouteAnalysisSelection = null
        routeAnalysisTrackRevision = null
        routeAnalysisSelectingPointB = false
    }
    val pointSelectionMarkers =
        phoneMapPointSelectionMarkers(poiCreationPoint, routeToolsState) +
            phoneMapDistanceMeasurementMarkers(distanceMeasurement) +
            listOfNotNull(
                (
                    searchSelection?.point ?: discoverySearchPoint?.takeIf {
                        discoveryOpen && mapUiState.toolPanel.activeTool == MapTool.GPX
                    }
                )?.let {
                    PhoneMapPointSelectionMarker(PhoneMapPointSelectionMarkerKind.SEARCH, it)
                },
            ) +
            remember(hikeUi.model, hikeUi.plan, mapUiState.contentVisibility.gpxTracks, hikeTrackEnabled) {
                if (mapUiState.contentVisibility.gpxTracks &&
                    hikeTrackEnabled
                ) {
                    phoneHikeMapMarkers(hikeUi)
                } else {
                    emptyList()
                }
            }
    val routePointSelectionPhase = routeToolsState.pointSelectionPhase()
    val currentLocationAvailable = mapLocation != null && hasLocationPermission
    val routeCreationControlsVisible = routeToolsState.usesMapRouteCreationControls()
    val routeCreationPreviewPoints =
        routeToolsState.mapRouteCreationPoints(
            currentLocation =
                mapLocation?.let { location ->
                    GeoPoint(latitude = location.latitude, longitude = location.longitude)
                },
        )
    var routeCreationPreview by remember { mutableStateOf<PhoneRouteSelectionPreview?>(null) }
    LaunchedEffect(routeToolsState.mode, routeToolsState.chainPoints, gpxSettings, generalSettings) {
        routeToolsViewModel.previewMultiPointRoute(gpxSettings, generalSettings)
    }
    LaunchedEffect(
        routeCreationPreviewPoints,
        routeToolsState.mode,
        gpxSettings,
        generalSettings,
        terrainDataVersion,
    ) {
        routeCreationPreview =
            if (
                routeToolsState.mode == PhoneRouteCreationMode.MULTI_POINT_CHAIN ||
                routeCreationPreviewPoints.size < 2
            ) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    buildPhoneRouteSelectionPreview(
                        points = routeCreationPreviewPoints,
                        elevationsMeters =
                            routeCreationPreviewPoints.map { point ->
                                elevationRepository.elevationAt(point.latitude, point.longitude)
                            },
                        settings = gpxSettings,
                        generalSettings = generalSettings,
                    )
                }
            }
    }
    val recordingDisplayName = stringResource(R.string.phone_rec_activity_name)
    val savedGpxGeometry =
        remember(
            gpxUiState.items,
            mapUiState.contentVisibility.gpxTracks,
            recordingSettings.showSavedActivities,
        ) {
            gpxUiState.items.enabledOverlays(
                globalVisible = mapUiState.contentVisibility.gpxTracks,
                showSavedActivities = recordingSettings.showSavedActivities,
            )
        }
    val savedGpxOverlays =
        remember(savedGpxGeometry, gpxCustomization, gpxSettings, libraryOrganization) {
            savedGpxGeometry.withAppearance(gpxUiState.items, gpxCustomization, gpxSettings, libraryOrganization)
        }
    val discoveryOverlay =
        remember(discoveredPreview, discoveryOpen, mapUiState.toolPanel.activeTool) {
            val visible = discoveryOpen && mapUiState.toolPanel.activeTool == MapTool.GPX
            discoveredPreview?.takeIf { visible }?.let { trail ->
                PhoneMapGpxOverlay(
                    "discovery/${trail.id}",
                    trail.title,
                    PhoneMapGpxTrack(trail.id, trail.geometry).toRouteSegments(),
                )
            }
        }
    val savedGpxFitSegments =
        remember(savedGpxGeometry, discoveryOverlay) {
            discoveryOverlay?.segments ?: savedGpxGeometry.flatMap { it.segments }
        }
    val recordingOverlay =
        remember(recordingState.pointsRevision, recordingState.session.isActive, recordingDisplayName) {
            phoneRecordingOverlay(recordingState, recordingDisplayName)
        }
    val hikeOverlay =
        remember(hikeUi.model, hikeTrackEnabled) {
            hikeUi.model?.takeIf { hikeTrackEnabled }?.let { model ->
                PhoneMapGpxOverlay("hike/${model.route.id}", model.route.title, model.mapSegments)
            }
        }
    val gpxOverlays =
        remember(
            savedGpxOverlays,
            recordingOverlay,
            hikeOverlay,
            discoveryOverlay,
            mapUiState.contentVisibility.gpxTracks,
        ) {
            if (!mapUiState.contentVisibility.gpxTracks && discoveryOverlay != null) {
                listOf(discoveryOverlay)
            } else {
                savedGpxOverlays.filterNot { hikeOverlay != null && it.id == hikeTrackId } +
                    listOfNotNull(recordingOverlay, hikeOverlay, discoveryOverlay)
            }
        }
    val visibleMultiPointRoutePreview = routeToolsState.visibleMultiPointRoutePreview()
    val multiPointDraftConnectorPoints = routeToolsState.multiPointDraftConnectorPoints()
    val routePreviewName = stringResource(R.string.map_route_tools_route_preview)
    val routePreviewOverlay =
        remember(
            visibleMultiPointRoutePreview,
            multiPointDraftConnectorPoints,
            routePreviewName,
            gpxSettings,
        ) {
            val routeSegments =
                visibleMultiPointRoutePreview
                    ?.let { preview ->
                        PhoneMapGpxTrack(
                            id = PHONE_ROUTE_TOOLS_PREVIEW_OVERLAY_ID,
                            points = preview.points,
                        ).toRouteSegments()
                    }.orEmpty()
            val connectorSegment =
                multiPointDraftConnectorPoints
                    .takeIf { it.size >= 2 }
                    ?.let { points -> PhoneMapRouteSegment(points) }
            val segments = routeSegments + listOfNotNull(connectorSegment)
            if (segments.isNotEmpty()) {
                PhoneMapGpxOverlay(
                    id = PHONE_ROUTE_TOOLS_PREVIEW_OVERLAY_ID,
                    displayName = routePreviewName,
                    segments = segments,
                    settings = gpxSettings,
                )
            } else {
                null
            }
        }
    val mapGpxOverlays =
        remember(gpxOverlays, routePreviewOverlay) {
            gpxOverlays.filterNot { overlay -> overlay.id == PHONE_ROUTE_TOOLS_PREVIEW_OVERLAY_ID } +
                listOfNotNull(routePreviewOverlay)
        }
    val switchToOfflineMap: (PhoneOfflineMap) -> Unit = { selectedMap ->
        mapUiState = mapUiState.nextComparisonSelectionRequest()
        mapSourcePreference = mapSourcePreferences.saveOffline(selectedMap)
        missingStartupOfflineMap = false
        if (cameraState.publisher == PhoneMapCameraRenderer.BASE) {
            mapRuntime.map
                ?.cameraSnapshotOrNull(mapRuntime.mapView?.pixelRatio?.toDouble() ?: 1.0)
                ?.let { cameraState = PhoneMapCameraState(it) }
        }
        liveMapPosition = null
        mapRuntime = mapRuntime.invalidate()
        pendingOfflineMap = selectedMap
        offlineMapError = null
        mapLocationMessage = null
    }
    val activateOnlineMap: (PhoneOnlineMapSource, Boolean) -> Unit =
        activateOnlineMap@{ requestedSource, persistSelection ->
            val onlineSource =
                requestedSource.takeIf(PhoneMapRendererCatalog::isOnlineSourceAvailable)
                    ?: return@activateOnlineMap
            if (persistSelection) {
                mapSourcePreference = mapSourcePreferences.saveOnline(onlineSource)
            }
            missingStartupOfflineMap = false
            liveMapPosition = null
            pendingOfflineMap = null
            mapUiState =
                mapUiState
                    .nextComparisonSelectionRequest()
                    .copy(source = PhoneMapSource.Online)
                    .let { state ->
                        val comparisonLayer =
                            state.comparison.layer.takeUnless { layer ->
                                layer == PhoneMapComparisonLayer.Online(onlineSource)
                            }
                        if (comparisonLayer == state.comparison.layer) {
                            state
                        } else {
                            state.selectComparisonLayer(comparisonLayer)
                        }
                    }.clearUnavailableComparison(offlineMaps)
            offlineMapError = null
            mapLocationMessage = null
        }
    val selectOnlineMap: (PhoneOnlineMapSource) -> Unit = { requestedSource ->
        activateOnlineMap(requestedSource, true)
    }
    val switchToOnlineMap = {
        mapSourcePreference = mapSourcePreferences.restoreOnline()
        activateOnlineMap(selectedOnlineSource, false)
    }
    phoneMapCompassLifecycle(compassSensorSource, enabled = mapActive && !mapUiState.mapMode.isOffline)
    val guidanceNeedsLocation =
        guidanceState.session != null &&
            !guidanceState.paused &&
            guidanceState.guidance?.mode != TrailGuidanceMode.FINISHED
    val mapLocationProfile =
        selectPhoneMapLocationRequestProfile(
            mapActive = mapActive,
            onlineMap = !mapUiState.mapMode.isOffline,
            hasLocationPermission = hasLocationPermission,
            recordingServiceOwnsLocation =
                recordingState.session.acceptsFixes || recordingState.locationUpdatesActive || guidanceNeedsLocation,
            followMode = mapUiState.mapMode.follow,
        )
    phoneMapLocationLifecycle(
        source = mapLocationSource,
        profile = mapLocationProfile,
        hasLocationPermission = hasLocationPermission,
        onPermissionChanged = { hasLocationPermission = it },
    )
    LaunchedEffect(pendingOfflineMap) {
        val selectedMap = pendingOfflineMap ?: return@LaunchedEffect
        withFrameNanos { }
        if (pendingOfflineMap != selectedMap) return@LaunchedEffect
        mapUiState =
            mapUiState
                .changeBaseSource(PhoneMapSource.Offline(selectedMap))
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
    val currentMapLocation by rememberUpdatedState(mapLocation)
    val currentOfflineMaps by rememberUpdatedState(offlineMaps)
    LaunchedEffect(
        gpxUiState.items,
        mapUiState.contentVisibility.gpxTracks,
        gpxSettings.inspectionEnabled,
        routeToolsState.isOpen,
        poiCreationActive,
        poiCreationPoint != null,
    ) {
        val currentItems = gpxUiState.items
        val routeAnalysisAvailable =
            isPhoneMapRouteAnalysisAvailable(
                inspectionEnabled = gpxSettings.inspectionEnabled,
                gpxTracksVisible = mapUiState.contentVisibility.gpxTracks,
                routeToolsOpen = routeToolsState.isOpen,
                poiCreationActive = poiCreationActive,
                poiCreationPointSet = poiCreationPoint != null,
            )
        val pendingRequest = routeAnalysisPendingRequest
        if (
            pendingRequest != null &&
            (
                !routeAnalysisAvailable ||
                    !pendingRequest.hasSameCandidateTracks(currentItems)
            )
        ) {
            invalidateRouteAnalysisRequest()
        }
        if (
            routeAnalysis != null &&
            (
                !gpxSettings.inspectionEnabled ||
                    routeAnalysisTrackRevision?.isAvailableIn(
                        currentItems,
                        mapUiState.contentVisibility.gpxTracks,
                    ) != true
            )
        ) {
            clearRouteAnalysis()
        }
    }
    val gpxSourcesReady =
        !gpxSourcesLoading &&
            gpxFolderScanLoaded &&
            !gpxUiState.isLoading &&
            gpxViewModel.loadedSources == (gpxSources to gpxFolderScan.files)
    LaunchedEffect(selectedGpxAnalysisId, gpxUiState.items, gpxSourcesReady) {
        if (gpxSourcesReady && selectedGpxAnalysisId != null && selectedGpxAnalysis == null) {
            selectedGpxAnalysisId = null
        }
    }
    LaunchedEffect(
        savedRouteAnalysisSelection,
        gpxUiState.items,
        gpxSourcesReady,
        routeAnalysisRequestState.generation,
        routeAnalysisPendingRequest != null,
        gpxSettings,
        generalSettings,
        mapUiState.contentVisibility.gpxTracks,
    ) {
        val selection = savedRouteAnalysisSelection ?: return@LaunchedEffect
        if (!gpxSettings.inspectionEnabled || !mapUiState.contentVisibility.gpxTracks) {
            clearRouteAnalysis()
            return@LaunchedEffect
        }
        if (!gpxSourcesReady || routeAnalysis != null || routeAnalysisPendingRequest != null) return@LaunchedEffect
        val generation = routeAnalysisRequestState.generation
        val itemsSnapshot = gpxUiState.items
        val settingsSnapshot = gpxSettings
        val generalSettingsSnapshot = generalSettings
        val restored =
            withContext(Dispatchers.Default) {
                restorePhoneMapRouteAnalysis(selection, itemsSnapshot, settingsSnapshot, generalSettingsSnapshot)
            }
        val settingsStillCurrent = settingsSnapshot == gpxSettings && generalSettingsSnapshot == generalSettings
        val tracksStillCurrent =
            gpxViewModel.loadedSources == (gpxSources to gpxFolderScan.files) &&
                gpxViewModel.uiState.value.items === itemsSnapshot
        if (!phoneMapRouteAnalysisRestoreIsCurrent(
                selection,
                savedRouteAnalysisSelection,
                generation,
                routeAnalysisRequestState,
            ) ||
            !settingsStillCurrent ||
            !tracksStillCurrent
        ) {
            return@LaunchedEffect
        }
        if (restored == null) {
            clearRouteAnalysis()
        } else if (restored.trackRevision.isAvailableIn(
                gpxViewModel.uiState.value.items,
                mapUiState.contentVisibility.gpxTracks,
            )
        ) {
            routeAnalysis = restored.analysis
            routeAnalysisTrackRevision = restored.trackRevision
        }
    }
    LaunchedEffect(
        mapActive,
        mapSettings.liveElevationEnabled,
        mapSettings.onlineElevationEnabled,
        mapUiState.mapMode.isDetachedFromLocation,
        terrainDataVersion,
    ) {
        if (!mapActive) return@LaunchedEffect
        if (!mapSettings.liveElevationEnabled || !mapUiState.mapMode.isDetachedFromLocation) {
            liveMapElevation = null
            return@LaunchedEffect
        }

        var lastElevationSampleTarget: PhoneMapCoordinate? = null
        var onlineElevationJob: Job? = null
        var lastDemElevation: Double? = null
        while (isActive) {
            val target = currentLiveMapPosition?.target
            if (target == null) {
                onlineElevationJob?.cancel()
                liveMapElevation = null
                lastElevationSampleTarget = null
                lastDemElevation = null
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
                onlineElevationJob?.cancel()
                liveMapElevation = null
                val sampledElevation =
                    withContext(Dispatchers.IO) {
                        elevationRepository.elevationAt(target.latitude, target.longitude)
                    }
                lastDemElevation = sampledElevation
                if (phoneMapElevationTargetIsCurrent(target, currentLiveMapPosition?.target)) {
                    liveMapElevation = phoneMapLiveElevation(sampledElevation, target, currentMapLocation)
                }
                lastElevationSampleTarget = target
                if (sampledElevation == null && mapSettings.onlineElevationEnabled) {
                    onlineElevationJob =
                        launch {
                            val onlineElevation =
                                phoneMapElevationAt(target, elevationRepository, onlineElevationRepository)
                            if (phoneMapElevationTargetIsCurrent(target, currentLiveMapPosition?.target)) {
                                lastDemElevation = onlineElevation
                                liveMapElevation = phoneMapLiveElevation(onlineElevation, target, currentMapLocation)
                            }
                            if (onlineElevation == null) {
                                delay(PHONE_ONLINE_DEM_FAILURE_RETRY_MS)
                                if (phoneMapElevationTargetIsCurrent(target, currentLiveMapPosition?.target)) {
                                    lastElevationSampleTarget = null
                                }
                            }
                        }
                }
            }
            if (phoneMapElevationTargetIsCurrent(lastElevationSampleTarget ?: target, currentLiveMapPosition?.target)) {
                liveMapElevation = phoneMapLiveElevation(lastDemElevation, target, currentMapLocation)
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
            hasPermission = hasLocationPermission && !mapUiState.mapMode.isOffline,
            location = mapLocation,
        )
    val gpxOverlayState =
        GpxOverlayState(
            overlays = mapGpxOverlays,
            segments = mapGpxOverlays.flatMap(PhoneMapGpxOverlay::segments),
            isVisible =
                mapUiState.contentVisibility.gpxTracks || discoveryOverlay != null || routePreviewOverlay != null,
            settings = gpxSettings,
        )
    val selectLocalMapLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                withOfflineMapLoading {
                    val imported =
                        withContext(Dispatchers.IO) {
                            offlineMapStore.import(context.contentResolver, uri)
                        }
                    when (imported) {
                        is PhoneOfflineMapImportResult.Success -> {
                            val importedMap = imported.map
                            offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                            switchToOfflineMap(importedMap)
                        }

                        is PhoneOfflineMapImportResult.Failure -> {
                            offlineMapError = imported.error
                        }
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
    val selectElevationFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            if (treeUri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val selectionError =
                    withContext(Dispatchers.IO) { elevationFolderSource.selectFolder(treeUri) }
                if (selectionError != null) {
                    elevationFolderSync = PhoneElevationFolderSyncResult(error = selectionError)
                    return@launch
                }
                elevationFolderSync = withContext(Dispatchers.IO) { elevationFolderSource.syncSelectedFolder() }
                hasSelectedElevationFolder = elevationFolderSource.hasSelectedFolder()
                hasElevationData = withContext(Dispatchers.IO) { elevationStore.hasData() }
                terrainDataVersion += 1L
                elevationRepository.invalidate()
            }
        }
    val selectRoutingFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            if (treeUri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val selectionError =
                    withContext(Dispatchers.IO) { routingFolderSource.selectFolder(treeUri) }
                if (selectionError != null) {
                    routingFolderSync = PhoneRoutingFolderSyncResult(error = selectionError)
                    return@launch
                }
                routingFolderSync = withContext(Dispatchers.IO) { routingFolderSource.syncSelectedFolder() }
                routingDataVersion += 1L
                hasSelectedRoutingFolder = routingFolderSource.hasSelectedFolder()
            }
        }
    val selectMapFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            if (treeUri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                withOfflineMapLoading {
                    val selectionError =
                        withContext(Dispatchers.IO) { mapFolderSource.selectFolder(treeUri) }
                    if (selectionError != null) {
                        offlineMapError = selectionError
                        return@withOfflineMapLoading
                    }
                    val syncResult = withContext(Dispatchers.IO) { mapFolderSource.syncSelectedFolder() }
                    offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                    hasSelectedMapFolder = mapFolderSource.hasSelectedFolder()
                    offlineMapError = syncResult.error
                }
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
            if (discoveryNearMePending && !hasLocationPermission) {
                discoveryNearMePending = false
                mapLocationMessage = R.string.discovery_location_permission
            }
        }

    LaunchedEffect(hasLocationPermission, mapLocation) {
        if (!shouldCenterOnInitialLocation || !hasLocationPermission || mapLocation == null) {
            return@LaunchedEffect
        }
        shouldCenterOnInitialLocation = false
        mapUiState = mapUiState.copy(mapMode = mapUiState.mapMode.recenterOnLocation())
    }

    LaunchedEffect(offlineMapStore, mapFolderSource) {
        val startupPreference = mapSourcePreference
        val startupSource = mapUiState.source
        var startupSelectionRequested = false
        val startupSelectionIsCurrent = {
            mapSourcePreference == startupPreference &&
                mapUiState.source == startupSource &&
                pendingOfflineMap == null
        }
        try {
            loadPhoneOfflineMapsOnStartup(
                discover = {
                    withOfflineMapLoading {
                        withContext(Dispatchers.IO) { offlineMapStore.discover() }
                    }
                },
                synchronizeFolder = { withContext(Dispatchers.IO) { mapFolderSource.syncSelectedFolder() } },
                onDiscovered = { installedMaps ->
                    offlineMaps = installedMaps
                    offlineMapDiscoveryFinished = true
                    if (startupSelectionIsCurrent()) {
                        val selection =
                            resolvePhoneMapOfflineStartupSelection(startupPreference, startupSource, installedMaps)
                        val comparison = mapUiState.comparison.layer as? PhoneMapComparisonLayer.Offline
                        val comparisonAvailable =
                            comparison == null ||
                                installedMaps.any { it.selectionIdentity == comparison.map.selectionIdentity }
                        if (selection.map != null && comparisonAvailable) {
                            startupSelectionRequested = true
                            switchToOfflineMap(selection.map)
                        }
                    }
                },
                onSynchronized = { syncResult, discoveredMaps ->
                    offlineMaps = discoveredMaps
                    hasSelectedMapFolder = mapFolderSource.hasSelectedFolder()
                    offlineMapError = syncResult.error
                    if (!startupSelectionRequested && startupSelectionIsCurrent()) {
                        val selection =
                            resolvePhoneMapOfflineStartupSelection(startupPreference, startupSource, discoveredMaps)
                        if (selection.map != null) switchToOfflineMap(selection.map)
                        if (selection.missingMap) {
                            activateOnlineMap(selectedOnlineSource, false)
                            missingStartupOfflineMap = true
                            offlineMapError = PhoneOfflineMapError.MISSING
                        }
                    }
                    mapUiState = mapUiState.refreshOfflineMapSelections(offlineMaps)
                },
                onFinished = {
                    offlineMapDiscoveryFinished = true
                    offlineMapsLoaded = true
                },
            )
        } catch (error: java.io.IOException) {
            PhoneDebugCapture.log(
                "PhoneOfflineMapImport",
                "event=offline_map_startup_failed exception=${error.javaClass.simpleName}",
            )
            offlineMapError = PhoneOfflineMapError.FILE_NOT_READABLE
        } catch (error: SecurityException) {
            PhoneDebugCapture.log(
                "PhoneOfflineMapImport",
                "event=offline_map_startup_failed exception=${error.javaClass.simpleName}",
            )
            offlineMapError = PhoneOfflineMapError.FOLDER_PERMISSION_LOST
        }
    }
    LaunchedEffect(offlineMaps, offlineMapsLoaded) {
        mapUiState = mapUiState.refreshOfflineMapSelections(offlineMaps.takeIf { offlineMapsLoaded })
        pendingOfflineMap = pendingOfflineMap?.refreshedFrom(offlineMaps)
    }
    LaunchedEffect(gpxFolderSource) {
        gpxFolderScan = withContext(Dispatchers.IO) { gpxFolderSource.scanSelectedFolder() }
        hasSelectedGpxFolder = gpxFolderSource.hasSelectedFolder()
        gpxFolderScanLoaded = true
    }
    LaunchedEffect(elevationFolderSource) {
        elevationFolderSync = withContext(Dispatchers.IO) { elevationFolderSource.syncSelectedFolder() }
        hasSelectedElevationFolder = elevationFolderSource.hasSelectedFolder()
        hasElevationData = withContext(Dispatchers.IO) { elevationStore.hasData() }
        terrainDataVersion += 1L
        elevationRepository.invalidate()
    }
    LaunchedEffect(routingFolderSource) {
        routingFolderSync = withContext(Dispatchers.IO) { routingFolderSource.syncSelectedFolder() }
        routingDataVersion += 1L
        hasSelectedRoutingFolder = routingFolderSource.hasSelectedFolder()
    }
    LaunchedEffect(mapUiState.contentVisibility.pois) {
        onPoiVisibilityChanged(mapUiState.contentVisibility.pois)
        if (!mapUiState.contentVisibility.pois) selectedPoi = null
    }
    LaunchedEffect(pois) {
        if (selectedPoi?.id !in pois.map(PhoneMapPoi::id).toSet()) selectedPoi = null
    }
    LaunchedEffect(pois.size, captureSessionId, captureActive) {
        if (captureActive) {
            PhoneDebugCapture.log(
                PHONE_MAP_FEATURE_DIAGNOSTICS_TAG,
                "event=poi_result_state results=${pois.size}",
            )
        }
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
    LaunchedEffect(poiCreationPoint) {
        val selectedPoint = poiCreationPoint ?: return@LaunchedEffect
        if (!poiCreationDialogVisible) {
            delay(PHONE_POI_SELECTION_PREVIEW_DELAY_MS)
            if (poiCreationPoint == selectedPoint) poiCreationDialogVisible = true
        }
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
    LaunchedEffect(mapActive, gpxSources, gpxFolderScan.files, initiallyEnabledGpxId, gpxSourcesLoading, gpxFolderScanLoaded) {
        if (mapActive && !gpxSourcesLoading && gpxFolderScanLoaded) {
            gpxViewModel.synchronize(gpxSources, gpxFolderScan.files, initiallyEnabledGpxId)
        }
    }
    // A previous map session's saved route must never be attached to a newly requested mission day.
    val initialPlannedSavedRouteId = remember(onPlannedRouteFinished != null) { routeToolsState.savedRouteId }
    LaunchedEffect(routeToolsState.savedRouteId) {
        val isPreviousPlannedResult =
            onPlannedRouteFinished != null && routeToolsState.savedRouteId == initialPlannedSavedRouteId
        if (routeToolsState.savedRouteId != null && !isPreviousPlannedResult) {
            onRouteSaved()
            onPlannedRouteFinished?.invoke(routeToolsState.savedRouteId)
            if (onPlannedRouteFinished != null) routeToolsViewModel.cancel()
            if (mapUiState.toolPanel.activeTool == MapTool.GPX_TOOLS) mapUiState = mapUiState.closeTool()
        }
    }
    LaunchedEffect(mapUiState.toolPanel.activeTool) {
        if (mapUiState.toolPanel.activeTool == MapTool.GPX_TOOLS) {
            if (!routeToolsViewModel.uiState.value.isOpen) routeToolsViewModel.open(gpxUiState.items)
        } else if (routeToolsViewModel.uiState.value.isOpen) {
            routeToolsViewModel.cancel()
            onPlannedRouteFinished?.invoke(null)
        }
    }
    LaunchedEffect(routePointSelectionPhase) {
        if (routePointSelectionPhase != null && mapUiState.toolPanel.activeTool == MapTool.GPX_TOOLS) {
            mapUiState = mapUiState.collapseTool()
        }
    }
    LaunchedEffect(mapSettings.distanceMeasurementEnabled) {
        if (!mapSettings.distanceMeasurementEnabled) distanceMeasurement = null
    }
    LaunchedEffect(mapActive, mapUiState.mapMode, mapSettings.autoRecenterEnabled, mapSettings.autoRecenterDelaySeconds) {
        if (!mapActive) return@LaunchedEffect
        if (!mapSettings.autoRecenterEnabled || !mapUiState.mapMode.canAutoRecenter) return@LaunchedEffect
        delay(mapSettings.autoRecenterDelaySeconds * 1_000L)
        if (mapUiState.mapMode.canAutoRecenter && mapLocation != null) {
            mapUiState = mapUiState.copy(mapMode = mapUiState.mapMode.recenterOnLocation())
            mapLocationMessage = null
        }
    }

    val onMapModePressed = {
        if (mapUiState.mapMode.isOffline) {
            mapUiState = mapUiState.toggleMapOrientation()
            cameraState = cameraState.copy(camera = cameraState.camera.forMapMode(mapUiState.mapMode))
        } else if (mapUiState.mapMode.isDetachedFromLocation) {
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

    val onToggleOfflineMode = {
        val mode = mapUiState.mapMode.toggleOfflineMode()
        shouldCenterOnInitialLocation = false
        mapUiState = mapUiState.copy(mapMode = mode)
        cameraState = cameraState.copy(camera = cameraState.camera.forMapMode(mode))
        mapLocationMessage = null
        if (!mode.isOffline && !hasLocationPermission) locationPermissionLauncher.launch(phoneMapLocationPermissions)
    }

    val onStorageChangeRequested: (PhoneOfflineStorageLocation) -> Unit = onStorageChangeRequested@{ target ->
        val current = offlineStorage.location()
        val pendingMigration = storageMigration.pending()
        val migrationInProgress = storageMigrationJob?.isCompleted == false
        if (migrationInProgress || storageChangeIsNoOp(target, current, offlineStorage, pendingMigration)) {
            return@onStorageChangeRequested
        }
        val selectedOfflineMapName = (mapUiState.source as? PhoneMapSource.Offline)?.map?.displayName
        storageMigrationJob =
            coroutineScope.launch {
                try {
                    storageMigrationState =
                        PhoneOfflineStorageMigrationState(
                            phase = PhoneOfflineStorageMigrationPhase.COPYING,
                            source = current,
                            target = target,
                            canCancel =
                                pendingMigration == null ||
                                    pendingMigration.phase == PhoneOfflineStorageMigrationPhase.COPYING ||
                                    pendingMigration.phase == PhoneOfflineStorageMigrationPhase.VERIFYING,
                            message = migrationTextResources.getString(R.string.map_tools_settings_storage_checking),
                        )
                    bundleViewModel.cancel()
                    fileTransferViewModel.cancelTransfer()
                    fileTransferViewModel.cancelPoiImport(context)
                    fileTransferViewModel.cancelRoutingDownload(context)
                    if (selectedOfflineMapName != null) {
                        mapUiState = mapUiState.changeBaseSource(PhoneMapSource.Online)
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
                                message =
                                    migrationTextResources.getString(
                                        R.string.storage_migration_active_data_operations_did_not_stop_in,
                                    ),
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
                                        copiedBytes = progress.copiedBytes,
                                        totalBytes = progress.totalBytes,
                                        canCancel =
                                            progress.phase == PhoneOfflineStorageMigrationPhase.COPYING ||
                                                progress.phase == PhoneOfflineStorageMigrationPhase.VERIFYING,
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
                                    copiedFiles = result.copiedFiles,
                                    totalFiles = result.movedFiles,
                                    reusedFiles = result.reusedFiles,
                                    replacedFiles = result.replacedFiles,
                                )
                            bundleViewModel.refreshInstalledBundles()
                            elevationFolderSync =
                                withContext(Dispatchers.IO) { elevationFolderSource.syncSelectedFolder() }
                            hasSelectedElevationFolder = elevationFolderSource.hasSelectedFolder()
                            routingFolderSync = withContext(Dispatchers.IO) { routingFolderSource.syncSelectedFolder() }
                            hasSelectedRoutingFolder = routingFolderSource.hasSelectedFolder()
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
                } catch (cancelled: CancellationException) {
                    if (!coroutineScope.isActive) throw cancelled
                    storageMigrationState =
                        storageMigrationState.copy(
                            phase = PhoneOfflineStorageMigrationPhase.CANCELLED,
                            canCancel = false,
                            message = migrationTextResources.getString(R.string.storage_migration_cancelled_resume),
                        )
                    selectedOfflineMapName
                        ?.let { name -> offlineMaps.firstOrNull { map -> map.displayName == name } }
                        ?.let { originalMap -> switchToOfflineMap(originalMap) }
                } finally {
                    storageMigrationJob = null
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
                    when (val comparisonLayer = mapUiState.comparison.layer) {
                        is PhoneMapComparisonLayer.Offline -> {
                            if (comparisonLayer.map.selectionIdentity == target.map.selectionIdentity) {
                                mapUiState.selectComparisonLayer(PhoneMapComparisonLayer.Offline(renamed))
                            } else {
                                mapUiState
                            }
                        }
                        else -> mapUiState
                    }
                val selectedMapIdentity = (mapUiState.source as? PhoneMapSource.Offline)?.map?.selectionIdentity
                if (selectedMapIdentity == target.map.selectionIdentity) {
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
                val selectedMapIdentity = (mapUiState.source as? PhoneMapSource.Offline)?.map?.selectionIdentity
                if (selectedMapIdentity == target.map.selectionIdentity) {
                    switchToOnlineMap()
                }
                withContext(Dispatchers.IO) { offlineMapStore.delete(target.map) }
                mapSourcePreference = mapSourcePreferences.forgetOfflineMapName(target.map.displayName)
                offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                mapUiState = mapUiState.refreshOfflineMapSelections(offlineMaps)
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

    val onlineMapTiltDegrees =
        phoneMapTiltDegrees(mapSettings.tiltedViewEnabled, mapUiState.source, mapUiState.comparison.layer)
    val toolsState =
        MapToolsPanelState(
            maps =
                MapToolsMapsState(
                    source = mapUiState.source,
                    onlineSources =
                        PhoneMapRendererCatalog.availableOnlineSources(
                            mapSettings.hiddenOnlineSources,
                            mapSettings.onlineSourceOrder,
                        ),
                    selectedOnlineSource = selectedOnlineSource,
                    offlineMaps = offlineMaps,
                    isLoading = !offlineMapDiscoveryFinished || offlineMapLoadingCount > 0,
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
                    hasSelectedElevationFolder = hasSelectedElevationFolder,
                    elevationFolderSync = elevationFolderSync,
                    hasSelectedRoutingFolder = hasSelectedRoutingFolder,
                    routingFolderSync = routingFolderSync,
                    offlineDataVersion = terrainDataVersion + routingDataVersion,
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
                    customization = gpxCustomization,
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
                    onlineSources =
                        PhoneMapRendererCatalog.availableComparisonOnlineSources(
                            mapSettings.hiddenOnlineSources,
                            mapSettings.onlineSourceOrder,
                        ),
                    comparison = mapUiState.comparison,
                    baseThemeConfig = effectiveBaseThemeConfig,
                    comparisonThemeConfig = comparisonThemeConfig,
                    hasElevationData = hasElevationData,
                    mapSettings = mapSettings,
                ),
            general =
                MapToolsGeneralState(
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
            onSwitchToOnline = switchToOnlineMap,
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
            onDownloadElevationForBundleArea = { areaId ->
                elevationBundleAreaId = areaId
                showOfflineBundleDownload = true
            },
            onImportMap = { selectLocalMapLauncher.launch(arrayOf("application/octet-stream")) },
            onImportElevation = {
                selectElevationLauncher.launch(
                    arrayOf("application/octet-stream", "application/gzip", "application/zip"),
                )
            },
            onOfflineDataChanged = {
                coroutineScope.launch {
                    hasElevationData = withContext(Dispatchers.IO) { elevationStore.hasData() }
                    terrainDataVersion += 1L
                    elevationRepository.invalidate()
                }
            },
            onSelectElevationFolder = { selectElevationFolderLauncher.launch(null) },
            onRescanElevationFolder = {
                coroutineScope.launch {
                    elevationFolderSync =
                        withContext(Dispatchers.IO) { elevationFolderSource.syncSelectedFolder() }
                    hasSelectedElevationFolder = elevationFolderSource.hasSelectedFolder()
                    hasElevationData = withContext(Dispatchers.IO) { elevationStore.hasData() }
                    terrainDataVersion += 1L
                    elevationRepository.invalidate()
                }
            },
            onClearElevationFolder = {
                elevationFolderSource.clearSelectedFolder()
                hasSelectedElevationFolder = false
                elevationFolderSync = PhoneElevationFolderSyncResult()
            },
            onSelectRoutingFolder = { selectRoutingFolderLauncher.launch(null) },
            onRescanRoutingFolder = {
                coroutineScope.launch {
                    routingFolderSync =
                        withContext(Dispatchers.IO) { routingFolderSource.syncSelectedFolder() }
                    routingDataVersion += 1L
                    hasSelectedRoutingFolder = routingFolderSource.hasSelectedFolder()
                }
            },
            onClearRoutingFolder = {
                routingFolderSource.clearSelectedFolder()
                hasSelectedRoutingFolder = false
                routingFolderSync = PhoneRoutingFolderSyncResult()
            },
            onSelectFolder = { selectMapFolderLauncher.launch(null) },
            onRescanFolder = {
                coroutineScope.launch {
                    withOfflineMapLoading {
                        val syncResult = withContext(Dispatchers.IO) { mapFolderSource.syncSelectedFolder() }
                        offlineMaps = withContext(Dispatchers.IO) { offlineMapStore.discover() }
                        hasSelectedMapFolder = mapFolderSource.hasSelectedFolder()
                        offlineMapError = syncResult.error
                    }
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

    val mapPointerCoordinate =
        liveMapPosition?.target
            ?: PhoneMapCoordinate(
                latitude = mapCamera.latitude,
                longitude = mapCamera.longitude,
            )
    val onSelectRoutePointAtCenter = {
        routeToolsViewModel.selectMapPoint(
            GeoPoint(
                latitude = mapPointerCoordinate.latitude,
                longitude = mapPointerCoordinate.longitude,
            ),
        )
    }
    val onOpenRouteToolsSettings = {
        routeToolsViewModel.cancel()
        mapUiState =
            mapUiState
                .closeTool()
                .copy(toolLauncherExpanded = false)
                .selectTool(MapTool.GPX)
                .showFeatureSettings()
    }
    val routeToolsActions =
        PhoneRouteToolsActions(
            onChooseToolKind = routeToolsViewModel::chooseToolKind,
            onChooseMode = routeToolsViewModel::chooseMode,
            onSelectModificationMode = routeToolsViewModel::chooseModificationMode,
            onSelectRoute = routeToolsViewModel::selectRoute,
            onCoordinatesChanged = routeToolsViewModel::updateCoordinates,
            onResetMapPoints = routeToolsViewModel::resetMapPoints,
            onCreate = { routeToolsViewModel.create(mapLocation) },
            onOpenSettings = onOpenRouteToolsSettings,
            onPlanAcrossDays =
                onPlanAcrossDays?.let { openPlanner ->
                    {
                        val routeId = routeToolsState.selectedRouteId
                        routeToolsViewModel.cancel()
                        openPlanner(routeId)
                    }
                },
            onDismiss = {
                routeToolsViewModel.cancel()
                onPlannedRouteFinished?.invoke(null)
                if (mapUiState.toolPanel.activeTool == MapTool.GPX_TOOLS) mapUiState = mapUiState.closeTool()
            },
        )
    val onOpenRouteTools = {
        clearRouteAnalysis()
        poiCreationActive = false
        poiCreationPoint = null
        poiCreationDialogVisible = false
        routeToolsViewModel.open(gpxUiState.items)
        mapUiState = mapUiState.openToolPage(MapTool.GPX_TOOLS, featureSettings = false)
    }
    val onAddPoi = {
        clearRouteAnalysis()
        routeToolsViewModel.cancel()
        selectedPoi = null
        searchOpen = false
        searchViewModel.controller.cancel()
        searchSelection = null
        distanceMeasurement = null
        discoveryPickingPoint = false
        discoveryNearMePending = false
        hikeController?.cancelCheckpoint()
        poiCreationActive = false
        poiCreationPoint = null
        poiCreationDialogVisible = true
        poiNameInput = ""
        poiCreationError = false
        mapUiState = mapUiState.closeTool().copy(toolLauncherExpanded = false)
    }
    val onDistanceMeasurementGesture: (PhoneMapCoordinate, PhoneMapCoordinate) -> Unit = { first, second ->
        if (mapSettings.distanceMeasurementEnabled) {
            distanceMeasurement = PhoneMapDistanceMeasurement(first, second)
        }
    }
    val onDistanceMeasurementPointMoved: (Int, PhoneMapCoordinate) -> Unit = { index, point ->
        if (mapSettings.distanceMeasurementEnabled) {
            distanceMeasurement = distanceMeasurement?.moveEndpoint(index, point)
        }
    }
    val onSelectPoiPoint: (PhoneMapCoordinate) -> Unit = { point ->
        poiCreationActive = false
        poiCreationPoint = point
        poiCreationDialogVisible = false
        poiCreationError = false
    }
    val onSelectDiscoveryPoint: (PhoneMapCoordinate) -> Unit = { point ->
        discoveryPickingPoint = false
        discoveryNearMePending = false
        discoverySearchPoint = point
        searchSelection = null
        selectedPoi = null
        shouldCenterOnInitialLocation = false
        hasFittedGpxOverlay = true
        discoveryViewModel.controller.submit(
            TrailDiscoveryQuery(GeoPoint(point.latitude, point.longitude)),
            discoveryViewModel.settings.value.enabled,
        )
        mapUiState = mapUiState.requestSearchTarget(point, mapCamera.bearingDegrees)
    }
    LaunchedEffect(discoveryNearMePending, hasLocationPermission, mapLocation) {
        if (discoveryNearMePending && hasLocationPermission) {
            val nearby = discoveryCurrentLocationQuery(mapLocation, android.os.SystemClock.elapsedRealtime())
            if (nearby != null) {
                mapLocationMessage = null
                onSelectDiscoveryPoint(PhoneMapCoordinate(nearby.center.latitude, nearby.center.longitude))
            }
        }
    }
    val onMapTap: (PhoneMapCoordinate) -> Unit = { point ->
        if (hikeUi.pickingCheckpoint) {
            hikeController?.checkpointAt(GeoPoint(point.latitude, point.longitude))
        } else if (discoveryPickingPoint) {
            onSelectDiscoveryPoint(point)
        } else if (distanceMeasurement != null) {
            distanceMeasurement = null
        } else if (poiCreationActive) {
            onSelectPoiPoint(point)
        } else if (routeToolsState.isOpen) {
            routeToolsViewModel.selectMapPoint(
                GeoPoint(latitude = point.latitude, longitude = point.longitude),
            )
        } else if (mapSettings.tapToToggleToolbarEnabled && !searchOpen) {
            mapToolbarHidden = !mapToolbarHidden
            if (mapToolbarHidden) mapUiState = mapUiState.copy(toolLauncherExpanded = false)
        }
    }
    val onHikeAwarePoiSelected: (PhoneMapPoi) -> Unit = { poi ->
        if (hikeUi.pickingCheckpoint) {
            hikeController?.checkpointAt(
                poi.location,
                poi.label.orEmpty(),
                "poi/${poi.sourceId}/${poi.id}",
                requireOnRoute = false,
            )
        } else if (discoveryPickingPoint) {
            onSelectDiscoveryPoint(PhoneMapCoordinate(poi.location.latitude, poi.location.longitude))
        } else if (poiCreationActive) {
            onSelectPoiPoint(PhoneMapCoordinate(poi.location.latitude, poi.location.longitude))
        } else {
            selectedPoi = poi
        }
    }
    val onMapLongPress: (PhoneMapCoordinate) -> Unit = fun(point) {
        if (hikeUi.pickingCheckpoint || discoveryPickingPoint) {
            if (hikeUi.pickingCheckpoint) {
                hikeController?.checkpointAt(GeoPoint(point.latitude, point.longitude))
            } else {
                onSelectDiscoveryPoint(point)
            }
            return
        }
        val canAnalyzeRoute =
            isPhoneMapRouteAnalysisAvailable(
                inspectionEnabled = gpxSettings.inspectionEnabled,
                gpxTracksVisible = mapUiState.contentVisibility.gpxTracks,
                routeToolsOpen = routeToolsState.isOpen,
                poiCreationActive = poiCreationActive,
                poiCreationPointSet = poiCreationPoint != null,
            )
        if (canAnalyzeRoute) {
            val phase =
                if (routeAnalysisSelectingPointB) {
                    PhoneMapRouteAnalysisSelectionPhase.POINT_B
                } else {
                    PhoneMapRouteAnalysisSelectionPhase.POINT_A
                }
            val currentAnalysis = routeAnalysis
            val currentTrackRevision = routeAnalysisTrackRevision
            val enabledItems =
                gpxViewModel.uiState.value.items
                    .filter(PhoneMapGpxItem::enabled)
            val candidateItems =
                if (phase == PhoneMapRouteAnalysisSelectionPhase.POINT_B) {
                    enabledItems.filter { item ->
                        item.id == currentAnalysis?.trackId &&
                            currentTrackRevision?.isAvailableIn(listOf(item), gpxTracksVisible = true) == true
                    }
                } else {
                    enabledItems
                }
            if (candidateItems.isEmpty()) {
                if (phase == PhoneMapRouteAnalysisSelectionPhase.POINT_B) {
                    clearRouteAnalysis()
                } else {
                    invalidateRouteAnalysisRequest()
                }
                return
            }
            cancelRouteAnalysisJob()
            val settingsSnapshot = gpxSettings
            val generalSettingsSnapshot = generalSettings
            val (requestState, request) =
                routeAnalysisRequestState.begin(
                    PhoneMapRouteAnalysisRequestInput(
                        phase = phase,
                        press = point,
                        candidateItems = candidateItems,
                        currentAnalysis = currentAnalysis,
                        currentTrackRevision = currentTrackRevision,
                        settings = settingsSnapshot,
                        generalSettings = generalSettingsSnapshot,
                    ),
                )
            routeAnalysisRequestState = requestState
            routeAnalysisPendingRequest = request
            val startedAtNanos = System.nanoTime()
            PhoneDebugCapture.log(
                PHONE_MAP_FEATURE_DIAGNOSTICS_TAG,
                phoneMapRouteAnalysisDiagnosticLine(
                    PhoneMapRouteAnalysisDiagnosticOutcome.STARTED,
                    request,
                    durationMillis = 0L,
                ),
            )
            val job =
                coroutineScope.launch {
                    try {
                        val result =
                            runCatching {
                                withContext(Dispatchers.Default) { computePhoneMapRouteAnalysisRequest(request) }
                            }.getOrElse { failure ->
                                val outcome =
                                    if (failure is CancellationException) {
                                        PhoneMapRouteAnalysisDiagnosticOutcome.CANCELLED
                                    } else {
                                        PhoneMapRouteAnalysisDiagnosticOutcome.FAILED
                                    }
                                PhoneDebugCapture.log(
                                    PHONE_MAP_FEATURE_DIAGNOSTICS_TAG,
                                    phoneMapRouteAnalysisDiagnosticLine(
                                        outcome,
                                        request,
                                        (System.nanoTime() - startedAtNanos) / PHONE_MAP_NANOS_PER_MILLISECOND,
                                        stale = routeAnalysisRequestState.generation != request.generation,
                                    ) +
                                        if (outcome == PhoneMapRouteAnalysisDiagnosticOutcome.FAILED) {
                                            " category=analysis_error"
                                        } else {
                                            ""
                                        },
                                )
                                throw failure
                            }
                        var outcome = phoneMapRouteAnalysisDiagnosticOutcome(result, canPublish = false)
                        if (result != null) {
                            val currentItems = gpxViewModel.uiState.value.items
                            val routeAnalysisAvailable =
                                isPhoneMapRouteAnalysisAvailable(
                                    inspectionEnabled = gpxSettings.inspectionEnabled,
                                    gpxTracksVisible = mapUiState.contentVisibility.gpxTracks,
                                    routeToolsOpen = routeToolsState.isOpen,
                                    poiCreationActive = poiCreationActive,
                                    poiCreationPointSet = poiCreationPoint != null,
                                )
                            val canPublish =
                                routeAnalysisRequestState.canPublish(
                                    request = request,
                                    result = result,
                                    current =
                                        PhoneMapRouteAnalysisRequestCurrentState(
                                            phase =
                                                if (routeAnalysisSelectingPointB) {
                                                    PhoneMapRouteAnalysisSelectionPhase.POINT_B
                                                } else {
                                                    PhoneMapRouteAnalysisSelectionPhase.POINT_A
                                                },
                                            analysis = routeAnalysis,
                                            trackRevision = routeAnalysisTrackRevision,
                                            items = currentItems,
                                            routeAnalysisAvailable = routeAnalysisAvailable,
                                            gpxTracksVisible = mapUiState.contentVisibility.gpxTracks,
                                            settings = gpxSettings,
                                            generalSettings = generalSettings,
                                        ),
                                )
                            outcome = phoneMapRouteAnalysisDiagnosticOutcome(result, canPublish)
                            if (canPublish) {
                                if (phase == PhoneMapRouteAnalysisSelectionPhase.POINT_A) selectedPoi = null
                                routeAnalysis = result.analysis
                                savedRouteAnalysisSelection = result.savedSelection()
                                routeAnalysisTrackRevision = result.trackRevision
                                routeAnalysisSelectingPointB = false
                            }
                        }
                        PhoneDebugCapture.log(
                            PHONE_MAP_FEATURE_DIAGNOSTICS_TAG,
                            phoneMapRouteAnalysisDiagnosticLine(
                                outcome,
                                request,
                                (System.nanoTime() - startedAtNanos) / PHONE_MAP_NANOS_PER_MILLISECOND,
                            ),
                        )
                    } finally {
                        if (routeAnalysisPendingRequest === request) {
                            routeAnalysisPendingRequest = null
                            routeAnalysisJob = null
                        }
                    }
                }
            routeAnalysisJob = job
        }
    }
    val savePoi = {
        val point = poiCreationPoint
        if (point != null && !poiCreationBusy) {
            poiCreationBusy = true
            coroutineScope.launch {
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
                    .onSuccess {
                        poiCreationPoint = null
                        poiCreationDialogVisible = false
                        poiNameInput = ""
                        poiCreationError = false
                        onPoiDataChanged()
                    }.onFailure {
                        poiCreationError = true
                    }
            }
        }
    }
    val onToolPanelBack = {
        if (settingsPage && mapUiState.toolPanel.featureSettingsSection == MapToolFeatureSettingsSection.ROOT) {
            mapUiState = mapUiState.closeTool()
            onOpenMenu()
        } else {
            mapUiState = mapUiState.onMapBack()
        }
    }
    val onMapBack = {
        when {
            discoveryPickingPoint || discoveryNearMePending -> {
                discoveryPickingPoint = false
                discoveryNearMePending = false
            }
            settingsPage -> onToolPanelBack()
            hikeUi.pickingCheckpoint -> hikeController?.cancelCheckpoint()
            searchOpen -> {
                searchOpen = false
                searchViewModel.controller.cancel()
            }
            searchSelection != null -> searchSelection = null
            selectedGpxAnalysisId != null -> selectedGpxAnalysisId = null
            routeAnalysis != null ||
                savedRouteAnalysisSelection != null ||
                routeAnalysisJob != null ||
                routeAnalysisSelectingPointB -> clearRouteAnalysis()
            poiCreationDialogVisible -> {
                if (!poiCreationBusy) {
                    poiCreationDialogVisible = false
                    poiCreationPoint = null
                    poiCreationError = false
                }
            }
            poiCreationPoint != null -> {
                poiCreationPoint = null
                poiCreationDialogVisible = false
                poiCreationError = false
            }
            poiCreationActive -> poiCreationActive = false
            else -> onToolPanelBack()
        }
    }

    BackHandler(
        enabled =
            hikeUi.pickingCheckpoint ||
                searchOpen ||
                searchSelection != null ||
                mapUiState.toolPanel.mode != MapToolPanelMode.CLOSED ||
                mapUiState.toolLauncherExpanded ||
                poiCreationActive ||
                poiCreationDialogVisible ||
                poiCreationPoint != null ||
                selectedGpxAnalysisId != null ||
                routeAnalysis != null ||
                savedRouteAnalysisSelection != null ||
                routeAnalysisJob != null ||
                routeAnalysisSelectingPointB,
    ) {
        onMapBack()
    }
    val waitingForOfflineMaps =
        shouldWaitForPhoneOfflineMapStartup(
            startupComplete = offlineMapsLoaded,
            source = mapUiState.source,
            comparison = mapUiState.comparison.layer,
            installedMaps = offlineMaps,
        )
    val comparisonOverlayAlpha = mapUiState.comparison.overlayAlpha()
    val selectedOfflineComparison = mapUiState.comparison.layer as? PhoneMapComparisonLayer.Offline
    val offlineComparison = selectedOfflineComparison
    val offlineComparisonSelectionGeneration = mapUiState.comparison.selectionGeneration
    val offlineComparisonActive = offlineComparison != null && comparisonOverlayAlpha > 0f
    val onlineComparison =
        (mapUiState.comparison.layer as? PhoneMapComparisonLayer.Online)
            ?.takeIf { comparison -> PhoneMapRendererCatalog.isOnlineSourceAvailable(comparison.source) }
    val onlineComparisonProvider =
        onlineComparison?.let { comparison ->
            PhoneMapRendererCatalog.providerForOnlineSource(comparison.source)
        }
    val onlineComparisonActive = onlineComparisonProvider != null && comparisonOverlayAlpha > 0f
    val activeOnlineBaseSource = selectedOnlineSource.takeIf { mapUiState.source == PhoneMapSource.Online }
    val activeOnlineBaseProviderId = selectedOnlineProvider.id.takeIf { activeOnlineBaseSource != null }
    val activeOnlineComparisonSource = onlineComparison?.source
    val activeOnlineComparisonProviderId = onlineComparisonProvider?.id
    val activeOnlineBase = PhoneMapLibreActiveSource(activeOnlineBaseSource, activeOnlineBaseProviderId)
    val activeOnlineComparison =
        PhoneMapLibreActiveSource(activeOnlineComparisonSource, activeOnlineComparisonProviderId)
    val activeMapLibreRenderErrors =
        mapLibreRenderErrors.errorsForSources(
            baseSource = activeOnlineBase,
            comparisonSource = activeOnlineComparison,
        )
    LaunchedEffect(activeOnlineBase, activeOnlineComparison) {
        mapLibreRenderErrors =
            mapLibreRenderErrors.clearObsoleteSources(
                baseSource = activeOnlineBase,
                comparisonSource = activeOnlineComparison,
            )
    }
    val comparisonOwnsSemanticOverlays =
        phoneMapComparisonOwnsSemanticOverlays(
            offlineComparisonActive = offlineComparisonActive,
            onlineComparisonActive = onlineComparisonActive,
        )
    val comparisonOwnsUserCamera =
        phoneMapComparisonOwnsUserCamera(
            offlineComparisonActive = offlineComparisonActive,
            onlineComparisonActive = onlineComparisonActive,
        )
    val currentComparisonActive = {
        mapUiState.comparison.layer != null && mapUiState.comparison.overlayAlpha() > 0f
    }
    val onBaseCameraInput = { cameraState = cameraState.beginInput(PhoneMapCameraRenderer.BASE) }
    val onComparisonCameraInput = {
        if (currentComparisonActive()) cameraState = cameraState.beginInput(PhoneMapCameraRenderer.COMPARISON)
    }
    val onBaseCameraChanged: (PhoneMapCameraSnapshot) -> Unit = { camera ->
        cameraState = cameraState.update(PhoneMapCameraRenderer.BASE, camera.forMapMode(mapUiState.mapMode), currentComparisonActive())
    }
    val onComparisonCameraChanged: (PhoneMapCameraSnapshot) -> Unit = { camera ->
        cameraState = cameraState.update(PhoneMapCameraRenderer.COMPARISON, camera.forMapMode(mapUiState.mapMode), currentComparisonActive())
    }
    val comparisonOwnsFollowAndCommands =
        phoneMapComparisonOwnsFollowAndCommands(
            baseSource = mapUiState.source,
            onlineComparisonActive = onlineComparisonActive,
            offlineComparisonActive = offlineComparisonActive,
        )
    val comparisonOwnsInitialGpxFit =
        phoneMapComparisonOwnsInitialGpxFit(
            baseSource = mapUiState.source,
            onlineComparisonActive = onlineComparisonActive,
            offlineComparisonActive = offlineComparisonActive,
        )
    PhoneHikeMapEffects(
        controller = hikeController,
        ui = hikeUi,
        location = mapLocation,
        camera = mapCamera,
        viewport = hikeMapViewport,
        visible = mapUiState.contentVisibility.gpxTracks && hikeTrackEnabled && !settingsPage,
        onPan = { point ->
            shouldCenterOnInitialLocation = false
            hasFittedGpxOverlay = true
            if (comparisonOwnsFollowAndCommands) onComparisonCameraInput() else onBaseCameraInput()
            mapUiState = mapUiState.requestSearchTarget(point, mapCamera.bearingDegrees)
        },
        onReturnToLive = {
            mapUiState = mapUiState.copy(mapMode = mapUiState.mapMode.recenterOnLocation())
        },
    )
    LaunchedEffect(comparisonOwnsFollowAndCommands, offlineComparisonActive) {
        if (comparisonOwnsFollowAndCommands && offlineComparisonActive) {
            onComparisonCameraInput()
        } else if (!currentComparisonActive()) {
            onBaseCameraInput()
        }
    }
    val baseOfflineMap = (mapUiState.source as? PhoneMapSource.Offline)?.map
    val requestedBaseSourceId =
        if (missingStartupOfflineMap) {
            "offline-preferred"
        } else {
            baseOfflineMap?.opaqueDiagnosticId() ?: mapSourcePreference.onlineSource.diagnosticProviderId()
        }
    val effectiveBaseSourceId =
        when {
            baseOfflineMap == null -> selectedOnlineProvider.id
            !offlineMapsLoaded || baseOfflineMap in offlineMaps -> baseOfflineMap.opaqueDiagnosticId()
            else -> "none"
        }
    val baseFallbackReason =
        when {
            missingStartupOfflineMap -> "preferred_offline_map_missing"
            baseOfflineMap == null && requestedBaseSourceId != effectiveBaseSourceId -> "provider_unavailable"
            baseOfflineMap != null && offlineMapsLoaded && baseOfflineMap !in offlineMaps ->
                "offline_source_unavailable"
            else -> null
        }
    val selectedComparisonLayer = mapUiState.comparison.layer
    val requestedComparisonSourceId =
        when (selectedComparisonLayer) {
            null -> "none"
            is PhoneMapComparisonLayer.Online -> selectedComparisonLayer.source.diagnosticProviderId()
            is PhoneMapComparisonLayer.Offline -> selectedComparisonLayer.map.opaqueDiagnosticId()
        }
    val effectiveComparisonSourceId =
        when (selectedComparisonLayer) {
            null -> "none"
            is PhoneMapComparisonLayer.Online -> onlineComparisonProvider?.id ?: "none"
            is PhoneMapComparisonLayer.Offline ->
                selectedComparisonLayer.map.opaqueDiagnosticId().takeIf {
                    !offlineMapsLoaded || selectedComparisonLayer.map in offlineMaps
                } ?: "none"
        }
    val comparisonFallbackReason =
        when (selectedComparisonLayer) {
            null -> null
            is PhoneMapComparisonLayer.Online -> "provider_unavailable".takeIf { onlineComparisonProvider == null }
            is PhoneMapComparisonLayer.Offline ->
                "offline_source_unavailable".takeIf {
                    offlineMapsLoaded && selectedComparisonLayer.map !in offlineMaps
                }
        }
    val mapDiagnosticState =
        PhoneMapDiagnosticState(
            requestedBaseSourceId = requestedBaseSourceId,
            effectiveBaseSourceId = effectiveBaseSourceId,
            baseFallbackReason = baseFallbackReason,
            requestedComparisonSourceId = requestedComparisonSourceId,
            effectiveComparisonSourceId = effectiveComparisonSourceId,
            comparisonFallbackReason = comparisonFallbackReason,
            baseRenderer =
                baseOfflineMap?.let {
                    offlineMapDiagnosticRenderer(it, PhoneOfflineMapRendererRole.BASE, offlineRendererRefs)
                } ?: mapLibreDiagnosticRenderer(
                    "maplibre-base",
                    mapRuntime.generation.renderer,
                    mapRuntime.mapView,
                ),
            comparisonRenderer =
                when (selectedComparisonLayer) {
                    null -> null
                    is PhoneMapComparisonLayer.Online ->
                        mapLibreDiagnosticRenderer(
                            "maplibre-comparison",
                            comparisonMapRuntime.generation.renderer,
                            comparisonMapRuntime.mapView,
                        )
                    is PhoneMapComparisonLayer.Offline ->
                        offlineMapDiagnosticRenderer(
                            selectedComparisonLayer.map,
                            PhoneOfflineMapRendererRole.COMPARISON,
                            offlineRendererRefs,
                        )
                },
            comparisonMode =
                when (selectedComparisonLayer) {
                    null -> "none"
                    is PhoneMapComparisonLayer.Online -> "online"
                    is PhoneMapComparisonLayer.Offline -> "offline"
                },
            comparisonTransparencyPercent =
                mapUiState.comparison.transparencyPercent.takeIf { selectedComparisonLayer != null },
            appliedComparisonOpacity = comparisonOverlayAlpha.takeIf { selectedComparisonLayer != null },
            cameraAuthority = cameraState.publisher.name.lowercase(),
            touchOwner = if (comparisonOwnsUserCamera) "comparison" else "base",
            overlayOwner = if (comparisonOwnsSemanticOverlays) "comparison" else "base",
            locationOwner = phoneMapDiagnosticLocationOwner(offlineComparisonActive, onlineComparisonActive),
            locationFollowOwner = if (comparisonOwnsFollowAndCommands) "comparison" else "base",
            followMode =
                if (mapUiState.mapMode.isOffline) {
                    "offline"
                } else {
                    mapUiState.mapMode.follow.name
                        .lowercase()
                },
            orientation =
                mapUiState.mapMode.orientation.name
                    .lowercase(),
            panelMode =
                mapUiState.toolPanel.mode.name
                    .lowercase(),
            panelTool =
                mapUiState.toolPanel.activeTool
                    ?.name
                    ?.lowercase() ?: "none",
            panelContent =
                mapUiState.toolPanel.contentMode.name
                    .lowercase(),
            toolLauncherExpanded = mapUiState.toolLauncherExpanded,
        )
    val offlineMapRevisionSelections =
        buildMap<PhoneOfflineMapRendererRole, PhoneMapOfflineRevisionSelection> {
            baseOfflineMap?.let { map ->
                put(
                    PhoneOfflineMapRendererRole.BASE,
                    PhoneMapOfflineRevisionSelection(map.selectionIdentity, requestedBaseSourceId),
                )
            }
            (selectedComparisonLayer as? PhoneMapComparisonLayer.Offline)
                ?.map
                ?.let { map ->
                    put(
                        PhoneOfflineMapRendererRole.COMPARISON,
                        PhoneMapOfflineRevisionSelection(map.selectionIdentity, requestedComparisonSourceId),
                    )
                }
        }

    LaunchedEffect(
        mapDiagnosticState,
        offlineMapRevisionSelections,
        captureSessionId,
        captureActive,
    ) {
        PhoneMapStateDiagnostics.record(
            state = mapDiagnosticState,
            captureSessionId = captureSessionId,
            captureActive = captureActive,
            offlineMapSelections = offlineMapRevisionSelections,
        )
    }
    DisposableEffect(mapLocationSource) {
        val removeBeforeStopListener =
            PhoneDebugCapture.addBeforeStopListener {
                mapLocationSource.finishCaptureDiagnostics()
                PhoneMapLibreRenderDiagnostics.publishCaptureSnapshot()
            }
        onDispose { removeBeforeStopListener() }
    }
    LaunchedEffect(captureSessionId, captureActive, mapLocationSource) {
        if (captureActive) {
            PhoneMapLibreRenderDiagnostics.publishCaptureSnapshot()
            mapLocationSource.publishCaptureSnapshot()
            while (isActive) {
                delay(5_000L)
                PhoneDebugCapture.checkpoint()
            }
        }
    }
    val visibleGpxPointCount =
        gpxOverlays.sumOf { overlay -> overlay.segments.sumOf { segment -> segment.points.size } }
    val measurementEndpointCount = if (distanceMeasurement == null) 0 else 2
    val selectionMarkerCount = pointSelectionMarkers.size - measurementEndpointCount
    val routeAnalysisPointCount =
        routeAnalysis?.let { analysis -> if (analysis.pointB == null) 1 else 2 } ?: 0
    val selectionPhase =
        when {
            routeAnalysisSelectingPointB -> "route_analysis_point_b"
            routeAnalysisPointCount > 0 -> "route_analysis_complete"
            routeToolsState.pointSelectionPhase() != null ->
                routeToolsState.pointSelectionPhase()?.name?.lowercase() ?: "none"
            poiCreationActive -> "poi_location"
            searchSelection != null -> "search_result"
            else -> "none"
        }
    LaunchedEffect(
        captureSessionId,
        captureActive,
        gpxUiState.isLoading,
        gpxOverlays.size,
        visibleGpxPointCount,
        mapDiagnosticState.overlayOwner,
    ) {
        if (captureActive) {
            PhoneDebugCapture.log(
                PHONE_MAP_FEATURE_DIAGNOSTICS_TAG,
                "event=gpx_overlay_state loading=${gpxUiState.isLoading} tracks=${gpxOverlays.size} " +
                    "points=$visibleGpxPointCount owner=${mapDiagnosticState.overlayOwner}",
            )
        }
    }
    LaunchedEffect(
        captureSessionId,
        captureActive,
        selectionMarkerCount,
        selectionPhase,
        routeAnalysisPointCount,
        measurementEndpointCount,
        mapDiagnosticState.overlayOwner,
    ) {
        if (captureActive) {
            PhoneDebugCapture.log(
                PHONE_MAP_FEATURE_DIAGNOSTICS_TAG,
                "event=map_selection_state phase=$selectionPhase markers=$selectionMarkerCount " +
                    "route_analysis_points=$routeAnalysisPointCount " +
                    "measurement_endpoints=$measurementEndpointCount owner=${mapDiagnosticState.overlayOwner}",
            )
        }
    }

    MapToolScaffold(
        headerContent = header@{ tool ->
            if (!isVisible) return@header
            when (tool) {
                MapTool.HIKE -> hikeHeaderAction()
                MapTool.LIBRARY -> {
                    if (mapUiState.toolPanel.contentMode == MapToolContentMode.MAIN) libraryHeaderContent()
                }

                MapTool.LAYER -> {
                    val baseLabel = stringResource(R.string.map_layer_base_source)
                    val comparisonLabel = stringResource(R.string.map_layer_comparison)
                    val base = mapUiState.source.asComparisonLayer(selectedOnlineSource)
                    val baseSourceLabel = comparisonLayerLabel(base)
                    val comparisonSourceLabel = mapUiState.comparison.layer?.let { comparisonLayerLabel(it) }
                    val views =
                        buildList {
                            add(
                                PhoneMapLegendView(
                                    label = baseLabel,
                                    sourceLabel =
                                        if (base is PhoneMapComparisonLayer.Offline) {
                                            effectiveBaseThemeConfig.summary()
                                        } else {
                                            baseSourceLabel
                                        },
                                    themeId =
                                        effectiveBaseThemeConfig.themeId.takeIf {
                                            base is PhoneMapComparisonLayer.Offline
                                        },
                                ),
                            )
                            mapUiState.comparison.layer?.let { comparison ->
                                add(
                                    PhoneMapLegendView(
                                        label = comparisonLabel,
                                        sourceLabel =
                                            if (comparison is PhoneMapComparisonLayer.Offline) {
                                                comparisonThemeConfig.summary()
                                            } else {
                                                comparisonSourceLabel.orEmpty()
                                            },
                                        themeId =
                                            comparisonThemeConfig.themeId.takeIf {
                                                comparison is PhoneMapComparisonLayer.Offline
                                            },
                                    ),
                                )
                            }
                        }
                    IconButton(onClick = { navigation.onMapLegend(views) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = stringResource(R.string.map_legend_title),
                        )
                    }
                }

                MapTool.GPX_TOOLS ->
                    IconButton(onClick = routeToolsActions.onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.map_tool_action_settings_content_description),
                        )
                    }

                else -> Unit
            }
        },
        presentation =
            MapToolScaffoldState(
                panel = mapUiState.toolPanel,
                launcherExpanded = mapUiState.toolLauncherExpanded,
                fullScreenPage = settingsPage,
                toolbarVisible = mapToolbarVisible,
                layersActive =
                    offlineComparisonActive ||
                        onlineComparisonActive ||
                        (
                            mapUiState.source is PhoneMapSource.Offline &&
                                hasElevationData &&
                                (
                                    mapSettings.hillShadingEnabled ||
                                        (
                                            mapSettings.reliefOverlayEnabled &&
                                                mapSettings.reliefOverlayOpacityPercent > 0
                                        )
                                )
                        ),
                maptoolkitAttributionVisible =
                    maptoolkitAttributionRequired(activeOnlineBaseSource, activeOnlineComparisonSource),
            ),
        actions =
            MapToolScaffoldActions(
                onToolSelected = { tool -> mapUiState = mapUiState.selectTool(tool) },
                onToggleLauncher = { mapUiState = mapUiState.toggleToolLauncher() },
                onAddPoi = onAddPoi,
                onOpenRecording = { mapUiState = mapUiState.selectTool(MapTool.REC) },
                onOpenGpxTools = onOpenRouteTools,
                onCycleSize = {
                    mapUiState = mapUiState.copy(toolPanel = mapUiState.toolPanel.cycleSize())
                    mapSettingsPreferences.saveToolPanelSizing(mapUiState.toolPanel)
                },
                onResize = { fraction ->
                    mapUiState = mapUiState.copy(toolPanel = mapUiState.toolPanel.resize(fraction))
                },
                onResizeFinished = { mapSettingsPreferences.saveToolPanelSizing(mapUiState.toolPanel) },
                onBack = onToolPanelBack,
                onClose = {
                    mapUiState = mapUiState.closeTool()
                    if (settingsPage) onOpenMenu()
                },
                onFeatureSettings = { mapUiState = mapUiState.showFeatureSettings() },
            ),
        mapContent = {
            Box(modifier = Modifier.fillMaxSize().onSizeChanged { hikeMapViewport = it }) {
                if (
                    storageMigrationState.phase.isPhoneStorageMigrationActive() ||
                    pendingOfflineMap != null ||
                    waitingForOfflineMaps
                ) {
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
                                runtime = activeMapRuntime,
                                locationState = locationState,
                                mapMode = mapUiState.mapMode,
                                mapSettings = mapSettings,
                                tiltDegrees = onlineMapTiltDegrees,
                                userGestureActive = onlineGestureState.isActive,
                                compassSource = compassSensorSource,
                                compassPresentation = compassPresentation,
                                showLocationMarker = !comparisonOwnsSemanticOverlays,
                                ownsLocationFollow = !comparisonOwnsFollowAndCommands,
                                onCameraInput = onBaseCameraInput,
                            )
                            synchronizeGpxOverlay(
                                runtime = activeMapRuntime,
                                overlayState =
                                    if (comparisonOwnsSemanticOverlays) {
                                        gpxOverlayState.copy(
                                            segments =
                                                phoneMapComparisonBaseGpxSegments(
                                                    segments = gpxOverlayState.segments,
                                                    comparisonOwnsSemanticOverlays = true,
                                                ),
                                            isVisible = false,
                                        )
                                    } else {
                                        gpxOverlayState
                                    },
                                hasFittedGpxOverlay = hasFittedGpxOverlay,
                                fitRequest =
                                    GpxFitRequest(
                                        segments = savedGpxFitSegments,
                                        isRequested = savedGpxFitSegments.isNotEmpty(),
                                        isOwner = true,
                                    ),
                                callbacks =
                                    GpxFitCallbacks(
                                        onFitted = { hasFittedGpxOverlay = true },
                                        onCameraInput = onBaseCameraInput,
                                    ),
                            )
                            synchronizeRouteAnalysisOverlay(
                                runtime = activeMapRuntime,
                                analysis = routeAnalysis.takeUnless { comparisonOwnsSemanticOverlays },
                            )
                            synchronizePoiOverlay(
                                runtime = activeMapRuntime,
                                pois = pois,
                                isVisible =
                                    mapUiState.contentVisibility.pois && !comparisonOwnsSemanticOverlays,
                                settings = poiSettings,
                            )
                            synchronizePointSelectionOverlay(
                                runtime = activeMapRuntime,
                                markers =
                                    pointSelectionMarkers
                                        .takeUnless { comparisonOwnsSemanticOverlays }
                                        .orEmpty(),
                            )
                            synchronizeDistanceMeasurementOverlay(
                                runtime = activeMapRuntime,
                                measurement =
                                    distanceMeasurement.takeUnless { comparisonOwnsSemanticOverlays },
                            )
                            if (!comparisonOwnsSemanticOverlays) {
                                observePoiViewport(
                                    runtime = activeMapRuntime,
                                    isVisible = mapUiState.contentVisibility.pois,
                                    onViewportChanged = onPoiViewportChanged,
                                )
                                observePoiSelection(
                                    runtime = activeMapRuntime,
                                    pois = pois,
                                    isVisible = mapUiState.contentVisibility.pois,
                                    onPoiSelected = onHikeAwarePoiSelected,
                                )
                            }
                            observeOnlineCamera(
                                runtime = activeMapRuntime,
                                onCameraChanged = onBaseCameraChanged,
                                onUserCameraInput = onBaseCameraInput,
                                userInputEnabled = !comparisonOwnsUserCamera,
                            )
                            observeOnlineLiveMapPosition(
                                runtime = activeMapRuntime,
                                enabled =
                                    !comparisonOwnsSemanticOverlays &&
                                        (
                                            mapSettings.liveElevationEnabled ||
                                                mapSettings.liveDistanceEnabled ||
                                                routePointSelectionPhase != null ||
                                                poiCreationActive
                                        ),
                                location = mapLocation,
                                markerAnchor = mapSettings.markerAnchor,
                                onPositionChanged = { liveMapPosition = it },
                            )
                            observeOnlineUserPan(
                                runtime = activeMapRuntime,
                                enabled = !comparisonOwnsUserCamera,
                                shouldSuppressPan = {
                                    onlineGestureState.shouldSuppressPan(mapUiState.mapMode.follow)
                                },
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
                                runtime = activeMapRuntime,
                                enabled = !comparisonOwnsUserCamera,
                                onGestureActiveChanged = { active ->
                                    onlineGestureState =
                                        onlineGestureState.withActive(PhoneOnlineGestureType.ROTATE, active)
                                },
                                onUserRotation = { bearing ->
                                    mapUiState =
                                        mapUiState.copy(
                                            mapMode = mapUiState.mapMode.withManualBearing(bearing),
                                        )
                                },
                            )
                            observeOnlineUserScale(
                                runtime = activeMapRuntime,
                                enabled = !comparisonOwnsUserCamera,
                                onGestureActiveChanged = { active ->
                                    onlineGestureState =
                                        onlineGestureState.withActive(PhoneOnlineGestureType.SCALE, active)
                                },
                            )
                            synchronizeOnlineMapControls(
                                runtime = activeMapRuntime,
                                command = mapUiState.cameraCommand,
                                mapSettings = mapSettings,
                                onCameraCommandHandled = { commandId ->
                                    mapUiState = mapUiState.consumeCommand(commandId)
                                },
                                onCameraInput = onBaseCameraInput,
                            )
                            synchronizeComparisonOnlineCamera(
                                runtime = activeMapRuntime,
                                camera = cameraState.cameraForSynchronization(PhoneMapCameraRenderer.BASE),
                            )
                            mapSurface(
                                mapMode = mapUiState.mapMode,
                                initialCamera = mapCamera,
                                onlineSource = selectedOnlineSource,
                                onlineProvider = selectedOnlineProvider,
                                surfaceMode = PhoneMapLibreSurfaceMode.PRIMARY,
                                isVisible = isVisible,
                                onMapTap = onMapTap,
                                onMapLongPress = onMapLongPress,
                                onTwoFingerTap = onDistanceMeasurementGesture,
                                onMeasurementPointMoved = onDistanceMeasurementPointMoved,
                                isMeasurementEnabled = {
                                    mapSettings.distanceMeasurementEnabled && !comparisonOwnsSemanticOverlays
                                },
                                distanceMeasurement =
                                    distanceMeasurement.takeUnless { comparisonOwnsSemanticOverlays },
                                onMapViewCreated = { createdMapView ->
                                    mapRuntime = mapRuntime.beginMapView(createdMapView)
                                    mapRuntime.generation.renderer
                                },
                                isRendererCurrent = { renderer ->
                                    renderer.isCurrentIn(mapRuntime, activeOnlineBase, PhoneMapLibreRendererRole.BASE)
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
                                onRendererFailure = { failure ->
                                    val renderer = failure.renderer
                                    val nextErrors =
                                        mapLibreRenderErrors.recordFailureIfCurrent(
                                            error =
                                                PhoneMapLibreRenderError(
                                                    providerId = renderer.providerId,
                                                    source = renderer.source,
                                                    role = PhoneMapLibreRendererRole.BASE,
                                                    generation = renderer.generation,
                                                    category = failure.category,
                                                ),
                                            rendererIsCurrent =
                                                mapRuntime.acceptsRendererCallback(
                                                    renderer.generation,
                                                    renderer.mapView,
                                                ),
                                            activeSource = activeOnlineBase,
                                        )
                                    if (nextErrors != null) {
                                        mapLibreRenderErrors = nextErrors
                                    }
                                },
                                onRendererRecovered = { renderer, recoveryCategory ->
                                    mapLibreRenderErrors
                                        .clearAfterRecoveryIfCurrent(
                                            source =
                                                PhoneMapLibreRenderErrorSource(
                                                    renderer.source,
                                                    renderer.providerId,
                                                ),
                                            role = PhoneMapLibreRendererRole.BASE,
                                            rendererIsCurrent =
                                                mapRuntime.acceptsRendererCallback(
                                                    renderer.generation,
                                                    renderer.mapView,
                                                ),
                                            activeSource = activeOnlineBase,
                                            recoveryCategory = recoveryCategory,
                                        )?.let { nextErrors -> mapLibreRenderErrors = nextErrors }
                                },
                            )
                            onlineComparison?.let {
                                mapViewLifecycle(
                                    mapView = comparisonMapRuntime.mapView,
                                    onMapViewDestroyed = { destroyedMapView ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.invalidate(destroyedMapView)
                                    },
                                )
                                synchronizeComparisonOnlineCamera(
                                    runtime = activeComparisonMapRuntime,
                                    camera = cameraState.cameraForSynchronization(PhoneMapCameraRenderer.COMPARISON),
                                )
                                synchronizeOnlineMapPresentation(
                                    runtime = activeComparisonMapRuntime,
                                    locationState = locationState,
                                    mapMode = mapUiState.mapMode,
                                    mapSettings =
                                        mapSettings.copy(
                                            northIndicatorMode = PhoneMapNorthIndicatorMode.NEVER,
                                        ),
                                    tiltDegrees = onlineMapTiltDegrees,
                                    userGestureActive = onlineGestureState.isActive,
                                    compassSource = compassSensorSource,
                                    compassPresentation = compassPresentation,
                                    showLocationMarker = onlineComparisonActive,
                                    terrainOpacity = if (onlineComparisonActive) comparisonOverlayAlpha else 0f,
                                    ownsLocationFollow = comparisonOwnsFollowAndCommands,
                                    rendererRole = PhoneMapCameraRenderer.COMPARISON,
                                    onCameraInput = onComparisonCameraInput,
                                )
                                synchronizeGpxOverlay(
                                    runtime = activeComparisonMapRuntime,
                                    overlayState =
                                        gpxOverlayState.copy(
                                            isVisible = gpxOverlayState.isVisible && onlineComparisonActive,
                                        ),
                                    hasFittedGpxOverlay = hasFittedGpxOverlay,
                                    fitRequest =
                                        GpxFitRequest(
                                            segments = savedGpxFitSegments,
                                            isRequested = savedGpxFitSegments.isNotEmpty() && onlineComparisonActive,
                                            isOwner = comparisonOwnsInitialGpxFit,
                                        ),
                                    callbacks =
                                        GpxFitCallbacks(
                                            onFitted = { hasFittedGpxOverlay = true },
                                            onCameraInput = onComparisonCameraInput,
                                        ),
                                )
                                if (onlineComparisonActive) {
                                    synchronizeOnlineRasterOpacity(
                                        runtime = activeComparisonMapRuntime,
                                        opacity = comparisonOverlayAlpha,
                                    )
                                    synchronizeRouteAnalysisOverlay(
                                        runtime = activeComparisonMapRuntime,
                                        analysis = routeAnalysis,
                                    )
                                    synchronizePoiOverlay(
                                        runtime = activeComparisonMapRuntime,
                                        pois = pois,
                                        isVisible = mapUiState.contentVisibility.pois,
                                        settings = poiSettings,
                                    )
                                    observeOnlineCamera(
                                        runtime = activeComparisonMapRuntime,
                                        onCameraChanged = onComparisonCameraChanged,
                                        onUserCameraInput = onComparisonCameraInput,
                                    )
                                    observeOnlineLiveMapPosition(
                                        runtime = activeComparisonMapRuntime,
                                        enabled =
                                            mapSettings.liveElevationEnabled ||
                                                mapSettings.liveDistanceEnabled ||
                                                routePointSelectionPhase != null ||
                                                poiCreationActive,
                                        location = mapLocation,
                                        markerAnchor = mapSettings.markerAnchor,
                                        onPositionChanged = { liveMapPosition = it },
                                    )
                                    synchronizePointSelectionOverlay(
                                        runtime = activeComparisonMapRuntime,
                                        markers = pointSelectionMarkers,
                                    )
                                    synchronizeDistanceMeasurementOverlay(
                                        runtime = activeComparisonMapRuntime,
                                        measurement = distanceMeasurement,
                                    )
                                    observePoiViewport(
                                        runtime = activeComparisonMapRuntime,
                                        isVisible = mapUiState.contentVisibility.pois,
                                        onViewportChanged = onPoiViewportChanged,
                                    )
                                    observePoiSelection(
                                        runtime = activeComparisonMapRuntime,
                                        pois = pois,
                                        isVisible = mapUiState.contentVisibility.pois,
                                        onPoiSelected = onHikeAwarePoiSelected,
                                    )
                                    observeOnlineUserPan(
                                        runtime = activeComparisonMapRuntime,
                                        shouldSuppressPan = {
                                            onlineGestureState.shouldSuppressPan(mapUiState.mapMode.follow)
                                        },
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
                                        runtime = activeComparisonMapRuntime,
                                        onGestureActiveChanged = { active ->
                                            onlineGestureState =
                                                onlineGestureState.withActive(PhoneOnlineGestureType.ROTATE, active)
                                        },
                                        onUserRotation = { bearing ->
                                            mapUiState =
                                                mapUiState.copy(
                                                    mapMode =
                                                        mapUiState.mapMode.withManualBearing(bearing),
                                                )
                                        },
                                    )
                                    observeOnlineUserScale(
                                        runtime = activeComparisonMapRuntime,
                                        onGestureActiveChanged = { active ->
                                            onlineGestureState =
                                                onlineGestureState.withActive(PhoneOnlineGestureType.SCALE, active)
                                        },
                                    )
                                }
                                mapSurface(
                                    mapMode = mapUiState.mapMode,
                                    initialCamera = mapCamera,
                                    onlineSource = onlineComparison.source,
                                    surfaceMode = PhoneMapLibreSurfaceMode.COMPARISON,
                                    additionalAttribution = selectedOnlineProvider.attribution,
                                    isInteractive = onlineComparisonActive,
                                    onlineProvider =
                                        onlineComparisonProvider
                                            ?: PhoneMapRendererCatalog.mainOnlineRasterProvider,
                                    onMapTap = onMapTap,
                                    onMapLongPress = onMapLongPress,
                                    onTwoFingerTap = onDistanceMeasurementGesture,
                                    onMeasurementPointMoved = onDistanceMeasurementPointMoved,
                                    isMeasurementEnabled = {
                                        mapSettings.distanceMeasurementEnabled && onlineComparisonActive
                                    },
                                    distanceMeasurement = distanceMeasurement,
                                    onMapViewCreated = { createdMapView ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.beginMapView(createdMapView)
                                        comparisonMapRuntime.generation.renderer
                                    },
                                    isRendererCurrent = { renderer ->
                                        renderer.isCurrentIn(
                                            comparisonMapRuntime,
                                            activeOnlineComparison,
                                            PhoneMapLibreRendererRole.COMPARISON,
                                        )
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
                                    onRendererFailure = { failure ->
                                        val renderer = failure.renderer
                                        val nextErrors =
                                            mapLibreRenderErrors.recordFailureIfCurrent(
                                                error =
                                                    PhoneMapLibreRenderError(
                                                        providerId = renderer.providerId,
                                                        source = renderer.source,
                                                        role = PhoneMapLibreRendererRole.COMPARISON,
                                                        generation = renderer.generation,
                                                        category = failure.category,
                                                    ),
                                                rendererIsCurrent =
                                                    comparisonMapRuntime.acceptsRendererCallback(
                                                        renderer.generation,
                                                        renderer.mapView,
                                                    ),
                                                activeSource = activeOnlineComparison,
                                            )
                                        if (nextErrors != null) {
                                            mapLibreRenderErrors = nextErrors
                                        }
                                    },
                                    onRendererRecovered = { renderer, recoveryCategory ->
                                        mapLibreRenderErrors
                                            .clearAfterRecoveryIfCurrent(
                                                source =
                                                    PhoneMapLibreRenderErrorSource(
                                                        renderer.source,
                                                        renderer.providerId,
                                                    ),
                                                role = PhoneMapLibreRendererRole.COMPARISON,
                                                rendererIsCurrent =
                                                    comparisonMapRuntime.acceptsRendererCallback(
                                                        renderer.generation,
                                                        renderer.mapView,
                                                    ),
                                                activeSource = activeOnlineComparison,
                                                recoveryCategory = recoveryCategory,
                                            )?.let { nextErrors -> mapLibreRenderErrors = nextErrors }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    isVisible = isVisible && onlineComparisonActive,
                                )
                            }
                        }

                        is PhoneMapSource.Offline -> {
                            offlineMapSurface(
                                isVisible = isVisible,
                                state =
                                    PhoneOfflineMapSurfaceState(
                                        map = source.map,
                                        gestureMapMode = mapUiState.mapMode,
                                        themeConfig = effectiveBaseThemeConfig,
                                        isInteractive = !comparisonOwnsUserCamera,
                                        initialCamera = mapCamera,
                                        measurementEnabled =
                                            mapSettings.distanceMeasurementEnabled && !comparisonOwnsSemanticOverlays,
                                        mapSettings = mapSettings,
                                        terrainDataVersion = terrainDataVersion,
                                        hasTerrainData = hasElevationData,
                                        cameraOverride =
                                            cameraState.cameraForSynchronization(PhoneMapCameraRenderer.BASE),
                                        gpxOverlays =
                                            if (comparisonOwnsSemanticOverlays) emptyList() else gpxOverlayState.overlays,
                                        gpxSettings = gpxOverlayState.settings,
                                        routeAnalysis = routeAnalysis.takeUnless { comparisonOwnsSemanticOverlays },
                                        pointSelectionMarkers =
                                            pointSelectionMarkers
                                                .takeUnless { comparisonOwnsSemanticOverlays }
                                                .orEmpty(),
                                        distanceMeasurement =
                                            distanceMeasurement.takeUnless { comparisonOwnsSemanticOverlays },
                                        pois =
                                            if (comparisonOwnsSemanticOverlays) {
                                                emptyList()
                                            } else {
                                                pois.takeIf { mapUiState.contentVisibility.pois }.orEmpty()
                                            },
                                        poiSettings = poiSettings,
                                        mapMode =
                                            if (comparisonOwnsFollowAndCommands) {
                                                PhoneMapMode(
                                                    follow = PhoneMapFollowMode.FREE,
                                                    manualBearingDegrees = mapCamera.bearingDegrees,
                                                )
                                            } else {
                                                mapUiState.mapMode
                                            },
                                        compassPresentation =
                                            if (comparisonOwnsFollowAndCommands) {
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
                                                locationState.hasPermission && !comparisonOwnsFollowAndCommands
                                            },
                                        hasLocationPermission =
                                            locationState.hasPermission && !comparisonOwnsFollowAndCommands,
                                        cameraCommand =
                                            mapUiState.cameraCommand.takeUnless { comparisonOwnsFollowAndCommands },
                                    ),
                                callbacks =
                                    PhoneOfflineMapsforgeCallbacks(
                                        onCameraChanged = { camera ->
                                            if (!comparisonOwnsFollowAndCommands) onBaseCameraChanged(camera)
                                        },
                                        onUserCameraInput = {
                                            if (!comparisonOwnsFollowAndCommands) onBaseCameraInput()
                                        },
                                        onLiveMapPositionChanged = { position ->
                                            if (!comparisonOwnsFollowAndCommands) liveMapPosition = position
                                        },
                                        onViewportChanged = { viewport ->
                                            if (!comparisonOwnsFollowAndCommands) onPoiViewportChanged(viewport)
                                        },
                                        onPoiSelected = { poi ->
                                            if (!comparisonOwnsFollowAndCommands) onHikeAwarePoiSelected(poi)
                                        },
                                        onMapTap = { point -> if (!comparisonOwnsFollowAndCommands) onMapTap(point) },
                                        onMapLongPress = { point ->
                                            if (!comparisonOwnsFollowAndCommands) onMapLongPress(point)
                                        },
                                        onUserPan = { bearing ->
                                            if (!comparisonOwnsFollowAndCommands) {
                                                mapUiState =
                                                    mapUiState.copy(
                                                        mapMode = mapUiState.mapMode.detachFromLocation(bearing),
                                                    )
                                            }
                                        },
                                        onUserRotation = { bearing ->
                                            if (!comparisonOwnsFollowAndCommands) {
                                                mapUiState =
                                                    mapUiState.copy(
                                                        mapMode =
                                                            mapUiState.mapMode.withManualBearing(bearing),
                                                    )
                                            }
                                        },
                                        onCameraCommandHandled = { commandId ->
                                            if (!comparisonOwnsFollowAndCommands) {
                                                mapUiState = mapUiState.consumeCommand(commandId)
                                            }
                                        },
                                        onTwoFingerTap = { first, second ->
                                            if (
                                                !comparisonOwnsSemanticOverlays &&
                                                mapSettings.distanceMeasurementEnabled
                                            ) {
                                                distanceMeasurement = PhoneMapDistanceMeasurement(first, second)
                                            }
                                        },
                                        onMeasurementPointMoved = { index, point ->
                                            if (!comparisonOwnsSemanticOverlays) {
                                                onDistanceMeasurementPointMoved(index, point)
                                            }
                                        },
                                        onMapError = { error ->
                                            pendingOfflineMap = null
                                            switchToOnlineMap()
                                            offlineMapError = error
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
                            onlineComparison?.let {
                                mapViewLifecycle(
                                    mapView = comparisonMapRuntime.mapView,
                                    onMapViewDestroyed = { destroyedMapView ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.invalidate(destroyedMapView)
                                    },
                                )
                                synchronizeComparisonOnlineCamera(
                                    runtime = activeComparisonMapRuntime,
                                    camera = cameraState.cameraForSynchronization(PhoneMapCameraRenderer.COMPARISON),
                                )
                                synchronizeOnlineMapPresentation(
                                    runtime = activeComparisonMapRuntime,
                                    locationState = locationState,
                                    mapMode = mapUiState.mapMode,
                                    mapSettings =
                                        mapSettings.copy(
                                            northIndicatorMode = PhoneMapNorthIndicatorMode.NEVER,
                                        ),
                                    tiltDegrees = onlineMapTiltDegrees,
                                    userGestureActive = onlineGestureState.isActive,
                                    compassSource = compassSensorSource,
                                    compassPresentation = compassPresentation,
                                    showLocationMarker = onlineComparisonActive,
                                    terrainOpacity = if (onlineComparisonActive) comparisonOverlayAlpha else 0f,
                                    ownsLocationFollow = comparisonOwnsFollowAndCommands,
                                    rendererRole = PhoneMapCameraRenderer.COMPARISON,
                                    onCameraInput = onComparisonCameraInput,
                                )
                                synchronizeGpxOverlay(
                                    runtime = activeComparisonMapRuntime,
                                    overlayState =
                                        gpxOverlayState.copy(
                                            isVisible = gpxOverlayState.isVisible && onlineComparisonActive,
                                        ),
                                    hasFittedGpxOverlay = hasFittedGpxOverlay,
                                    fitRequest =
                                        GpxFitRequest(
                                            segments = savedGpxFitSegments,
                                            isRequested = savedGpxFitSegments.isNotEmpty() && onlineComparisonActive,
                                            isOwner = comparisonOwnsInitialGpxFit,
                                        ),
                                    callbacks =
                                        GpxFitCallbacks(
                                            onFitted = { hasFittedGpxOverlay = true },
                                            onCameraInput = onComparisonCameraInput,
                                        ),
                                )
                                if (onlineComparisonActive) {
                                    synchronizeOnlineRasterOpacity(
                                        runtime = activeComparisonMapRuntime,
                                        opacity = comparisonOverlayAlpha,
                                    )
                                    synchronizeRouteAnalysisOverlay(
                                        runtime = activeComparisonMapRuntime,
                                        analysis = routeAnalysis,
                                    )
                                    synchronizePoiOverlay(
                                        runtime = activeComparisonMapRuntime,
                                        pois = pois,
                                        isVisible = mapUiState.contentVisibility.pois,
                                        settings = poiSettings,
                                    )
                                    synchronizePointSelectionOverlay(
                                        runtime = activeComparisonMapRuntime,
                                        markers = pointSelectionMarkers,
                                    )
                                    synchronizeDistanceMeasurementOverlay(
                                        runtime = activeComparisonMapRuntime,
                                        measurement = distanceMeasurement,
                                    )
                                    observePoiViewport(
                                        runtime = activeComparisonMapRuntime,
                                        isVisible = mapUiState.contentVisibility.pois,
                                        onViewportChanged = onPoiViewportChanged,
                                    )
                                    observePoiSelection(
                                        runtime = activeComparisonMapRuntime,
                                        pois = pois,
                                        isVisible = mapUiState.contentVisibility.pois,
                                        onPoiSelected = onHikeAwarePoiSelected,
                                    )
                                    observeOnlineCamera(
                                        runtime = activeComparisonMapRuntime,
                                        onCameraChanged = onComparisonCameraChanged,
                                        onUserCameraInput = onComparisonCameraInput,
                                    )
                                    observeOnlineLiveMapPosition(
                                        runtime = activeComparisonMapRuntime,
                                        enabled =
                                            mapSettings.liveElevationEnabled ||
                                                mapSettings.liveDistanceEnabled ||
                                                routePointSelectionPhase != null ||
                                                poiCreationActive,
                                        location = mapLocation,
                                        markerAnchor = mapSettings.markerAnchor,
                                        onPositionChanged = { liveMapPosition = it },
                                    )
                                    observeOnlineUserPan(
                                        runtime = activeComparisonMapRuntime,
                                        shouldSuppressPan = {
                                            onlineGestureState.shouldSuppressPan(mapUiState.mapMode.follow)
                                        },
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
                                        runtime = activeComparisonMapRuntime,
                                        onGestureActiveChanged = { active ->
                                            onlineGestureState =
                                                onlineGestureState.withActive(PhoneOnlineGestureType.ROTATE, active)
                                        },
                                        onUserRotation = { bearing ->
                                            mapUiState =
                                                mapUiState.copy(
                                                    mapMode =
                                                        mapUiState.mapMode.withManualBearing(bearing),
                                                )
                                        },
                                    )
                                    observeOnlineUserScale(
                                        runtime = activeComparisonMapRuntime,
                                        onGestureActiveChanged = { active ->
                                            onlineGestureState =
                                                onlineGestureState.withActive(PhoneOnlineGestureType.SCALE, active)
                                        },
                                    )
                                    synchronizeOnlineMapControls(
                                        runtime = activeComparisonMapRuntime,
                                        command = mapUiState.cameraCommand,
                                        mapSettings = mapSettings,
                                        onCameraCommandHandled = { commandId ->
                                            mapUiState = mapUiState.consumeCommand(commandId)
                                        },
                                        onCameraInput = onComparisonCameraInput,
                                    )
                                }
                                mapSurface(
                                    mapMode = mapUiState.mapMode,
                                    initialCamera = mapCamera,
                                    onlineSource = onlineComparison.source,
                                    surfaceMode = PhoneMapLibreSurfaceMode.COMPARISON,
                                    isInteractive = onlineComparisonActive,
                                    onlineProvider =
                                        onlineComparisonProvider
                                            ?: PhoneMapRendererCatalog.mainOnlineRasterProvider,
                                    onMapTap = onMapTap,
                                    onMapLongPress = onMapLongPress,
                                    onTwoFingerTap = onDistanceMeasurementGesture,
                                    onMeasurementPointMoved = onDistanceMeasurementPointMoved,
                                    isMeasurementEnabled = {
                                        mapSettings.distanceMeasurementEnabled && onlineComparisonActive
                                    },
                                    distanceMeasurement = distanceMeasurement,
                                    onMapViewCreated = { createdMapView ->
                                        comparisonMapRuntime =
                                            comparisonMapRuntime.beginMapView(createdMapView)
                                        comparisonMapRuntime.generation.renderer
                                    },
                                    isRendererCurrent = { renderer ->
                                        renderer.isCurrentIn(
                                            comparisonMapRuntime,
                                            activeOnlineComparison,
                                            PhoneMapLibreRendererRole.COMPARISON,
                                        )
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
                                    onRendererFailure = { failure ->
                                        val renderer = failure.renderer
                                        val nextErrors =
                                            mapLibreRenderErrors.recordFailureIfCurrent(
                                                error =
                                                    PhoneMapLibreRenderError(
                                                        providerId = renderer.providerId,
                                                        source = renderer.source,
                                                        role = PhoneMapLibreRendererRole.COMPARISON,
                                                        generation = renderer.generation,
                                                        category = failure.category,
                                                    ),
                                                rendererIsCurrent =
                                                    comparisonMapRuntime.acceptsRendererCallback(
                                                        renderer.generation,
                                                        renderer.mapView,
                                                    ),
                                                activeSource = activeOnlineComparison,
                                            )
                                        if (nextErrors != null) {
                                            mapLibreRenderErrors = nextErrors
                                        }
                                    },
                                    onRendererRecovered = { renderer, recoveryCategory ->
                                        mapLibreRenderErrors
                                            .clearAfterRecoveryIfCurrent(
                                                source =
                                                    PhoneMapLibreRenderErrorSource(
                                                        renderer.source,
                                                        renderer.providerId,
                                                    ),
                                                role = PhoneMapLibreRendererRole.COMPARISON,
                                                rendererIsCurrent =
                                                    comparisonMapRuntime.acceptsRendererCallback(
                                                        renderer.generation,
                                                        renderer.mapView,
                                                    ),
                                                activeSource = activeOnlineComparison,
                                                recoveryCategory = recoveryCategory,
                                            )?.let { nextErrors -> mapLibreRenderErrors = nextErrors }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    isVisible = isVisible && onlineComparisonActive,
                                )
                            }
                        }
                    }
                    offlineComparison?.let { comparison ->
                        offlineMapSurface(
                            state =
                                PhoneOfflineMapSurfaceState(
                                    map = comparison.map,
                                    gestureMapMode = mapUiState.mapMode,
                                    themeConfig = comparisonThemeConfig,
                                    initialCamera = mapCamera,
                                    transparentBackground = true,
                                    isInteractive = offlineComparisonActive,
                                    measurementEnabled =
                                        mapSettings.distanceMeasurementEnabled && offlineComparisonActive,
                                    cameraOverride =
                                        cameraState.cameraForSynchronization(PhoneMapCameraRenderer.COMPARISON),
                                    mapSettings = mapSettings,
                                    baseLayerOpacity = comparisonOverlayAlpha,
                                    terrainDataVersion = 0L,
                                    hasTerrainData = false,
                                    gpxOverlays =
                                        if (offlineComparisonActive) {
                                            gpxOverlayState.overlays
                                        } else {
                                            emptyList()
                                        },
                                    gpxSettings = gpxOverlayState.settings,
                                    routeAnalysis = routeAnalysis.takeIf { offlineComparisonActive },
                                    pointSelectionMarkers =
                                        pointSelectionMarkers.takeIf { offlineComparisonActive }.orEmpty(),
                                    distanceMeasurement =
                                        distanceMeasurement.takeIf { offlineComparisonActive },
                                    pois =
                                        if (offlineComparisonActive) {
                                            pois.takeIf { mapUiState.contentVisibility.pois }.orEmpty()
                                        } else {
                                            emptyList()
                                        },
                                    poiSettings = poiSettings,
                                    mapMode =
                                        if (comparisonOwnsFollowAndCommands) {
                                            mapUiState.mapMode
                                        } else {
                                            PhoneMapMode(
                                                follow = PhoneMapFollowMode.FREE,
                                                manualBearingDegrees = mapCamera.bearingDegrees,
                                            )
                                        },
                                    compassPresentation = compassPresentation,
                                    location =
                                        locationState.location.takeIf {
                                            locationState.hasPermission && offlineComparisonActive
                                        },
                                    hasLocationPermission =
                                        locationState.hasPermission && offlineComparisonActive,
                                    cameraCommand = mapUiState.cameraCommand.takeIf { comparisonOwnsFollowAndCommands },
                                ),
                            callbacks =
                                PhoneOfflineMapsforgeCallbacks(
                                    onCameraChanged = { camera ->
                                        if (offlineComparisonActive) onComparisonCameraChanged(camera)
                                    },
                                    onUserCameraInput = {
                                        if (offlineComparisonActive) onComparisonCameraInput()
                                    },
                                    onLiveMapPositionChanged = { position ->
                                        if (offlineComparisonActive) liveMapPosition = position
                                    },
                                    onViewportChanged = { viewport ->
                                        if (offlineComparisonActive) onPoiViewportChanged(viewport)
                                    },
                                    onPoiSelected = { poi ->
                                        if (offlineComparisonActive) onHikeAwarePoiSelected(poi)
                                    },
                                    onMapTap = onMapTap,
                                    onMapLongPress = onMapLongPress,
                                    onTwoFingerTap = onDistanceMeasurementGesture,
                                    onMeasurementPointMoved = onDistanceMeasurementPointMoved,
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
                                                    mapUiState.mapMode.withManualBearing(bearing),
                                            )
                                    },
                                    onCameraCommandHandled = { commandId ->
                                        if (comparisonOwnsFollowAndCommands) {
                                            mapUiState = mapUiState.consumeCommand(commandId)
                                        }
                                    },
                                    onMapError = { error ->
                                        val recovered =
                                            mapUiState.clearFailedOfflineComparison(
                                                layer = comparison,
                                                selectionGeneration = offlineComparisonSelectionGeneration,
                                            )
                                        if (recovered != mapUiState) {
                                            offlineMapError = error
                                            mapUiState = recovered
                                        }
                                    },
                                    onLocationFollowUnavailable = {
                                        if (comparisonOwnsFollowAndCommands) {
                                            mapUiState =
                                                mapUiState.copy(mapMode = mapUiState.mapMode.detachFromLocation())
                                            mapLocationMessage = R.string.map_location_outside_offline_map
                                        }
                                    },
                                ),
                            modifier = Modifier.fillMaxSize(),
                            isVisible = isVisible && offlineComparisonActive,
                            rendererOwner =
                                PhoneOfflineMapsforgeRendererOwner(
                                    role = PhoneOfflineMapRendererRole.COMPARISON,
                                    generation = offlineComparisonSelectionGeneration,
                                ),
                        )
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
                val mapSearchActions =
                    PhoneMapSearchActions(
                        onFocus = { searchOpen = true },
                        onQueryChanged = searchViewModel.controller::edit,
                        onOnlineChanged = { enabled ->
                            generalSettingsPreferences.setOnlineSearchEnabled(enabled)
                            onlinePlaceSearchEnabled = enabled
                            searchViewModel.controller.setContext(enabled, poiSources.enabledFileNames())
                        },
                        onSearch = {
                            searchViewModel.controller.submit(
                                PhoneMapCoordinate(mapCamera.latitude, mapCamera.longitude),
                            )
                        },
                        onResultSelected = { result ->
                            if (searchViewModel.controller.state.value.reply.results
                                    .any { it.id == result.id }
                            ) {
                                searchViewModel.controller.cancel()
                                searchOpen = false
                                selectedPoi = null
                                searchSelection = result
                                if (discoveryOpen && mapUiState.toolPanel.activeTool == MapTool.GPX) {
                                    discoveryPickingPoint = false
                                    discoveryNearMePending = false
                                    discoverySearchPoint = result.point
                                    discoveryViewModel.controller.submit(
                                        TrailDiscoveryQuery(
                                            GeoPoint(result.point.latitude, result.point.longitude),
                                        ),
                                        discoveryViewModel.settings.value.enabled,
                                    )
                                }
                                shouldCenterOnInitialLocation = false
                                hasFittedGpxOverlay = true
                                if (comparisonOwnsFollowAndCommands) {
                                    onComparisonCameraInput()
                                } else {
                                    onBaseCameraInput()
                                }
                                mapUiState =
                                    mapUiState.requestSearchTarget(result.point, mapCamera.bearingDegrees)
                            }
                        },
                        onDismiss = {
                            searchOpen = false
                            searchViewModel.controller.cancel()
                        },
                    )
                mapControls(
                    onOpenMenu = onOpenMenu,
                    onZoomIn = { mapUiState = mapUiState.requestZoom(1) },
                    onZoomOut = { mapUiState = mapUiState.requestZoom(-1) },
                    camera = mapCamera,
                    compassHeadingDegrees = compassState.headingDegrees.takeIf { compassState.isRenderable && !mapUiState.mapMode.isOffline },
                    mapMode = mapUiState.mapMode,
                    mapSettings = mapSettings,
                    onCycleMapMode = onMapModePressed,
                    onToggleOfflineMode = onToggleOfflineMode,
                    northIndicatorVisible = northIndicatorVisible,
                    showModeControl = !routeCreationControlsVisible,
                    toolbarVisible = mapToolbarVisible,
                )

                Column(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    phoneMapSearchControl(
                        presentation =
                            PhoneMapSearchControlState(
                                search = searchState,
                                expanded = searchOpen,
                                camera = mapCamera,
                                isMetric = generalSettings.isMetric,
                            ),
                        actions = mapSearchActions,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp),
                    )
                    if (isVisible && searchOpen) {
                        PhoneMapSearchPanel(state = searchState, actions = mapSearchActions)
                    }
                }

                searchSelection?.takeUnless { searchOpen }?.let { result ->
                    PhoneMapPopupCard(
                        title = result.label,
                        onDismiss = { searchSelection = null },
                        modifier =
                            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 128.dp),
                    ) {
                        if (result.detail.isNotBlank()) {
                            Text(
                                result.detail,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                phoneMapPointerOverlay(
                    showPointer = mapUiState.mapMode.isDetachedFromLocation || routePointSelectionPhase != null,
                    poiCreationActive = poiCreationActive,
                    pointerCoordinate = mapPointerCoordinate,
                    onSelectPoiAtPointer = onSelectPoiPoint,
                    onCancelPoi = { poiCreationActive = false },
                )

                selectedPoi?.let { poi ->
                    phoneMapPoiDetailsCard(
                        poi = poi,
                        isMetric = generalSettings.isMetric,
                        onDismiss = { selectedPoi = null },
                        onUseAsCheckpoint =
                            hikeController?.takeIf { hikeUi.model != null }?.let { controller ->
                                {
                                    controller.checkpointAt(
                                        poi.location,
                                        poi.label.orEmpty(),
                                        "poi/${poi.sourceId}/${poi.id}",
                                        requireOnRoute = false,
                                    )
                                    selectedPoi = null
                                    mapUiState = mapUiState.openToolPage(MapTool.HIKE, featureSettings = false)
                                }
                            },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    )
                }
                distanceMeasurement?.let { measurement ->
                    phoneMapDistanceMeasurementCard(
                        measurement = measurement,
                        elevationRepository = elevationRepository,
                        terrainDataVersion = terrainDataVersion,
                        isMetric = generalSettings.isMetric,
                        onClear = { distanceMeasurement = null },
                        onlineElevationRepository =
                            onlineElevationRepository.takeIf { mapSettings.onlineElevationEnabled },
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
                        Text(
                            text =
                                stringResource(
                                    if (error == PhoneOfflineMapError.MISSING && missingStartupOfflineMap) {
                                        R.string.map_offline_preferred_map_missing
                                    } else {
                                        error.messageResource()
                                    },
                                ),
                        )
                    }
                }
                if (offlineMapError == null && activeMapLibreRenderErrors.isNotEmpty()) {
                    PhoneMapPopupCard(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp),
                        onDismiss = { mapLibreRenderErrors = PhoneMapLibreRenderErrorState() },
                    ) {
                        Column {
                            activeMapLibreRenderErrors.forEach { error ->
                                val messageResource =
                                    when (error.role) {
                                        PhoneMapLibreRendererRole.BASE -> R.string.map_online_base_render_failed
                                        PhoneMapLibreRendererRole.COMPARISON ->
                                            R.string.map_online_comparison_render_failed
                                    }
                                Text(text = stringResource(messageResource))
                            }
                        }
                    }
                }
                mapLocationMessage
                    ?.takeIf { offlineMapError == null && activeMapLibreRenderErrors.isEmpty() }
                    ?.let { messageResource ->
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
                            clearRouteAnalysis()
                        },
                        onSelectPointB = {
                            invalidateRouteAnalysisRequest()
                            routeAnalysisSelectingPointB = true
                        },
                    )
                }
                if (isVisible && poiCreationDialogVisible) {
                    phonePoiCreationSheet(
                        state =
                            PhonePoiCreationState(
                                point = poiCreationPoint,
                                name = poiNameInput,
                                saving = poiCreationBusy,
                                saveFailed = poiCreationError,
                            ),
                        coordinateSeed = mapPointerCoordinate,
                        actions =
                            PhonePoiCreationActions(
                                onPickMap = {
                                    if (!poiCreationBusy) {
                                        poiCreationPoint = null
                                        poiCreationDialogVisible = false
                                        poiCreationActive = true
                                        poiCreationError = false
                                    }
                                },
                                onCoordinatesSelected = { point ->
                                    poiCreationActive = false
                                    poiCreationPoint = point
                                    poiCreationDialogVisible = true
                                    poiCreationError = false
                                    shouldCenterOnInitialLocation = false
                                    hasFittedGpxOverlay = true
                                    if (comparisonOwnsFollowAndCommands) {
                                        onComparisonCameraInput()
                                    } else {
                                        onBaseCameraInput()
                                    }
                                    mapUiState = mapUiState.requestSearchTarget(point, mapCamera.bearingDegrees)
                                },
                                onNameChanged = { name ->
                                    poiNameInput = name
                                    poiCreationError = false
                                },
                                onSave = savePoi,
                                onDismiss = {
                                    if (!poiCreationBusy) {
                                        poiCreationPoint = null
                                        poiCreationDialogVisible = false
                                        poiCreationError = false
                                    }
                                },
                            ),
                    )
                }
                if (routeCreationControlsVisible) {
                    phoneMapRouteSelectionControls(
                        state = routeToolsState,
                        onUndo = routeToolsViewModel::undoLastMapPoint,
                        onClear = routeToolsViewModel::resetMapPoints,
                        onSelectCenter = onSelectRoutePointAtCenter,
                        toolbarVisible = mapToolbarVisible,
                    )
                }
            }
        },
        panelContent = panel@{ tool, contentMode, featureSettingsSection ->
            if (!isVisible) return@panel
            MapToolPanelContent(
                tool = tool,
                contentMode = contentMode,
                featureSettingsSection = featureSettingsSection,
                state = toolsState,
                hikeContent = hikeContent,
                routeToolsContent = {
                    PhoneRouteToolsPanel(
                        state = routeToolsState,
                        currentLocationAvailable = currentLocationAvailable,
                        actions = routeToolsActions,
                        mapSelectionContent = {
                            phoneMapRouteCreationSummary(
                                state = routeToolsState,
                                preview = routeCreationPreview,
                                isMetric = generalSettings.isMetric,
                                currentLocationAvailable = currentLocationAvailable,
                                actions = routeToolsActions,
                            )
                        },
                    )
                },
                libraryContent = libraryContent,
                discoveryOpen = discoveryOpen,
                onOpenDiscovery = { discoveryOpen = true },
                discoveryContent = {
                    TrailDiscoveryContent(
                        viewModel = discoveryViewModel,
                        query = TrailDiscoveryQuery(GeoPoint(mapCamera.latitude, mapCamera.longitude)),
                        onSearchPlace = {
                            onlinePlaceSearchEnabled = true
                            searchViewModel.controller.setContext(true, poiSources.enabledFileNames())
                            searchOpen = true
                        },
                        onPreview = { trail ->
                            val hadPreview = discoveredPreview != null
                            discoveredPreview = trail
                            if (trail != null && trail.geometry.isNotEmpty()) {
                                shouldCenterOnInitialLocation = false
                                hasFittedGpxOverlay = false
                                mapUiState =
                                    mapUiState.copy(
                                        mapMode = mapUiState.mapMode.detachFromLocation(mapCamera.bearingDegrees),
                                    )
                            } else if (hadPreview) {
                                hasFittedGpxOverlay = true
                            }
                        },
                        onLibrary = { discoveryOpen = false },
                        mapActions =
                            TrailDiscoverySearchActions(
                                onNearMe = {
                                    discoveryPickingPoint = false
                                    discoveryNearMePending = true
                                    if (!hasLocationPermission) {
                                        locationPermissionLauncher.launch(phoneMapLocationPermissions)
                                    }
                                },
                                onChoosePoint = {
                                    discoveryNearMePending = false
                                    discoveryPickingPoint = true
                                    mapUiState = mapUiState.collapseTool()
                                },
                                onUseMapCenter = { onSelectDiscoveryPoint(mapPointerCoordinate) },
                                onCancelPoint = {
                                    discoveryPickingPoint = false
                                    discoveryNearMePending = false
                                },
                                onDismiss = {
                                    discoveryPickingPoint = false
                                    discoveryNearMePending = false
                                },
                                pickingPoint = discoveryPickingPoint,
                                waitingForLocation = discoveryNearMePending,
                            ),
                    )
                },
                actions =
                    MapToolsPanelActions(
                        maps = mapActions,
                        onAddPoi = onAddPoi,
                        onOpenTool = { tool -> mapUiState = mapUiState.openToolPage(tool, featureSettings = false) },
                        onGpxVisibilityChanged = { visible ->
                            if (!visible) clearRouteAnalysis()
                            mapUiState =
                                mapUiState.copy(
                                    contentVisibility =
                                        mapUiState.contentVisibility.copy(gpxTracks = visible),
                                )
                        },
                        onGpxItemToggled = { id ->
                            val disablesRouteSource =
                                routeAnalysis?.trackId == id &&
                                    gpxUiState.items.firstOrNull { item -> item.id == id }?.enabled == true
                            if (disablesRouteSource) {
                                clearRouteAnalysis()
                            } else {
                                invalidateRouteAnalysisRequest()
                            }
                            gpxViewModel.toggle(id)
                        },
                        onOpenGpxAnalysis = { item ->
                            if (hikeController != null) {
                                hikeController.preview(item)
                                mapUiState = mapUiState.openToolPage(MapTool.HIKE, featureSettings = false)
                            } else {
                                selectedGpxAnalysisId = item.id
                            }
                        },
                        onRenameGpxItem = { item -> openContentRename(PhoneMapManagedContent.Gpx(item)) },
                        onDeleteGpxItem = { item -> openContentDelete(PhoneMapManagedContent.Gpx(item)) },
                        onOpenRouteTools = onOpenRouteTools,
                        onGpxSettingsChanged = { settings ->
                            if (!settings.inspectionEnabled) clearRouteAnalysis() else invalidateRouteAnalysisRequest()
                            gpxSettings = gpxSettingsPreferences.save(settings)
                        },
                        onGpxCustomizationChanged = gpxCustomizationPreferences::save,
                        onGpxAppearanceAppliedToAll = { settings ->
                            val updated = gpxCustomization.applyGeneralToAll(gpxSettings, settings)
                            gpxSettings = gpxSettingsPreferences.save(settings, updated)
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
                        onLayerBaseMapChanged = { layer ->
                            when (layer) {
                                is PhoneMapComparisonLayer.Online -> mapActions.onSelectOnline(layer.source)
                                is PhoneMapComparisonLayer.Offline -> mapActions.onSelectOffline(layer.map)
                            }
                        },
                        onBaseThemeChanged = changeBaseTheme,
                        onComparisonThemeChanged = { config ->
                            comparisonThemeConfig = offlineThemePreferences.saveComparison(config)
                        },
                        onSwapComparison = {
                            val upper = mapUiState.comparison.layer
                            if (upper != null) {
                                val request = mapUiState.nextComparisonSelectionRequest()
                                mapUiState = request
                                coroutineScope.launch {
                                    val error =
                                        if (upper is PhoneMapComparisonLayer.Offline) {
                                            withContext(Dispatchers.IO) { offlineMapStore.validate(upper.map) }
                                        } else {
                                            null
                                        }
                                    val selectionIsCurrent =
                                        mapUiState.comparison.validationRequestGeneration ==
                                            request.comparison.validationRequestGeneration &&
                                            mapUiState.comparison.layer == upper &&
                                            mapUiState.source == request.source
                                    if (selectionIsCurrent) {
                                        if (error != null) {
                                            offlineMapError = error
                                        } else {
                                            val oldBaseTheme = currentBaseThemeConfig()
                                            val oldComparisonTheme = comparisonThemeConfig
                                            val swapped = mapUiState.swapComparison(selectedOnlineSource)
                                            when (upper) {
                                                is PhoneMapComparisonLayer.Online -> selectOnlineMap(upper.source)
                                                is PhoneMapComparisonLayer.Offline -> switchToOfflineMap(upper.map)
                                            }
                                            mapUiState = swapped
                                            changeBaseTheme(oldComparisonTheme)
                                            comparisonThemeConfig = offlineThemePreferences.saveComparison(oldBaseTheme)
                                        }
                                    }
                                }
                            }
                        },
                        onComparisonLayerChanged = { layer ->
                            val offlineLayer = layer as? PhoneMapComparisonLayer.Offline
                            if (offlineLayer != null) {
                                comparisonThemeConfig = offlineThemePreferences.saveComparison(comparisonThemeConfig)
                            }
                            val selectionRequest = mapUiState.nextComparisonSelectionRequest()
                            mapUiState = selectionRequest
                            if (offlineLayer == null) {
                                mapUiState = mapUiState.selectComparisonLayer(layer)
                            } else {
                                val requestGeneration = selectionRequest.comparison.validationRequestGeneration
                                coroutineScope.launch {
                                    val error =
                                        withContext(Dispatchers.IO) {
                                            offlineMapStore.validate(offlineLayer.map)
                                        }
                                    val completion =
                                        mapUiState.completeOfflineComparisonSelection(
                                            requestGeneration = requestGeneration,
                                            layer = offlineLayer,
                                            validationError = error,
                                            offlineMaps = currentOfflineMaps,
                                        )
                                    mapUiState = completion.state
                                    completion.validationError?.let { offlineMapError = it }
                                }
                            }
                        },
                        onComparisonTransparencyChanged = { percent ->
                            mapUiState = mapUiState.setComparisonTransparency(percent)
                        },
                        onOpenTerrainPreview =
                            if (mapUiState.mapMode.isOffline) {
                                null
                            } else {
                                {
                                    terrainPreview =
                                        PhoneTerrainPreviewData(
                                            camera = mapCamera,
                                            source = selectedOnlineSource,
                                            tracks =
                                                gpxOverlayState.segments
                                                    .takeIf { gpxOverlayState.isVisible }
                                                    .orEmpty(),
                                            pois = pois.takeIf { mapUiState.contentVisibility.pois }.orEmpty(),
                                            location =
                                                locationState.location
                                                    .takeIf { locationState.hasPermission }
                                                    ?.let { GeoPoint(it.latitude, it.longitude) },
                                        )
                                }
                            },
                        onLayerMapSettingsChanged = { settings ->
                            mapSettings = mapSettingsPreferences.save(settings)
                        },
                        onFeatureSettings = { tool ->
                            if (settingsPage) {
                                navigation.onOpenSettings(tool)
                            } else {
                                mapUiState = mapUiState.closeTool().selectTool(tool).showFeatureSettings()
                            }
                        },
                        onFeatureSettingsSection = { section ->
                            mapUiState =
                                mapUiState.copy(
                                    toolPanel = mapUiState.toolPanel.showFeatureSettingsSection(section),
                                )
                        },
                        onGeneralSettingsChanged = { settings ->
                            generalSettings = generalSettingsPreferences.save(settings)
                        },
                        onOpenBundleDownload = {
                            elevationBundleAreaId = null
                            showOfflineBundleDownload = true
                        },
                        onStorageChangeRequested = onStorageChangeRequested,
                        onStorageMigrationCancelled = {
                            if (storageMigrationState.canCancel) {
                                storageMigrationState =
                                    storageMigrationState.copy(
                                        canCancel = false,
                                        message =
                                            migrationTextResources.getString(
                                                R.string.map_tools_settings_storage_cancelling,
                                            ),
                                    )
                                storageMigrationJob?.cancel()
                            }
                        },
                    ),
            )
        },
    )

    terrainPreview?.takeIf { isVisible && !mapUiState.mapMode.isOffline }?.let { preview ->
        phoneTerrainPreviewDialog(preview, onDismiss = { terrainPreview = null })
    }

    recordingState.completedRecording?.takeIf { mapActive }?.let { completed ->
        phoneRecordingCompletedDialog(
            recording = completed,
            isMetric = generalSettings.isMetric,
            onDismiss = { PhoneRecordingSessionStore.dismissCompletedRecording(completed.id) },
        )
    }

    selectedGpxAnalysis?.takeIf { isVisible }?.let { item ->
        PhoneMapGpxAnalysisDialog(
            item = item,
            settings = gpxSettings,
            generalSettings = generalSettings,
            onDismiss = { selectedGpxAnalysisId = null },
        )
    }

    val renameFailedMessage = stringResource(R.string.map_content_rename_failed)
    val deleteFailedMessage = stringResource(R.string.map_content_delete_failed)

    contentRenameTarget?.takeIf { isVisible }?.let { target ->
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

    contentDeleteTarget?.takeIf { isVisible }?.let { target ->
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

    if (isVisible && showOfflineThemeSelector) {
        offlineThemeSelector(
            config = offlineThemeConfig,
            onDismiss = { showOfflineThemeSelector = false },
            onChange = { config -> offlineThemeConfig = offlineThemePreferences.save(config) },
        )
    }

    if (isVisible && showOfflineBundleDownload) {
        PhoneOfflineBundleDialog(
            uiState = bundleUiState,
            onDismiss = {
                showOfflineBundleDownload = false
                elevationBundleAreaId = null
            },
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
            initialAreaId = elevationBundleAreaId,
            initialIncludeRouting = elevationBundleAreaId == null,
        )
    }
}

@Composable
private fun offlineThemeSelector(
    config: PhoneOfflineThemeConfig,
    onDismiss: () -> Unit,
    onChange: (PhoneOfflineThemeConfig) -> Unit,
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
                    onSelect = { themeId -> onChange(PhoneOfflineThemeCatalog.resolve(themeId, styleId = null)) },
                )
                mapToolsMapSettingPicker(
                    label = stringResource(R.string.map_theme_selector_style_label),
                    selectedLabel =
                        selectedTheme.styles
                            .firstOrNull { style -> style.id == config.styleId }
                            ?.label
                            ?: "Default",
                    options = selectedTheme.styles.map { style -> style.id to style.label },
                    onSelect = { styleId -> onChange(PhoneOfflineThemeCatalog.resolve(config.themeId, styleId)) },
                )
                phoneOfflineThemeOverlayControls(config, "base") { onChange(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}

@Composable
private fun phoneMapCompassLifecycle(
    source: PhoneCompassSensorSource,
    enabled: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(source, lifecycleOwner, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
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
    profile: PhoneMapLocationRequestProfile?,
    hasLocationPermission: Boolean,
    onPermissionChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPermissionChanged by rememberUpdatedState(onPermissionChanged)
    DisposableEffect(source, lifecycleOwner, profile, hasLocationPermission) {
        fun updateRegistration() {
            val permissionGranted = context.hasPhoneMapLocationPermission()
            if (permissionGranted != hasLocationPermission) {
                currentOnPermissionChanged(permissionGranted)
            }
            if (
                profile != null &&
                permissionGranted &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                source.start(requireNotNull(profile))
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
    onUserCameraInput: () -> Unit,
    userInputEnabled: Boolean = true,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOnCameraChanged by rememberUpdatedState(onCameraChanged)
    val currentOnUserCameraInput by rememberUpdatedState(onUserCameraInput)
    val currentUserInputEnabled by rememberUpdatedState(userInputEnabled)
    DisposableEffect(runtime.map) {
        val activeMap = runtime.map ?: return@DisposableEffect onDispose {}
        val listener =
            MapLibreMap.OnCameraMoveListener {
                if (runtime.isRendererCurrentIn(currentRuntime)) {
                    activeMap
                        .cameraSnapshotOrNull(runtime.mapView?.pixelRatio?.toDouble() ?: 1.0)
                        ?.let(currentOnCameraChanged)
                }
            }
        val inputListener =
            MapLibreMap.OnCameraMoveStartedListener { reason ->
                if (
                    reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE &&
                    currentUserInputEnabled &&
                    runtime.isRendererCurrentIn(currentRuntime)
                ) {
                    currentOnUserCameraInput()
                }
            }
        activeMap.addOnCameraMoveStartedListener(inputListener)
        activeMap.addOnCameraMoveListener(listener)
        onDispose {
            activeMap.removeOnCameraMoveStartedListener(inputListener)
            activeMap.removeOnCameraMoveListener(listener)
        }
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
            if (!runtime.isRendererCurrentIn(currentRuntime) || activeMap.width <= 0 || activeMap.height <= 0) return
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
    enabled: Boolean = true,
    shouldSuppressPan: () -> Boolean = { false },
    onGestureActiveChanged: (Boolean) -> Unit,
    onUserPan: (Float) -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentShouldSuppressPan by rememberUpdatedState(shouldSuppressPan)
    val currentOnGestureActiveChanged by rememberUpdatedState(onGestureActiveChanged)
    val currentOnUserPan by rememberUpdatedState(onUserPan)
    DisposableEffect(runtime.map, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val activeMap = runtime.map ?: return@DisposableEffect onDispose {}
        val listener =
            object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    if (runtime.isRendererCurrentIn(currentRuntime)) {
                        currentOnGestureActiveChanged(true)
                        recordOnlineGestureEvent("online_pan_begin", activeMap)
                    }
                }

                override fun onMove(detector: MoveGestureDetector) {
                    if (runtime.isRendererCurrentIn(currentRuntime) && !currentShouldSuppressPan()) {
                        currentOnUserPan(activeMap.cameraPosition.bearing.toFloat())
                    }
                }

                override fun onMoveEnd(detector: MoveGestureDetector) {
                    if (runtime.isRendererCurrentIn(currentRuntime)) {
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
private fun observeOnlineUserScale(
    runtime: MapRuntime,
    enabled: Boolean = true,
    onGestureActiveChanged: (Boolean) -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOnGestureActiveChanged by rememberUpdatedState(onGestureActiveChanged)
    DisposableEffect(runtime.map, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val activeMap = runtime.map ?: return@DisposableEffect onDispose {}
        val listener =
            object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                    if (runtime.isRendererCurrentIn(currentRuntime)) {
                        currentOnGestureActiveChanged(true)
                        recordOnlineGestureEvent("online_scale_begin", activeMap)
                    }
                }

                override fun onScale(detector: StandardScaleGestureDetector) = Unit

                override fun onScaleEnd(detector: StandardScaleGestureDetector) {
                    if (runtime.isRendererCurrentIn(currentRuntime)) {
                        recordOnlineGestureEvent("online_scale_end", activeMap)
                        currentOnGestureActiveChanged(false)
                    }
                }
            }
        activeMap.addOnScaleListener(listener)
        onDispose {
            activeMap.removeOnScaleListener(listener)
            currentOnGestureActiveChanged(false)
        }
    }
}

@Composable
private fun observeOnlineUserRotation(
    runtime: MapRuntime,
    enabled: Boolean = true,
    onGestureActiveChanged: (Boolean) -> Unit,
    onUserRotation: (Float) -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOnGestureActiveChanged by rememberUpdatedState(onGestureActiveChanged)
    val currentOnUserRotation by rememberUpdatedState(onUserRotation)
    DisposableEffect(runtime.map, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val activeMap = runtime.map ?: return@DisposableEffect onDispose {}
        activeMap.uiSettings.setDisableRotateWhenScaling(false)
        val listener =
            object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(detector: RotateGestureDetector) {
                    if (runtime.isRendererCurrentIn(currentRuntime)) {
                        currentOnGestureActiveChanged(true)
                        recordOnlineGestureEvent("online_rotate_begin", activeMap)
                    }
                }

                override fun onRotate(detector: RotateGestureDetector) {
                    if (runtime.isRendererCurrentIn(currentRuntime)) {
                        currentOnUserRotation(activeMap.cameraPosition.bearing.toFloat())
                    }
                }

                override fun onRotateEnd(detector: RotateGestureDetector) {
                    if (runtime.isRendererCurrentIn(currentRuntime)) {
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
    onCameraInput: () -> Unit,
) {
    val currentOnCameraCommandHandled by rememberUpdatedState(onCameraCommandHandled)
    val currentOnCameraInput by rememberUpdatedState(onCameraInput)
    LaunchedEffect(runtime.map, runtime.mapView, command, mapSettings) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        val camera = activeMap.cameraPosition
        val requestedTarget = command?.target
        val target =
            if (requestedTarget == null) {
                camera.target ?: return@LaunchedEffect
            } else {
                LatLng(requestedTarget.latitude, requestedTarget.longitude)
            }
        val viewportWidthPx = activeMap.width.toDouble()
        val tileSizePx = PHONE_MAPLIBRE_CAMERA_TILE_SIZE_PX * (runtime.mapView?.pixelRatio?.toDouble() ?: 1.0)
        if (viewportWidthPx > 0.0) {
            val zoomForScale = { scaleMeters: Int ->
                phoneMapZoomForScale(target.latitude, scaleMeters, viewportWidthPx, tileSizePx)
            }

            val minimumZoom = zoomForScale(mapSettings.zoomMinScaleMeters)
            val maximumZoom = zoomForScale(mapSettings.zoomMaxScaleMeters)
            if (minimumZoom != null && maximumZoom != null && minimumZoom <= maximumZoom) {
                activeMap.setMinZoomPreference(minimumZoom)
                activeMap.setMaxZoomPreference(maximumZoom)
                if (
                    target.latitude == defaultPhoneMapCamera.latitude &&
                    target.longitude == defaultPhoneMapCamera.longitude &&
                    camera.zoom == PHONE_MAP_DEFAULT_ZOOM
                ) {
                    zoomForScale(mapSettings.zoomDefaultScaleMeters)?.let { defaultZoom ->
                        currentOnCameraInput()
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
            pendingCommand
                .requestedZoom(activeMap.cameraPosition.zoom, mapSettings, viewportWidthPx, tileSizePx)
                .coerceIn(activeMap.minZoomLevel, activeMap.maxZoomLevel)
        currentOnCameraInput()
        activeMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition
                    .Builder(activeMap.cameraPosition)
                    .target(target)
                    .zoom(requestedZoom)
                    .build(),
            ),
        )
        currentOnCameraCommandHandled(pendingCommand.id)
    }
}

@Composable
private fun synchronizeComparisonOnlineCamera(
    runtime: MapRuntime,
    camera: PhoneMapCameraSnapshot?,
) {
    LaunchedEffect(runtime.map, camera) {
        if (camera == null) return@LaunchedEffect
        val activeMap = runtime.map ?: return@LaunchedEffect
        val pixelRatio = runtime.mapView?.pixelRatio?.toDouble() ?: 1.0
        val current = activeMap.cameraSnapshotOrNull(pixelRatio)
        if (current == null || phoneMapComparisonCameraNeedsSync(current, camera)) {
            activeMap.moveCamera(camera.toMapLibreCameraUpdate(activeMap, pixelRatio))
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
@SuppressLint("MissingPermission") // The component is only toggled after the location source reports permission.
@Suppress(
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
) // Explicit branches preserve the MapLibre location and camera lifecycle ownership.
private fun synchronizeOnlineMapPresentation(
    runtime: MapRuntime,
    locationState: MapLocationState,
    mapMode: PhoneMapMode,
    mapSettings: PhoneMapSettings,
    tiltDegrees: Double,
    userGestureActive: Boolean,
    compassSource: PhoneCompassSensorSource,
    compassPresentation: PhoneMapCompassPresentation,
    showLocationMarker: Boolean = true,
    terrainOpacity: Float = 1f,
    ownsLocationFollow: Boolean = true,
    rendererRole: PhoneMapCameraRenderer = PhoneMapCameraRenderer.BASE,
    onCameraInput: () -> Unit,
) {
    val context = LocalContext.current
    val currentRuntime by rememberUpdatedState(runtime)
    val currentLocationState by rememberUpdatedState(locationState)
    val currentMapMode by rememberUpdatedState(mapMode)
    val currentOnCameraInput by rememberUpdatedState(onCameraInput)
    val currentUserGestureActive by rememberUpdatedState(userGestureActive)
    var lastCameraSyncSkip by remember { mutableStateOf<PhoneOnlineCameraSyncSkipKey?>(null) }
    LaunchedEffect(runtime.map, tiltDegrees) {
        runtime.map?.applyPhoneMapTilt(tiltDegrees)
    }
    synchronizeOnlineHillshade(runtime, mapSettings.hillShadingEnabled, terrainOpacity)
    LaunchedEffect(runtime.map, mapMode, mapSettings.markerAnchor) {
        runtime.map?.let { activeMap ->
            activeMap.applyPhoneMapMarkerAnchor(mapSettings.markerAnchor)
            activeMap.applyPhoneMapGestureSettings(mapMode)
            if (
                mapMode.isOffline &&
                phoneMapBearingNeedsSync(
                    activeMap.cameraPosition.bearing.toFloat(),
                    compassPresentation.mapBearingDegrees,
                )
            ) {
                activeMap.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition
                            .Builder(activeMap.cameraPosition)
                            .bearing(compassPresentation.mapBearingDegrees.toDouble())
                            .build(),
                    ),
                )
            }
        }
    }
    phoneMapOrientationTelemetry(runtime.map) {
        runtime.map?.orientationDiagnosticSample(
            renderer = "maplibre_${rendererRole.name.lowercase()}_${runtime.generation.renderer}_style${runtime.generation.styleRevision}",
            mapMode = mapMode,
            requestedMapDegrees = compassPresentation.mapBearingDegrees,
            markerExpected = showLocationMarker && locationState.hasPermission,
            cameraControl =
                when {
                    mapMode.isOffline -> "offline"
                    !ownsLocationFollow -> "passive"
                    !locationState.hasPermission -> "no_permission"
                    userGestureActive -> "user_gesture"
                    mapMode.follow == PhoneMapFollowMode.FREE -> "free"
                    else -> "follow"
                },
        )
    }

    LaunchedEffect(
        runtime.map,
        runtime.generation.styleRevision,
        locationState.hasPermission,
        showLocationMarker,
        compassPresentation.markerScreenRotationDegrees != null,
        mapSettings.markerStyle,
        mapSettings.gpsAccuracyCircleEnabled,
    ) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { activeMap, _, style ->
            if (currentLocationState.hasPermission && showLocationMarker) {
                activeMap.enableLocationPuck(
                    style = style,
                    context = context,
                    compassSource = compassSource,
                    compassRenderable = compassPresentation.markerScreenRotationDegrees != null,
                    mapSettings = mapSettings,
                )
            } else if (activeMap.locationComponent.isLocationComponentActivated) {
                activeMap.disableLocationPuck()
            }
        }
    }

    LaunchedEffect(
        runtime.map,
        runtime.generation.styleRevision,
        locationState.hasPermission,
        locationState.location,
        mapSettings.markerAnchor,
        showLocationMarker,
    ) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        if (
            !showLocationMarker ||
            !locationState.hasPermission ||
            !activeMap.locationComponent.isLocationComponentActivated
        ) {
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
        ownsLocationFollow,
    ) {
        val activeMap = runtime.map ?: return@LaunchedEffect
        val followsLocation = if (currentUserGestureActive) showLocationMarker else ownsLocationFollow
        if (!followsLocation || !locationState.hasPermission) {
            return@LaunchedEffect
        }
        when {
            currentUserGestureActive && currentMapMode.follow == PhoneMapFollowMode.FREE -> {
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
                    preserveGestureBearing = currentUserGestureActive && currentMapMode.allowsManualRotation,
                    onCameraInput = currentOnCameraInput,
                )
            }
        }
    }
}

@Composable
private fun synchronizeOnlineHillshade(
    runtime: MapRuntime,
    enabled: Boolean,
    opacity: Float,
) {
    val effectiveOpacity = phoneMapHillshadeOpacity(enabled, opacity)
    val currentRuntime by rememberUpdatedState(runtime)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOpacity by rememberUpdatedState(effectiveOpacity)
    LaunchedEffect(runtime.map, runtime.generation.styleRevision, enabled, effectiveOpacity) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { _, _, style ->
            runCatching { style.setOnlineHillshade(currentEnabled, currentOpacity) }
                .onSuccess {
                    PhoneDebugCapture.log(
                        "PhoneOnlineDem",
                        "event=hillshade enabled=$currentEnabled opacity=$currentOpacity " +
                            "generation=${runtime.generation.renderer}",
                    )
                }.onFailure {
                    PhoneDebugCapture.log("PhoneOnlineDem", "event=hillshade_failure category=style_update")
                }
        }
    }
}

@Composable
private fun synchronizeOnlineRasterOpacity(
    runtime: MapRuntime,
    opacity: Float,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    LaunchedEffect(runtime.map, runtime.generation.styleRevision, opacity) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { _, _, style ->
            currentRuntime.basemapOpacity?.apply(opacity)
        }
    }
}

@Composable
private fun synchronizeGpxOverlay(
    runtime: MapRuntime,
    overlayState: GpxOverlayState,
    hasFittedGpxOverlay: Boolean,
    fitRequest: GpxFitRequest,
    callbacks: GpxFitCallbacks,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOverlayState by rememberUpdatedState(overlayState)
    val currentHasFittedGpxOverlay by rememberUpdatedState(hasFittedGpxOverlay)
    val currentFitRequest by rememberUpdatedState(fitRequest)
    val currentOnOverlayFitted by rememberUpdatedState(callbacks.onFitted)
    val currentOnCameraInput by rememberUpdatedState(callbacks.onCameraInput)

    LaunchedEffect(
        runtime.map,
        runtime.mapView,
        runtime.generation.styleRevision,
        overlayState.overlays,
        overlayState.isVisible,
        overlayState.settings,
        fitRequest,
    ) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { activeMap, activeMapView, style ->
            val latestOverlayState = currentOverlayState
            style.renderGpxOverlays(
                overlays = latestOverlayState.overlays,
                isVisible = latestOverlayState.isVisible,
                settings = latestOverlayState.settings,
            )
            if (
                fitRequest.isRequested &&
                fitRequest.segments.isNotEmpty() &&
                phoneMapGpxFitIsStillEligible(
                    rendererIsCurrent = runtime.isCurrentIn(currentRuntime),
                    fitOwner = fitRequest.isOwner,
                    alreadyFitted = currentHasFittedGpxOverlay,
                )
            ) {
                activeMap.fitGpxTrackBounds(
                    mapView = activeMapView,
                    segments = fitRequest.segments,
                    isCurrent = {
                        phoneMapGpxFitIsStillEligible(
                            rendererIsCurrent = runtime.isCurrentIn(currentRuntime),
                            fitOwner = currentFitRequest.isOwner && currentFitRequest.isRequested,
                            alreadyFitted = currentHasFittedGpxOverlay,
                        ) &&
                            currentFitRequest.segments == fitRequest.segments
                    },
                    onFitted = currentOnOverlayFitted,
                    onCameraInput = currentOnCameraInput,
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
    mapMode: PhoneMapMode,
    initialCamera: PhoneMapCameraSnapshot,
    onlineSource: PhoneOnlineMapSource,
    onlineProvider: OnlineMapProvider = PhoneMapRendererCatalog.mainOnlineRasterProvider,
    additionalAttribution: String? = null,
    surfaceMode: PhoneMapLibreSurfaceMode,
    isInteractive: Boolean = true,
    isVisible: Boolean = true,
    isMeasurementEnabled: () -> Boolean = { true },
    onMapTap: (PhoneMapCoordinate) -> Unit,
    onMapLongPress: (PhoneMapCoordinate) -> Unit,
    onTwoFingerTap: (PhoneMapCoordinate, PhoneMapCoordinate) -> Unit,
    onMeasurementPointMoved: (Int, PhoneMapCoordinate) -> Unit = { _, _ -> },
    distanceMeasurement: PhoneMapDistanceMeasurement? = null,
    onMapViewCreated: (MapView) -> Long,
    isRendererCurrent: (PhoneMapLibreRendererRef) -> Boolean,
    onMapReady: (Long, MapView, MapLibreMap) -> Unit,
    onStyleReady: (Long, MapView, MapLibreMap) -> Unit,
    onRendererFailure: (PhoneMapLibreRenderFailure) -> Unit,
    onRendererRecovered: (PhoneMapLibreRendererRef, PhoneMapLibreErrorCategory) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val latestMapMode = rememberUpdatedState(mapMode)
    val latestOnTwoFingerTap = rememberUpdatedState(onTwoFingerTap)
    val latestOnMeasurementPointMoved = rememberUpdatedState(onMeasurementPointMoved)
    val latestOnMapTap = rememberUpdatedState(onMapTap)
    val latestOnMapLongPress = rememberUpdatedState(onMapLongPress)
    val latestDistanceMeasurement = rememberUpdatedState(distanceMeasurement)
    val latestIsMeasurementEnabled = rememberUpdatedState(isMeasurementEnabled)
    val latestIsRendererCurrent = rememberUpdatedState(isRendererCurrent)
    val latestOnRendererFailure = rememberUpdatedState(onRendererFailure)
    val latestOnRendererRecovered = rememberUpdatedState(onRendererRecovered)
    key(surfaceMode, onlineSource, onlineProvider.id, additionalAttribution) {
        AndroidView(
            factory = { viewContext ->
                createMapView(
                    context = viewContext,
                    initialCamera = initialCamera,
                    onlineSource = onlineSource,
                    onlineProvider = onlineProvider,
                    additionalAttribution = additionalAttribution,
                    surfaceMode = surfaceMode,
                    distanceMeasurement = { latestDistanceMeasurement.value },
                    isMeasurementEnabled = { latestIsMeasurementEnabled.value() },
                    mapMode = { latestMapMode.value },
                    callbacks =
                        PhoneMapViewCallbacks(
                            onTwoFingerTap = { first, second -> latestOnTwoFingerTap.value(first, second) },
                            onMeasurementPointMoved = { index, point ->
                                latestOnMeasurementPointMoved.value(index, point)
                            },
                            onMapTap = { point -> latestOnMapTap.value(point) },
                            onMapLongPress = { point -> latestOnMapLongPress.value(point) },
                            onCreated = onMapViewCreated,
                            isRendererCurrent = { renderer -> latestIsRendererCurrent.value(renderer) },
                            onMapReady = onMapReady,
                            onStyleReady = onStyleReady,
                            onRendererFailure = { failure -> latestOnRendererFailure.value(failure) },
                            onRendererRecovered = { renderer, recoveryCategory ->
                                latestOnRendererRecovered.value(renderer, recoveryCategory)
                            },
                        ),
                ).apply {
                    visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                    isEnabled = isInteractive
                    isClickable = isInteractive
                    isLongClickable = isInteractive
                }
            },
            update = { mapView ->
                mapView.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                mapView.isEnabled = isInteractive
                mapView.isClickable = isInteractive
                mapView.isLongClickable = isInteractive
            },
            modifier = modifier,
        )
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList") // Controls are one compact UI cluster; wrappers add no state boundary.
private fun mapControls(
    onOpenMenu: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    camera: PhoneMapCameraSnapshot,
    compassHeadingDegrees: Float?,
    mapMode: PhoneMapMode,
    mapSettings: PhoneMapSettings,
    onCycleMapMode: () -> Unit,
    onToggleOfflineMode: () -> Unit,
    northIndicatorVisible: Boolean,
    showModeControl: Boolean,
    toolbarVisible: Boolean,
) {
    val toolbarHeight = if (toolbarVisible) mapToolLauncherHeight else 0.dp
    Box(modifier = Modifier.fillMaxSize()) {
        FilledTonalIconButton(
            onClick = onOpenMenu,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = stringResource(R.string.menu_open_content_description),
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
                phoneMapNorthIndicator(
                    mapBearingDegrees = camera.bearingDegrees,
                    compassHeadingDegrees = compassHeadingDegrees,
                    mapMode = mapMode,
                )
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

        if (showModeControl) {
            PhoneMapModeButton(
                mode = mapMode,
                onClick = onCycleMapMode,
                onToggleOffline = onToggleOfflineMode,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = toolbarHeight + 8.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.phoneMapRouteCreationSummary(
    state: PhoneRouteToolsUiState,
    preview: PhoneRouteSelectionPreview?,
    isMetric: Boolean,
    currentLocationAvailable: Boolean,
    actions: PhoneRouteToolsActions,
) {
    val routedPreview = state.visibleMultiPointRoutePreview()
    state.pointSelectionProgress()?.let { progress ->
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
    state.pointSelectionPhase()?.let { selectionPhase ->
        Text(text = stringResource(state.pointSelectionHintResource(selectionPhase)))
    }
    if (state.mode == PhoneRouteCreationMode.CURRENT_TO_DESTINATION && !currentLocationAvailable) {
        Text(stringResource(R.string.map_route_tools_current_location_missing))
    }
    val estimate =
        if (state.mode == PhoneRouteCreationMode.MULTI_POINT_CHAIN) {
            routedPreview?.summary
        } else {
            preview
        }
    if (state.mode == PhoneRouteCreationMode.MULTI_POINT_CHAIN) {
        when {
            state.isMultiPointPreviewLoading -> Text(stringResource(R.string.map_route_tools_preview_updating))
            estimate != null -> Text(stringResource(R.string.map_route_tools_routed_preview))
            state.multiPointPreviewMessage != null -> Text(state.multiPointPreviewMessage)
        }
    } else if (estimate != null) {
        Text(stringResource(R.string.map_route_tools_direct_estimate))
    }
    estimate?.let {
        Row(modifier = Modifier.fillMaxWidth()) {
            phoneMapRouteCreationMetric(
                label = stringResource(R.string.map_gpx_analysis_distance),
                value = formatPhoneMapMeasuredDistance(it.distanceMeters, isMetric),
            )
            phoneMapRouteCreationMetric(
                label = stringResource(R.string.map_route_tools_elevation_gain_loss),
                value = formatPhoneMapElevationGainLoss(it, isMetric),
            )
            phoneMapRouteCreationMetric(
                label = stringResource(R.string.map_gpx_analysis_time),
                value = formatPhoneMapDuration(it.estimatedDurationSeconds),
            )
        }
    }
    if (state.canCreateFromMapControls(currentLocationAvailable)) {
        TextButton(onClick = actions.onCreate, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.map_route_tools_create))
        }
    }
}

private fun formatPhoneMapElevationGainLoss(
    preview: PhoneRouteSelectionPreview,
    isMetric: Boolean,
): String {
    val ascent = preview.elevationGainMeters ?: return "—"
    val descent = preview.elevationLossMeters ?: return "—"
    return "↑${formatPhoneMapElevation(ascent, isMetric)} / ↓${formatPhoneMapElevation(descent, isMetric)}"
}

@Composable
private fun RowScope.phoneMapRouteCreationMetric(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(text = label)
        Text(text = value)
    }
}

@Composable
private fun BoxScope.phoneMapRouteSelectionControls(
    state: PhoneRouteToolsUiState,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSelectCenter: () -> Unit,
    toolbarVisible: Boolean,
) {
    val toolbarHeight = if (toolbarVisible) mapToolLauncherHeight else 0.dp
    Row(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = toolbarHeight + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onSelectCenter,
            enabled = state.pointSelectionPhase() != null,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.map_route_tools_select_center_content_description),
            )
        }
        FilledTonalIconButton(
            onClick = onUndo,
            enabled = state.canUndoLastMapPoint(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.map_route_tools_undo_point_content_description),
            )
        }
        FilledTonalIconButton(
            onClick = onClear,
            enabled = state.canUndoLastMapPoint(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.map_route_tools_clear_points_content_description),
            )
        }
    }
}

@Composable
private fun phoneMapNorthIndicator(
    mapBearingDegrees: Float,
    compassHeadingDegrees: Float?,
    mapMode: PhoneMapMode,
) {
    val northBearing =
        if (mapMode.orientation == PhoneMapOrientation.NORTH_UP && mapMode.manualBearingDegrees == null) {
            compassHeadingDegrees ?: mapBearingDegrees
        } else {
            mapBearingDegrees
        }
    Image(
        painter = painterResource(R.drawable.map_north_indicator),
        contentDescription = stringResource(R.string.map_north_indicator_content_description),
        modifier =
            Modifier
                .size(48.dp)
                .rotate(phoneMapNorthIndicatorRotationDegrees(northBearing)),
    )
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

    if (liveDistanceEnabled) phoneMapLiveDistanceGuide(position)
    if (liveElevationEnabled) {
        phoneMapLiveElevationCard(elevationMeters, isMetric)
    }
    liveDistance?.let { distance ->
        phoneMapLiveDistanceCard(distance, isMetric)
    }
}

@Composable
private fun BoxScope.phoneMapLiveDistanceGuide(position: PhoneMapLiveMetricsPosition) {
    val density = LocalDensity.current
    val reticleStroke = with(density) { 2.dp.toPx() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
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

@Suppress(
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
) // Renderer construction keeps SDK callbacks and gesture ownership in one lifecycle boundary.
private fun createMapView(
    context: Context,
    initialCamera: PhoneMapCameraSnapshot,
    onlineSource: PhoneOnlineMapSource,
    onlineProvider: OnlineMapProvider,
    additionalAttribution: String?,
    surfaceMode: PhoneMapLibreSurfaceMode,
    distanceMeasurement: () -> PhoneMapDistanceMeasurement?,
    isMeasurementEnabled: () -> Boolean,
    mapMode: () -> PhoneMapMode,
    callbacks: PhoneMapViewCallbacks,
): MapView {
    ensureMapLibreConfigured(context)
    var mapForProjection: MapLibreMap? = null
    val mapView =
        if (phoneMapLibreSurfaceUsesTexture(surfaceMode)) {
            MapView(
                context,
                MapLibreMapOptions
                    .createFromAttributes(context)
                    .textureMode(true)
                    .translucentTextureSurface(true),
            )
        } else {
            MapView(context)
        }
    if (surfaceMode == PhoneMapLibreSurfaceMode.COMPARISON) {
        PhoneDebugCapture.log(
            PHONE_MAP_LIBRE_DIAGNOSTICS_TAG,
            "event=comparison_renderer_created comparison_maplibre_surface=texture source=${onlineProvider.id}",
        )
    }
    return mapView.also { mapView ->
        var suppressNextMapTap = false

        fun publishTwoFingerMeasurement(
            firstX: Float,
            firstY: Float,
            secondX: Float,
            secondY: Float,
        ) {
            mapForProjection?.let { map ->
                runCatching {
                    val first = map.projection.fromScreenLocation(PointF(firstX, firstY))
                    val second = map.projection.fromScreenLocation(PointF(secondX, secondY))
                    callbacks.onTwoFingerTap(
                        PhoneMapCoordinate(first.latitude, first.longitude),
                        PhoneMapCoordinate(second.latitude, second.longitude),
                    )
                }
            }
        }

        fun measurementHandleAt(
            x: Float,
            y: Float,
        ): Int? =
            mapForProjection?.let { map ->
                distanceMeasurement()?.let { measurement ->
                    fun screenPoint(point: PhoneMapCoordinate): PhoneMapScreenPoint? =
                        runCatching {
                            map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                        }.getOrNull()?.let { value -> PhoneMapScreenPoint(value.x, value.y) }
                    phoneMapMeasurementHandleIndex(
                        first = screenPoint(measurement.first),
                        second = screenPoint(measurement.second),
                        x = x,
                        y = y,
                        maxDistancePx = 36f * context.resources.displayMetrics.density,
                    )
                }
            }

        fun publishMeasurementPointMove(
            index: Int,
            x: Float,
            y: Float,
        ) {
            mapForProjection?.let { map ->
                runCatching {
                    val point = map.projection.fromScreenLocation(PointF(x, y))
                    callbacks.onMeasurementPointMoved(
                        index,
                        PhoneMapCoordinate(point.latitude, point.longitude),
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
        val twoFingerTapDetector =
            PhoneTwoFingerTapDetector(
                context = context,
                onTwoFingerTap = ::publishTwoFingerMeasurement,
                onTwoFingerMove = ::publishTwoFingerMeasurement,
                measurementHandleAt = ::measurementHandleAt,
                onMeasurementPointMove = ::publishMeasurementPointMove,
                isMeasurementEnabled = {
                    val gestures = mapForProjection?.gesturesManager
                    isMeasurementEnabled() &&
                        gestures?.standardScaleGestureDetector?.isInProgress != true &&
                        gestures?.rotateGestureDetector?.isInProgress != true
                },
                onMeasurementGestureStart = {
                    mapView.cancelPhoneMapNativeGesture()
                    longPressDetector.cancelPhoneMapLongPress()
                },
                onMeasurementPointDragEnd = { cancelled ->
                    if (!cancelled) suppressNextMapTap = true
                },
            )
        val centeredGestures = PhoneOnlineCenteredGestures(mapView, { mapForProjection }, mapMode)
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) suppressNextMapTap = false
            centeredGestures.onTouch(event)
            val measurementOwnsGesture = twoFingerTapDetector.onTouchEvent(event)
            if (measurementOwnsGesture) return@setOnTouchListener true
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                longPressDetector.cancelPhoneMapLongPress(event)
            }
            if (event.pointerCount == 1) {
                longPressDetector.onTouchEvent(event)
            }
            false
        }
        mapView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    centeredGestures.reset()
                    twoFingerTapDetector.reset()
                    longPressDetector.cancelPhoneMapLongPress()
                }
            },
        )
        val generation = callbacks.onCreated(mapView)
        val role =
            if (surfaceMode == PhoneMapLibreSurfaceMode.COMPARISON) {
                PhoneMapLibreRendererRole.COMPARISON
            } else {
                PhoneMapLibreRendererRole.BASE
            }
        val rendererIdentity =
            PhoneMapLibreRenderDiagnostics.rendererCreated(
                role = role,
                generation = generation,
                source = onlineSource,
                providerId = onlineProvider.id,
            )
        val renderer = PhoneMapLibreRendererRef(rendererIdentity, generation, mapView, onlineSource, onlineProvider.id)
        val mapLoadFailureListener =
            MapView.OnDidFailLoadingMapListener { _ ->
                if (callbacks.isRendererCurrent(renderer)) {
                    PhoneMapLibreRenderDiagnostics.failure(renderer.identity, PhoneMapLibreErrorCategory.MAP_LOAD)
                }
                callbacks.onRendererFailure(
                    PhoneMapLibreRenderFailure(renderer, PhoneMapLibreErrorCategory.MAP_LOAD),
                )
            }
        val renderFailureListener =
            MapView.OnRenderErrorListener {
                if (callbacks.isRendererCurrent(renderer)) {
                    PhoneMapLibreRenderDiagnostics.failure(renderer.identity, PhoneMapLibreErrorCategory.RENDER)
                }
                callbacks.onRendererFailure(
                    PhoneMapLibreRenderFailure(renderer, PhoneMapLibreErrorCategory.RENDER),
                )
            }
        val renderFrameFinishedListener =
            MapView.OnDidFinishRenderingMapListener { fullyRendered ->
                if (callbacks.isRendererCurrent(renderer)) {
                    PhoneMapLibreRenderDiagnostics.renderFrameFinished(renderer.identity, fullyRendered)
                    if (fullyRendered) {
                        callbacks.onRendererRecovered(renderer, PhoneMapLibreErrorCategory.RENDER)
                    }
                }
            }
        // MapLibre 13.6.1 provides the operation, but not HTTP status; discard tile coordinates and source.
        val tileActionListener =
            MapView.OnTileActionListener { operation, _, _, _, _, _, _ ->
                if (
                    operation == org.maplibre.android.tile.TileOperation.Error &&
                    callbacks.isRendererCurrent(renderer)
                ) {
                    PhoneMapLibreRenderDiagnostics.resourceFailure(
                        renderer.identity,
                        PhoneMapLibreResourceFailureKind.TILE,
                    )
                }
            }
        val glyphsErrorListener =
            MapView.OnGlyphsErrorListener { _, _, _ ->
                if (callbacks.isRendererCurrent(renderer)) {
                    PhoneMapLibreRenderDiagnostics.resourceFailure(
                        renderer.identity,
                        PhoneMapLibreResourceFailureKind.GLYPHS,
                    )
                }
            }
        val spriteErrorListener =
            MapView.OnSpriteErrorListener { _, _ ->
                if (callbacks.isRendererCurrent(renderer)) {
                    PhoneMapLibreRenderDiagnostics.resourceFailure(
                        renderer.identity,
                        PhoneMapLibreResourceFailureKind.SPRITE,
                    )
                }
            }
        val styleLoadedListener =
            MapView.OnDidFinishLoadingStyleListener {
                if (callbacks.isRendererCurrent(renderer)) {
                    PhoneMapLibreRenderDiagnostics.styleLoadCompleted(renderer.identity)
                }
                callbacks.onRendererRecovered(renderer, PhoneMapLibreErrorCategory.MAP_LOAD)
            }
        mapView.addOnDidFailLoadingMapListener(mapLoadFailureListener)
        mapView.addOnRenderErrorListener(renderFailureListener)
        mapView.addOnDidFinishRenderingMapListener(renderFrameFinishedListener)
        mapView.addOnTileActionListener(tileActionListener)
        mapView.addOnGlyphsErrorListener(glyphsErrorListener)
        mapView.addOnSpriteErrorListener(spriteErrorListener)
        mapView.addOnDidFinishLoadingStyleListener(styleLoadedListener)
        mapView.tag =
            PhoneMapTouchDetectorCleanup(
                twoFingerTapDetector = twoFingerTapDetector,
                longPressDetector = longPressDetector,
                onDispose = {
                    mapView.removeOnDidFailLoadingMapListener(mapLoadFailureListener)
                    mapView.removeOnRenderErrorListener(renderFailureListener)
                    mapView.removeOnDidFinishRenderingMapListener(renderFrameFinishedListener)
                    mapView.removeOnTileActionListener(tileActionListener)
                    mapView.removeOnGlyphsErrorListener(glyphsErrorListener)
                    mapView.removeOnSpriteErrorListener(spriteErrorListener)
                    mapView.removeOnDidFinishLoadingStyleListener(styleLoadedListener)
                    PhoneMapLibreRenderDiagnostics.rendererDisposed(renderer.identity)
                },
            )
        mapView.onCreate(null)
        mapView.getMapAsync { map ->
            if (mapView.isDestroyed) return@getMapAsync
            mapForProjection = map
            map.uiSettings.setCompassEnabled(false)
            map.uiSettings.setTiltGesturesEnabled(false)
            callbacks.onMapReady(generation, mapView, map)
            map.addOnMapClickListener { point ->
                if (suppressNextMapTap) {
                    suppressNextMapTap = false
                } else {
                    callbacks.onMapTap(PhoneMapCoordinate(point.latitude, point.longitude))
                }
                false
            }
            val initialStyle = onlineProvider.mapLibreOnlineStyle(additionalAttribution)
            PhoneMapLibreRenderDiagnostics.styleLoadStarted(renderer.identity)
            map.setStyle(initialStyle) { style ->
                if (!mapView.isDestroyed) {
                    style.prepareOnlineAttribution(onlineProvider, additionalAttribution)
                    map.moveCamera(
                        initialCamera.toMapLibreCameraUpdate(
                            activeMap = map,
                            pixelRatio = mapView.pixelRatio.toDouble(),
                        ),
                    )
                    callbacks.onStyleReady(generation, mapView, map)
                }
            }
        }
    }
}

/** Keeps MapLibre's ordered lifecycle callbacks together rather than splitting the state machine. */
@Suppress("CyclomaticComplexMethod", "DEPRECATION", "OVERRIDE_DEPRECATION")
@Composable
internal fun mapViewLifecycle(
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
                (mapView.tag as? PhoneMapTouchDetectorCleanup)?.dispose()
                mapView.tag = null
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
            LocationComponentActivationOptions
                .builder(context.applicationContext, style)
                .useDefaultLocationEngine(false)
                .build(),
        )
    }
    locationComponent.isLocationComponentEnabled = true
    locationComponent.cameraMode = CameraMode.NONE
    locationComponent.compassEngine = compassSource
    locationComponent.renderMode = if (compassRenderable) RenderMode.COMPASS else RenderMode.NORMAL
    locationComponent.applyStyle(
        locationComponent.getLocationComponentOptions().withPhoneLocationMarker(mapSettings, compassRenderable),
    )
}

private fun MapLibreMap.disableLocationPuck() {
    try {
        locationComponent.setLocationComponentEnabled(false)
    } catch (_: SecurityException) {
        PhoneDebugCapture.log("PhoneMapLocation", "event=puck_disable_failed category=permission")
    }
    locationComponent.setLocationEngine(null)
    locationComponent.setCompassEngine(null)
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
    preserveGestureBearing: Boolean = false,
    onCameraInput: () -> Unit,
) {
    if (follow == PhoneMapFollowMode.FREE) return
    val current = cameraPosition
    val next =
        CameraPosition
            .Builder(current)
            .bearing(if (preserveGestureBearing) current.bearing else compassPresentation.mapBearingDegrees.toDouble())
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
        onCameraInput()
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

internal fun PhoneMapLocation.toAndroidLocation(): android.location.Location =
    android.location.Location("phone-map").apply {
        latitude = this@toAndroidLocation.latitude
        longitude = this@toAndroidLocation.longitude
        val metadata = this@toAndroidLocation.toAndroidLocationMetadata()
        metadata.accuracyMeters?.let(::setAccuracy)
        metadata.altitudeMeters?.let(::setAltitude)
        metadata.elapsedRealtimeNanos?.let { elapsedRealtimeNanos = it }
    }

internal fun PhoneMapCameraSnapshot.toMapLibreCameraUpdate(
    activeMap: MapLibreMap? = null,
    pixelRatio: Double = 1.0,
): CameraUpdate {
    val targetZoom =
        groundResolutionMetersPerPixel?.let { resolution ->
            activeMap?.let { map ->
                val currentResolution =
                    map.projection.getMetersPerPixelAtLatitude(latitude) / pixelRatio
                map.cameraPosition.zoom + log2(currentResolution / resolution)
            }
        } ?: zoom
    val cameraPosition =
        CameraPosition
            .Builder()
            .target(LatLng(latitude, longitude))
            .zoom(targetZoom)
            .bearing(bearingDegrees.toDouble())
            .tilt(activeMap?.cameraPosition?.tilt ?: 0.0)
            .build()
    return CameraUpdateFactory.newCameraPosition(cameraPosition)
}

private fun MapLibreMap.cameraSnapshotOrNull(pixelRatio: Double = 1.0): PhoneMapCameraSnapshot? =
    runCatching {
        val camera = cameraPosition
        val target = camera.target ?: return@runCatching null
        PhoneMapCameraSnapshot(
            latitude = target.latitude,
            longitude = target.longitude,
            zoom = camera.zoom,
            bearingDegrees = camera.bearing.toFloat(),
            groundResolutionMetersPerPixel =
                projection.getMetersPerPixelAtLatitude(target.latitude) / pixelRatio,
        )
    }.getOrNull()
