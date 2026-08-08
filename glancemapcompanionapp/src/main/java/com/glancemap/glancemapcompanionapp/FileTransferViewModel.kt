package com.glancemap.glancemapcompanionapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.activehike.PhoneActiveHikeSnapshot
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionDiagnosticsEmailComposer
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCaptureState
import com.glancemap.glancemapcompanionapp.refuges.GpxWaypointPoiImporter
import com.glancemap.glancemapcompanionapp.refuges.MapsforgeMapBoundsParser
import com.glancemap.glancemapcompanionapp.refuges.OsmOverpassPoiImporter
import com.glancemap.glancemapcompanionapp.refuges.PoiFileMerger
import com.glancemap.glancemapcompanionapp.refuges.RefugesGeoJsonPoiImporter
import com.glancemap.glancemapcompanionapp.refuges.RefugesImportRequest
import com.glancemap.glancemapcompanionapp.refuges.RefugesImportResult
import com.glancemap.glancemapcompanionapp.refuges.RefugesRegionPreset
import com.glancemap.glancemapcompanionapp.refuges.RefugesRegionPresetMode
import com.glancemap.glancemapcompanionapp.refuges.RefugesRegionPresetRepository
import com.glancemap.glancemapcompanionapp.refuges.defaultOsmPoiCategoryIds
import com.glancemap.glancemapcompanionapp.routing.BRouterTileDownloader
import com.glancemap.glancemapcompanionapp.routing.RoutingDownloadRequest
import com.glancemap.glancemapcompanionapp.transfer.WatchInstalledMapsRequester
import com.glancemap.glancemapcompanionapp.transfer.service.FileTransferService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.ArrayList

enum class PoiImportSource {
    REFUGES,
    OSM,
}

data class PoiImportProgressState(
    val isRunning: Boolean = false,
    val progressPercent: Int = 0,
    val status: String = "",
    val completed: Boolean = false,
    val success: Boolean = false,
    val message: String = "",
)

data class RoutingDownloadProgressState(
    val isRunning: Boolean = false,
    val progressPercent: Int = 0,
    val status: String = "",
    val detail: String = "",
    val completed: Boolean = false,
    val success: Boolean = false,
    val message: String = "",
)

data class GeneratedPhoneFile(
    val uri: Uri,
    val fileName: String,
)

class FileTransferViewModel : ViewModel() {
    companion object {
        private const val MAX_GEOJSON_IMPORT_BYTES = 12L * 1024L * 1024L // 12MB
        private const val MAX_GPX_WAYPOINT_IMPORT_BYTES = 12L * 1024L * 1024L // 12MB
        private const val REFUGES_DIRECT_STAGE_START_PERCENT = 0
        private const val REFUGES_DIRECT_STAGE_END_PERCENT = 85
        private const val REFUGES_ENRICH_STAGE_START_PERCENT = 0
        private const val REFUGES_ENRICH_STAGE_END_PERCENT = 55
        private const val OSM_DIRECT_STAGE_START_PERCENT = 0
        private const val OSM_DIRECT_STAGE_END_PERCENT = 85
        private const val OSM_ENRICH_STAGE_START_PERCENT = 60
        private const val OSM_ENRICH_STAGE_END_PERCENT = 75
    }

    private var serviceRef: WeakReference<FileTransferService>? = null
    var isBound = false
        private set
    private var isBinding = false
    private var acceptsServiceConnection = false

    // ✅ buffer multi-selection if service not yet bound
    private var pendingFileUris: List<Uri> = emptyList()

    private val _uiState = MutableStateFlow(FileTransferUiState())
    val uiState: StateFlow<FileTransferUiState> = _uiState.asStateFlow()

    private val _activeHikeSnapshot = MutableStateFlow<PhoneActiveHikeSnapshot?>(null)
    val activeHikeSnapshot: StateFlow<PhoneActiveHikeSnapshot?> = _activeHikeSnapshot.asStateFlow()

    private val _isImportingRefuges = MutableStateFlow(false)
    val isImportingRefuges: StateFlow<Boolean> = _isImportingRefuges.asStateFlow()

    private val _poiImportProgress = MutableStateFlow(PoiImportProgressState())
    val poiImportProgress: StateFlow<PoiImportProgressState> = _poiImportProgress.asStateFlow()

    private val _lastRefugesRequest = MutableStateFlow<RefugesImportRequest?>(null)
    val lastRefugesRequest: StateFlow<RefugesImportRequest?> = _lastRefugesRequest.asStateFlow()

    private val _isDownloadingRouting = MutableStateFlow(false)
    val isDownloadingRouting: StateFlow<Boolean> = _isDownloadingRouting.asStateFlow()

    private val _routingDownloadProgress = MutableStateFlow(RoutingDownloadProgressState())
    val routingDownloadProgress: StateFlow<RoutingDownloadProgressState> =
        _routingDownloadProgress.asStateFlow()

    private val _lastRoutingRequest = MutableStateFlow<RoutingDownloadRequest?>(null)
    val lastRoutingRequest: StateFlow<RoutingDownloadRequest?> = _lastRoutingRequest.asStateFlow()

    private val _refugesRegionPresets =
        MutableStateFlow(RefugesRegionPresetRepository.defaultPresets())
    val refugesRegionPresets: StateFlow<List<RefugesRegionPreset>> = _refugesRegionPresets.asStateFlow()
    private val _useDetailedRefugesRegionPresets = MutableStateFlow(false)
    val useDetailedRefugesRegionPresets: StateFlow<Boolean> =
        _useDetailedRefugesRegionPresets.asStateFlow()

    private val _watchInstalledMaps = MutableStateFlow<List<WatchInstalledMap>>(emptyList())
    val watchInstalledMaps: StateFlow<List<WatchInstalledMap>> = _watchInstalledMaps.asStateFlow()

    private val _watchInstalledCoverageAreas = MutableStateFlow<List<WatchInstalledCoverageArea>>(emptyList())
    val watchInstalledCoverageAreas: StateFlow<List<WatchInstalledCoverageArea>> =
        _watchInstalledCoverageAreas.asStateFlow()

    private val _isLoadingWatchInstalledMaps = MutableStateFlow(false)
    val isLoadingWatchInstalledMaps: StateFlow<Boolean> = _isLoadingWatchInstalledMaps.asStateFlow()

    private val _watchInstalledMapsStatusMessage = MutableStateFlow<String?>(null)
    val watchInstalledMapsStatusMessage: StateFlow<String?> =
        _watchInstalledMapsStatusMessage.asStateFlow()

    private val _lastImportedPoiFile = MutableStateFlow<GeneratedPhoneFile?>(null)
    val lastImportedPoiFile: StateFlow<GeneratedPhoneFile?> = _lastImportedPoiFile.asStateFlow()

    private val _lastRoutingDownloadedFiles = MutableStateFlow<List<GeneratedPhoneFile>>(emptyList())
    val lastRoutingDownloadedFiles: StateFlow<List<GeneratedPhoneFile>> =
        _lastRoutingDownloadedFiles.asStateFlow()

    val debugCaptureState: StateFlow<PhoneDebugCaptureState> = PhoneDebugCapture.state

    private var refugesImporter: RefugesGeoJsonPoiImporter? = null
    private var osmOverpassImporter: OsmOverpassPoiImporter? = null
    private var gpxWaypointPoiImporter: GpxWaypointPoiImporter? = null
    private var poiFileMerger: PoiFileMerger? = null
    private var refugesRegionPresetRepository: RefugesRegionPresetRepository? = null
    private var brouterTileDownloader: BRouterTileDownloader? = null
    private var hasLoadedRefugesPresets = false
    private var loadedRefugesRegionPresetMode: RefugesRegionPresetMode? = null
    private var pendingWatchMapsRefresh = false

    private var stateCollectJob: Job? = null
    private var activeHikeSnapshotCollectJob: Job? = null
    private var lastRequestsLoadJob: Job? = null
    private var poiImportJob: Job? = null
    private var routingDownloadJob: Job? = null

    private val connection =
        object : ServiceConnection {
            @Suppress("LongMethod")
            override fun onServiceConnected(
                className: ComponentName,
                binder: IBinder,
            ) {
                isBinding = false
                if (!acceptsServiceConnection) return
                val localBinder = binder as? FileTransferService.LocalBinder
                if (localBinder == null) {
                    Log.e("FileTransferVM", "Unexpected binder type: ${binder::class.java.name}")
                    isBound = false
                    acceptsServiceConnection = false
                    serviceRef = null
                    return
                }
                val boundService = localBinder.getService()
                serviceRef = WeakReference(boundService)
                isBound = true

                // ✅ Prevent multiple collectors
                stateCollectJob?.cancel()
                stateCollectJob =
                    viewModelScope.launch {
                        var previousSelectedWatchId: String? = null
                        var previousSelectedWatchReachable = false
                        boundService.uiState.collect { serviceState ->
                            _uiState.value = serviceState
                            val currentSelectedWatchId = serviceState.selectedWatch?.id
                            val currentSelectedWatchReachable =
                                isSelectedWatchReachable(
                                    selectedWatch = serviceState.selectedWatch,
                                    availableWatches = serviceState.availableWatches,
                                )
                            val selectedChanged = currentSelectedWatchId != previousSelectedWatchId
                            val reachabilityChanged =
                                currentSelectedWatchReachable != previousSelectedWatchReachable

                            if (selectedChanged || reachabilityChanged) {
                                _watchInstalledMapsStatusMessage.value = null
                            }
                            if (selectedChanged || (reachabilityChanged && !currentSelectedWatchReachable)) {
                                _watchInstalledMaps.value = emptyList()
                                _watchInstalledCoverageAreas.value = emptyList()
                            }

                            previousSelectedWatchId = currentSelectedWatchId
                            previousSelectedWatchReachable = currentSelectedWatchReachable
                        }
                    }

                activeHikeSnapshotCollectJob?.cancel()
                activeHikeSnapshotCollectJob =
                    viewModelScope.launch {
                        boundService.activeHikeSnapshot.collect { snapshot ->
                            _activeHikeSnapshot.value = snapshot
                        }
                    }

                // ✅ Apply pending multi-selection
                if (pendingFileUris.isNotEmpty()) {
                    if (!_uiState.value.isTransferring) {
                        boundService.loadFilesFromUris(boundService, pendingFileUris)
                    }
                    pendingFileUris = emptyList()
                }
                if (pendingWatchMapsRefresh && _uiState.value.selectedWatch != null) {
                    refreshWatchInstalledMaps(
                        context = boundService,
                        showToastIfUnavailable = false,
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                clearServiceCollectors()
                isBound = false
                isBinding = false
                acceptsServiceConnection = false
                serviceRef = null
            }
        }

    fun bindService(context: Context) {
        val appContext = context.applicationContext
        loadLastRequests(appContext)
        if (isBound || isBinding) return

        acceptsServiceConnection = true
        val intent = Intent(appContext, FileTransferService::class.java)
        isBinding = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!isBinding) {
            acceptsServiceConnection = false
            Log.w("FileTransferVM", "Unable to bind FileTransferService")
        }
    }

    fun unbindService(context: Context) {
        if (!isBound && !isBinding) return
        acceptsServiceConnection = false
        runCatching { context.applicationContext.unbindService(connection) }
        clearServiceCollectors()
        isBound = false
        isBinding = false
        serviceRef = null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadLastRequests(context: Context) {
        if (
            (_lastRefugesRequest.value != null && _lastRoutingRequest.value != null) ||
            lastRequestsLoadJob?.isActive == true
        ) {
            return
        }
        lastRequestsLoadJob =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (_lastRefugesRequest.value == null) {
                        _lastRefugesRequest.value = getRefugesImporter(context).getLastRequest()
                    }
                    if (_lastRoutingRequest.value == null) {
                        _lastRoutingRequest.value = getBRouterTileDownloader(context).getLastRequest()
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Log.w("FileTransferVM", "Unable to restore previous requests", error)
                }
            }
    }

    private fun clearServiceCollectors() {
        stateCollectJob?.cancel()
        stateCollectJob = null
        activeHikeSnapshotCollectJob?.cancel()
        activeHikeSnapshotCollectJob = null
    }

    fun findWatchNodes() {
        serviceRef?.get()?.searchForWatches()
    }

    fun onWatchSelected(
        context: Context,
        watch: WatchNode,
    ) {
        serviceRef?.get()?.onWatchSelected(watch)
        _uiState.value = _uiState.value.copy(selectedWatch = watch)
        _watchInstalledMaps.value = emptyList()
        _watchInstalledCoverageAreas.value = emptyList()
        _watchInstalledMapsStatusMessage.value = null
        refreshWatchInstalledMaps(
            context = context.applicationContext,
            showToastIfUnavailable = false,
        )
    }

    // ✅ multi-load from picker
    fun loadFilesFromUris(
        context: Context,
        uris: List<Uri>,
    ) {
        if (_uiState.value.isTransferring) {
            Toast.makeText(context, "Transfer in progress. Please wait or cancel.", Toast.LENGTH_SHORT).show()
            return
        }
        if (uris.isEmpty()) return

        val appContext = context.applicationContext
        viewModelScope.launch {
            val prepared =
                runCatching {
                    withContext(Dispatchers.IO) {
                        prepareSelectedUrisForTransfer(
                            context = appContext,
                            uris = uris,
                            refugesImporter = getRefugesImporter(appContext),
                            gpxWaypointPoiImporter = getGpxWaypointPoiImporter(appContext),
                            maxGeoJsonImportBytes = MAX_GEOJSON_IMPORT_BYTES,
                            maxGpxWaypointImportBytes = MAX_GPX_WAYPOINT_IMPORT_BYTES,
                        )
                    }
                }.getOrElse { error ->
                    val message =
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Failed to read selected file(s)."
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                    return@launch
                }

            if (prepared.convertedGeoJsonCount > 0) {
                Toast
                    .makeText(
                        appContext,
                        "Converted ${prepared.convertedGeoJsonCount} file(s) to .poi",
                        Toast.LENGTH_SHORT,
                    ).show()
            }
            if (prepared.extractedPoiFromGpxCount > 0) {
                val message =
                    if (prepared.extractedPoiFromMixedGpxCount > 0) {
                        "Extracted POI from ${prepared.extractedPoiFromGpxCount} GPX file(s), route kept when present."
                    } else {
                        "Converted ${prepared.extractedPoiFromGpxCount} waypoint GPX file(s) to .poi"
                    }
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }

            deliverSelectedUris(appContext, prepared.uris)
        }
    }

    private fun deliverSelectedUris(
        context: Context,
        uris: List<Uri>,
    ) {
        if (isBound && serviceRef?.get() != null) {
            serviceRef?.get()?.loadFilesFromUris(context, uris)
        } else {
            Log.d("FileTransferVM", "Service not ready. Buffering URIs.")
            pendingFileUris = uris
        }
    }

    fun sendFiles(context: Context) {
        val uris = _uiState.value.selectedFileUris
        val watch = _uiState.value.selectedWatch

        if (uris.isEmpty() || watch == null) {
            Log.e("FileTransferVM", "Missing URIs or watch.")
            return
        }

        val intent =
            Intent(context, FileTransferService::class.java).apply {
                action = FileTransferService.ACTION_START_MULTI
                putParcelableArrayListExtra(FileTransferService.EXTRA_FILE_URIS, ArrayList(uris))
                putExtra(FileTransferService.EXTRA_NODE_ID, watch.id)
                putExtra(FileTransferService.EXTRA_NODE_DISPLAY_NAME, watch.displayName)
            }

        ContextCompat.startForegroundService(context, intent)
    }

    fun loadRefugesDefaults(context: Context) {
        val appContext = context.applicationContext

        if (_lastRefugesRequest.value == null) {
            _lastRefugesRequest.value = getRefugesImporter(appContext).getLastRequest()
        }
        if (_lastRoutingRequest.value == null) {
            _lastRoutingRequest.value = getBRouterTileDownloader(appContext).getLastRequest()
        }

        val mode =
            if (_useDetailedRefugesRegionPresets.value) {
                RefugesRegionPresetMode.DETAILED_MASSIFS
            } else {
                RefugesRegionPresetMode.COMPACT_ZONES
            }
        if (hasLoadedRefugesPresets && loadedRefugesRegionPresetMode == mode) return
        loadRefugesRegionPresets(appContext, mode = mode, forceRefresh = false)
    }

    fun setUseDetailedRefugesRegionPresets(
        context: Context,
        enabled: Boolean,
    ) {
        val mode =
            if (enabled) {
                RefugesRegionPresetMode.DETAILED_MASSIFS
            } else {
                RefugesRegionPresetMode.COMPACT_ZONES
            }
        if (
            _useDetailedRefugesRegionPresets.value == enabled &&
            hasLoadedRefugesPresets &&
            loadedRefugesRegionPresetMode == mode
        ) {
            return
        }
        _useDetailedRefugesRegionPresets.value = enabled
        loadRefugesRegionPresets(context.applicationContext, mode = mode, forceRefresh = false)
    }

    fun refreshWatchInstalledMaps(
        context: Context,
        showToastIfUnavailable: Boolean = true,
    ) {
        val selectedWatch = _uiState.value.selectedWatch
        if (selectedWatch == null) {
            pendingWatchMapsRefresh = false
            _watchInstalledMaps.value = emptyList()
            _watchInstalledCoverageAreas.value = emptyList()
            _watchInstalledMapsStatusMessage.value = null
            return
        }
        if (!isSelectedWatchReachable(selectedWatch, _uiState.value.availableWatches)) {
            pendingWatchMapsRefresh = false
            _watchInstalledMaps.value = emptyList()
            _watchInstalledCoverageAreas.value = emptyList()
            val message = selectedWatchDisconnectedStatusMessage()
            _watchInstalledMapsStatusMessage.value = message
            if (showToastIfUnavailable) {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val service = serviceRef?.get()
        if (!isBound || service == null) {
            pendingWatchMapsRefresh = true
            if (showToastIfUnavailable) {
                Toast.makeText(context, "Connect to watch first.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        pendingWatchMapsRefresh = false
        if (_isLoadingWatchInstalledMaps.value) return

        viewModelScope.launch {
            _isLoadingWatchInstalledMaps.value = true
            _watchInstalledMapsStatusMessage.value = null
            try {
                when (
                    val result =
                        withContext(Dispatchers.IO) {
                            service.requestInstalledMaps(selectedWatch.id)
                        }
                ) {
                    is WatchInstalledMapsRequester.Result.Success -> {
                        _watchInstalledMaps.value =
                            result.maps
                                .filter { it.bbox.isNotBlank() }
                                .sortedBy { it.fileName.lowercase() }
                        _watchInstalledCoverageAreas.value =
                            result.coverageAreas
                                .filter { it.bbox.isNotBlank() }
                                .sortedWith(
                                    compareBy<WatchInstalledCoverageArea> { it.kind.name }
                                        .thenBy { it.fileName.lowercase() },
                                )
                    }

                    is WatchInstalledMapsRequester.Result.Timeout -> {
                        _watchInstalledMaps.value = emptyList()
                        _watchInstalledCoverageAreas.value = emptyList()
                        _watchInstalledMapsStatusMessage.value = selectedWatchTimeoutStatusMessage()
                    }

                    is WatchInstalledMapsRequester.Result.Error -> {
                        _watchInstalledMaps.value = emptyList()
                        _watchInstalledCoverageAreas.value = emptyList()
                        val message = normalizeWatchMapsStatusMessage(result.exception)
                        _watchInstalledMapsStatusMessage.value = message
                        Toast
                            .makeText(
                                context.applicationContext,
                                message,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            } catch (e: Exception) {
                _watchInstalledMaps.value = emptyList()
                _watchInstalledCoverageAreas.value = emptyList()
                val message = normalizeWatchMapsStatusMessage(e)
                _watchInstalledMapsStatusMessage.value = message
                Toast
                    .makeText(
                        context.applicationContext,
                        message,
                        Toast.LENGTH_SHORT,
                    ).show()
            } finally {
                _isLoadingWatchInstalledMaps.value = false
            }
        }
    }

    fun importRefugesByBbox(
        context: Context,
        bbox: String,
        fileName: String,
        selectedTypeIds: Set<Int> = RefugesGeoJsonPoiImporter.defaultPointTypeIds(),
        appendToSelection: Boolean = true,
    ) {
        importPoiByBbox(
            context = context,
            bbox = bbox,
            fileName = fileName,
            source = PoiImportSource.REFUGES,
            enrichWithOsm = false,
            selectedRefugesTypeIds = selectedTypeIds,
            appendToSelection = appendToSelection,
        )
    }

    fun importPoiByBbox(
        context: Context,
        bbox: String,
        fileName: String,
        source: PoiImportSource,
        enrichWithOsm: Boolean,
        selectedRefugesTypeIds: Set<Int> = RefugesGeoJsonPoiImporter.defaultPointTypeIds(),
        selectedOsmCategoryIds: Set<String> = defaultOsmPoiCategoryIds(),
        appendToSelection: Boolean = true,
    ) {
        runRefugesImport(context, appendToSelection) { refugesImporter, reportProgress ->
            val appContext = context.applicationContext
            when (source) {
                PoiImportSource.REFUGES -> {
                    val refugesResult =
                        refugesImporter.importFromBbox(
                            bboxInput = bbox,
                            fileNameInput = fileName,
                            typePointIds = selectedRefugesTypeIds,
                            reportProgress = { percent, status ->
                                reportProgress(
                                    scaleStageProgress(
                                        percent = percent,
                                        start =
                                            if (enrichWithOsm) {
                                                REFUGES_ENRICH_STAGE_START_PERCENT
                                            } else {
                                                REFUGES_DIRECT_STAGE_START_PERCENT
                                            },
                                        end =
                                            if (enrichWithOsm) {
                                                REFUGES_ENRICH_STAGE_END_PERCENT
                                            } else {
                                                REFUGES_DIRECT_STAGE_END_PERCENT
                                            },
                                    ),
                                    status,
                                )
                            },
                        )
                    if (!enrichWithOsm) {
                        refugesResult
                    } else {
                        reportProgress(60, "Importing OSM data for enrichment…")
                        val osmTempFileName = buildOsmEnrichTempFileName(refugesResult.fileName)
                        val osmResult =
                            getOsmImporter(appContext).importFromBbox(
                                bboxInput = bbox,
                                fileNameInput = osmTempFileName,
                                selectedCategoryIds = selectedOsmCategoryIds,
                                reportProgress = { percent, status ->
                                    reportProgress(
                                        scaleStageProgress(
                                            percent = percent,
                                            start = OSM_ENRICH_STAGE_START_PERCENT,
                                            end = OSM_ENRICH_STAGE_END_PERCENT,
                                        ),
                                        status,
                                    )
                                },
                            )
                        reportProgress(80, "Merging Refuges.info + OSM…")
                        val merged =
                            getPoiFileMerger(appContext).mergeImportedFiles(
                                outputFileName = refugesResult.fileName,
                                sourceFileNamesInPriorityOrder =
                                    listOf(
                                        refugesResult.fileName,
                                        osmResult.fileName,
                                    ),
                                bboxQuery = refugesResult.bbox,
                            )
                        runCatching {
                            File(appContext.filesDir, "refuges-poi/${osmResult.fileName}").delete()
                        }
                        reportProgress(90, "Finalizing merged POI…")
                        merged
                    }
                }

                PoiImportSource.OSM -> {
                    reportProgress(OSM_DIRECT_STAGE_START_PERCENT, "Preparing OpenStreetMap import…")
                    getOsmImporter(appContext)
                        .importFromBbox(
                            bboxInput = bbox,
                            fileNameInput = fileName,
                            selectedCategoryIds = selectedOsmCategoryIds,
                            reportProgress = { percent, status ->
                                reportProgress(
                                    scaleStageProgress(
                                        percent = percent,
                                        start = OSM_DIRECT_STAGE_START_PERCENT,
                                        end = OSM_DIRECT_STAGE_END_PERCENT,
                                    ),
                                    status,
                                )
                            },
                        ).also {
                            reportProgress(90, "Finalizing OSM POI…")
                        }
                }
            }
        }
    }

    fun refreshLastRefuges(
        context: Context,
        appendToSelection: Boolean = true,
    ) {
        val appContext = context.applicationContext
        val importer = getRefugesImporter(appContext)
        val lastRequest = importer.getLastRequest()
        if (lastRequest == null || lastRequest.bbox.isBlank()) {
            _lastRefugesRequest.value = null
            Toast.makeText(appContext, "No previous Refuges import found.", Toast.LENGTH_SHORT).show()
            return
        }
        _lastRefugesRequest.value = lastRequest
        runRefugesImport(context, appendToSelection) { importer, reportProgress ->
            importer.importLast(
                reportProgress = { percent, status ->
                    reportProgress(
                        scaleStageProgress(
                            percent = percent,
                            start = REFUGES_DIRECT_STAGE_START_PERCENT,
                            end = REFUGES_DIRECT_STAGE_END_PERCENT,
                        ),
                        status,
                    )
                },
            )
        }
    }

    fun importRefugesFromMapUri(
        context: Context,
        mapUri: Uri,
        fileName: String,
        selectedTypeIds: Set<Int> = RefugesGeoJsonPoiImporter.defaultPointTypeIds(),
        appendToSelection: Boolean = true,
    ) {
        runRefugesImport(context, appendToSelection) { importer, reportProgress ->
            reportProgress(20, "Reading map bounds…")
            val bbox =
                MapsforgeMapBoundsParser.readBboxString(
                    context = context.applicationContext,
                    uri = mapUri,
                )
            reportProgress(35, "Importing from Refuges.info…")
            importer
                .importFromBbox(
                    bboxInput = bbox,
                    fileNameInput = fileName,
                    typePointIds = selectedTypeIds,
                ).also {
                    reportProgress(90, "Finalizing POI…")
                }
        }
    }

    fun importRefugesFromGeoJson(
        context: Context,
        uri: Uri,
        appendToSelection: Boolean = true,
    ) {
        runRefugesImport(context, appendToSelection) { importer, reportProgress ->
            val appContext = context.applicationContext
            reportProgress(20, "Reading GeoJSON file…")
            val geoJsonSize = queryUriSize(appContext, uri)
            if (geoJsonSize != null && geoJsonSize > MAX_GEOJSON_IMPORT_BYTES) {
                throw IllegalArgumentException(
                    "GeoJSON is too large (${geoJsonSize / (1024 * 1024)}MB). Please use a smaller file.",
                )
            }
            val text = readGeoJsonTextWithLimit(appContext, uri, MAX_GEOJSON_IMPORT_BYTES)
            val poiFileName = suggestPoiFileName(appContext, uri)
            reportProgress(60, "Converting GeoJSON to POI…")
            importer
                .importFromGeoJsonText(
                    geoJsonText = text,
                    fileNameInput = poiFileName,
                ).also {
                    reportProgress(90, "Finalizing POI…")
                }
        }
    }

    private fun runRefugesImport(
        context: Context,
        appendToSelection: Boolean,
        request: suspend (
            RefugesGeoJsonPoiImporter,
            reportProgress: (percent: Int, status: String) -> Unit,
        ) -> RefugesImportResult,
    ) {
        if (_isImportingRefuges.value) {
            Toast.makeText(context, "POI import already running.", Toast.LENGTH_SHORT).show()
            return
        }
        if (_uiState.value.isTransferring || _uiState.value.isPaused) {
            Toast.makeText(context, "Transfer in progress. Please wait or cancel.", Toast.LENGTH_SHORT).show()
            return
        }

        val appContext = context.applicationContext
        poiImportJob =
            viewModelScope.launch {
                _isImportingRefuges.value = true
                _lastImportedPoiFile.value = null
                _poiImportProgress.value =
                    PoiImportProgressState(
                        isRunning = true,
                        progressPercent = 0,
                        status = "Starting import…",
                        completed = false,
                        success = false,
                        message = "",
                    )
                try {
                    val importer = getRefugesImporter(appContext)
                    val result =
                        withContext(Dispatchers.IO) {
                            request(importer) { percent, status ->
                                _poiImportProgress.value =
                                    _poiImportProgress.value.copy(
                                        progressPercent = percent.coerceIn(0, 100),
                                        status = status,
                                    )
                            }
                        }
                    _lastRefugesRequest.value = importer.getLastRequest()

                    _poiImportProgress.value =
                        _poiImportProgress.value.copy(
                            progressPercent = 95,
                            status = "Adding imported POI to selected files…",
                        )
                    val existing = if (appendToSelection) _uiState.value.selectedFileUris else emptyList()
                    val merged = (existing + result.poiUri).distinctBy { it.toString() }
                    loadFilesFromUris(appContext, merged)

                    val successMessage = "POI imported: ${result.pointCount} points"
                    _lastImportedPoiFile.value =
                        GeneratedPhoneFile(
                            uri = result.poiUri,
                            fileName = result.fileName,
                        )
                    _poiImportProgress.value =
                        _poiImportProgress.value.copy(
                            isRunning = false,
                            progressPercent = 100,
                            status = "Import complete.",
                            completed = true,
                            success = true,
                            message = successMessage,
                        )
                    Toast
                        .makeText(
                            appContext,
                            successMessage,
                            Toast.LENGTH_LONG,
                        ).show()
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        val message = "POI import cancelled."
                        _poiImportProgress.value =
                            _poiImportProgress.value.copy(
                                isRunning = false,
                                completed = true,
                                success = false,
                                status = "Cancelled.",
                                message = message,
                            )
                        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val message =
                        when (t) {
                            is OutOfMemoryError ->
                                "Import area is too large for this phone. Please use a smaller area."
                            else ->
                                t.localizedMessage?.takeIf { it.isNotBlank() }
                                    ?: "POI import failed."
                        }
                    _poiImportProgress.value =
                        _poiImportProgress.value.copy(
                            isRunning = false,
                            completed = true,
                            success = false,
                            message = message,
                        )
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                } finally {
                    _isImportingRefuges.value = false
                    poiImportJob = null
                }
            }
    }

    fun resetPoiImportProgress() {
        _poiImportProgress.value = PoiImportProgressState()
    }

    fun cancelPoiImport() {
        val job = poiImportJob
        if (job?.isActive != true) return

        _poiImportProgress.value =
            _poiImportProgress.value.copy(
                status = "Cancelling POI import…",
            )
        osmOverpassImporter?.cancelActiveImport()
        job.cancel(CancellationException("Cancelled by user"))
    }

    fun resetRoutingDownloadProgress() {
        _routingDownloadProgress.value = RoutingDownloadProgressState()
    }

    fun cancelRoutingDownload() {
        val job = routingDownloadJob
        if (job?.isActive != true) return

        _routingDownloadProgress.value =
            _routingDownloadProgress.value.copy(
                status = "Cancelling routing download…",
                detail = "",
            )
        brouterTileDownloader?.cancelActiveDownload()
        job.cancel(CancellationException("Cancelled by user"))
    }

    fun downloadRoutingByBbox(
        context: Context,
        bbox: String,
        appendToSelection: Boolean = true,
        forceRefresh: Boolean = false,
    ) {
        if (_isDownloadingRouting.value) {
            Toast.makeText(context, "Routing download already running.", Toast.LENGTH_SHORT).show()
            return
        }
        if (_uiState.value.isTransferring || _uiState.value.isPaused) {
            Toast.makeText(context, "Transfer in progress. Please wait or cancel.", Toast.LENGTH_SHORT).show()
            return
        }

        val appContext = context.applicationContext
        routingDownloadJob =
            viewModelScope.launch {
                _isDownloadingRouting.value = true
                _lastRoutingDownloadedFiles.value = emptyList()
                _routingDownloadProgress.value =
                    RoutingDownloadProgressState(
                        isRunning = true,
                        progressPercent = 0,
                        status = "Preparing routing packs…",
                        detail = "",
                    )
                try {
                    val downloader = getBRouterTileDownloader(appContext)
                    val result =
                        withContext(Dispatchers.IO) {
                            downloader.downloadForBbox(
                                bboxInput = bbox,
                                reportProgress = { percent, status, detail ->
                                    _routingDownloadProgress.value =
                                        _routingDownloadProgress.value.copy(
                                            progressPercent = percent.coerceIn(0, 100),
                                            status = status,
                                            detail = detail,
                                        )
                                },
                                forceRefresh = forceRefresh,
                            )
                        }

                    _lastRoutingRequest.value = downloader.getLastRequest()

                    _routingDownloadProgress.value =
                        _routingDownloadProgress.value.copy(
                            progressPercent = 95,
                            status =
                                if (forceRefresh) {
                                    "Refreshing selected routing packs…"
                                } else {
                                    "Adding routing packs to selected files…"
                                },
                            detail = "",
                        )

                    val existing = if (appendToSelection) _uiState.value.selectedFileUris else emptyList()
                    val merged = (existing + result.tileUris).distinctBy { it.toString() }
                    loadFilesFromUris(appContext, merged)

                    val message =
                        when {
                            forceRefresh && result.downloadedCount > 0 ->
                                "Routing refreshed: ${result.downloadedCount} pack(s) updated."
                            result.downloadedCount > 0 && result.skippedCount > 0 ->
                                "Routing ready: ${result.downloadedCount} downloaded, ${result.skippedCount} already present."
                            result.downloadedCount > 0 ->
                                "Routing ready: ${result.downloadedCount} pack(s) downloaded."
                            else ->
                                "Routing already available (${result.skippedCount} pack(s) already present)."
                        }

                    _lastRoutingDownloadedFiles.value =
                        result.tileUris.zip(result.tileNames).map { (uri, name) ->
                            GeneratedPhoneFile(uri = uri, fileName = name)
                        }
                    _routingDownloadProgress.value =
                        _routingDownloadProgress.value.copy(
                            isRunning = false,
                            progressPercent = 100,
                            status = "Routing packs ready.",
                            detail = "",
                            completed = true,
                            success = true,
                            message = message,
                        )
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        val message = "Routing download cancelled."
                        _routingDownloadProgress.value =
                            _routingDownloadProgress.value.copy(
                                isRunning = false,
                                completed = true,
                                success = false,
                                status = "Cancelled.",
                                detail = "",
                                message = message,
                            )
                        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val message =
                        t.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Routing download failed."
                    _routingDownloadProgress.value =
                        _routingDownloadProgress.value.copy(
                            isRunning = false,
                            completed = true,
                            success = false,
                            detail = "",
                            message = message,
                        )
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                } finally {
                    _isDownloadingRouting.value = false
                    routingDownloadJob = null
                }
            }
    }

    fun refreshLastRouting(
        context: Context,
        appendToSelection: Boolean = true,
    ) {
        val appContext = context.applicationContext
        val downloader = getBRouterTileDownloader(appContext)
        val lastRequest = downloader.getLastRequest()
        if (lastRequest == null || lastRequest.bbox.isBlank()) {
            _lastRoutingRequest.value = null
            Toast.makeText(appContext, "No previous routing download found.", Toast.LENGTH_SHORT).show()
            return
        }
        _lastRoutingRequest.value = lastRequest
        downloadRoutingByBbox(
            context = context,
            bbox = lastRequest.bbox,
            appendToSelection = appendToSelection,
            forceRefresh = true,
        )
    }

    private fun getRefugesImporter(context: Context): RefugesGeoJsonPoiImporter = refugesImporter ?: RefugesGeoJsonPoiImporter(context).also { refugesImporter = it }

    private fun getBRouterTileDownloader(context: Context): BRouterTileDownloader =
        brouterTileDownloader ?: BRouterTileDownloader(context).also {
            brouterTileDownloader = it
        }

    private fun getOsmImporter(context: Context): OsmOverpassPoiImporter =
        osmOverpassImporter
            ?: OsmOverpassPoiImporter(context).also { osmOverpassImporter = it }

    private fun getGpxWaypointPoiImporter(context: Context): GpxWaypointPoiImporter =
        gpxWaypointPoiImporter
            ?: GpxWaypointPoiImporter(context).also { gpxWaypointPoiImporter = it }

    private fun scaleStageProgress(
        percent: Int,
        start: Int,
        end: Int,
    ): Int {
        val clampedPercent = percent.coerceIn(0, 100)
        val safeStart = start.coerceIn(0, 100)
        val safeEnd = end.coerceIn(safeStart, 100)
        val span = safeEnd - safeStart
        return (safeStart + ((clampedPercent * span) / 100)).coerceIn(safeStart, safeEnd)
    }

    private fun getPoiFileMerger(context: Context): PoiFileMerger = poiFileMerger ?: PoiFileMerger(context).also { poiFileMerger = it }

    private fun getRefugesRegionPresetRepository(context: Context): RefugesRegionPresetRepository =
        refugesRegionPresetRepository
            ?: RefugesRegionPresetRepository(context).also { refugesRegionPresetRepository = it }

    private fun loadRefugesRegionPresets(
        appContext: Context,
        mode: RefugesRegionPresetMode,
        forceRefresh: Boolean,
    ) {
        hasLoadedRefugesPresets = true
        loadedRefugesRegionPresetMode = mode
        viewModelScope.launch {
            val presets =
                runCatching {
                    withContext(Dispatchers.IO) {
                        getRefugesRegionPresetRepository(appContext).loadPresets(
                            forceRefresh = forceRefresh,
                            mode = mode,
                        )
                    }
                }.getOrDefault(RefugesRegionPresetRepository.defaultPresets())
            _refugesRegionPresets.value = presets
        }
    }

    fun cancelTransfer() {
        serviceRef?.get()?.cancelTransfer()
    }

    fun pauseTransfer() {
        serviceRef?.get()?.pauseTransfer()
    }

    fun resumeTransfer() {
        serviceRef?.get()?.resumeTransfer()
    }

    fun clearSelectedFiles() {
        pendingFileUris = emptyList()
        val service = serviceRef?.get()
        if (service != null) {
            service.clearSelectedFiles()
        } else {
            _uiState.value =
                _uiState.value.copy(
                    selectedFileUris = emptyList(),
                    selectedFileDisplayNames = emptyList(),
                    selectedFileUri = null,
                    selectedFileName = null,
                    selectedFileSizeMb = 0,
                )
        }
    }

    fun clearHistory() {
        serviceRef?.get()?.clearHistory()
    }

    fun startPhoneDebugCapture(context: Context) {
        PhoneDebugCapture.start()
        Toast
            .makeText(
                context.applicationContext,
                "Phone debug capture started",
                Toast.LENGTH_SHORT,
            ).show()
    }

    fun stopPhoneDebugCaptureAndSend(context: Context) {
        PhoneDebugCapture.stop()
        viewModelScope.launch {
            runCatching {
                CompanionDiagnosticsEmailComposer.composePhoneDiagnosticsEmail(context)
            }.onFailure { error ->
                Toast
                    .makeText(
                        context.applicationContext,
                        error.localizedMessage ?: "Could not open email draft",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    fun sendLastPhoneDebugCapture(context: Context) {
        viewModelScope.launch {
            runCatching {
                CompanionDiagnosticsEmailComposer.composeLatestPhoneDiagnosticsEmail(context)
            }.onFailure { error ->
                Toast
                    .makeText(
                        context.applicationContext,
                        error.localizedMessage ?: "No saved phone recording available",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    override fun onCleared() {
        clearServiceCollectors()
        lastRequestsLoadJob?.cancel()
        lastRequestsLoadJob = null
        super.onCleared()
    }
}
