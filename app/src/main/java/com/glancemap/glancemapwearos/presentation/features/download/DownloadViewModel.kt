@file:Suppress(
    "CyclomaticComplexMethod",
    "LargeClass",
    "LongMethod",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadUiState(
    val areas: List<OamDownloadArea> = OamDownloadCatalog.areas,
    val selectedAreaIds: Set<String> = emptySet(),
    val suggestedAreaIds: List<String> = emptyList(),
    val selection: OamDownloadSelection = OamDownloadSelection(),
    val installedBundles: List<OamInstalledBundle> = emptyList(),
    val deletingBundleId: String? = null,
    val bundleHealthByAreaId: Map<String, OamBundleLocalHealth> = emptyMap(),
    val isDownloading: Boolean = false,
    val phase: String? = null,
    val detail: String? = null,
    val bytesDone: Long = 0L,
    val totalBytes: Long? = null,
    val isPausedDownload: Boolean = false,
    val pausedOperation: DownloadOperation? = null,
    val isCheckingUpdates: Boolean = false,
    val selectedRefreshBundleIds: Set<String> = emptySet(),
    val refreshPrompt: OamBundleUpdateCheck? = null,
    val refreshSummaryPrompt: OamBundleRefreshSummary? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val networkWarningMessage: String? = null,
    val lastLibraryChangedAtMillis: Long = 0L,
) {
    val visibleInstalledBundles: List<OamInstalledBundle>
        get() = installedBundles.filterNot { it.areaId == deletingBundleId }

    val isLibraryBusy: Boolean
        get() = isDownloading || deletingBundleId != null

    val selectedAreas: List<OamDownloadArea>
        get() = areas.filter { it.id in selectedAreaIds }

    val suggestedAreas: List<OamDownloadArea>
        get() = suggestedAreaIds.mapNotNull { areaId -> areas.firstOrNull { it.id == areaId } }

    val selectedBundle: OamBundleChoice
        get() = selection.toBundleChoice()

    val canStartOrResumeDownload: Boolean
        get() =
            !isLibraryBusy &&
                when (pausedOperation) {
                    DownloadOperation.REFRESH -> true
                    DownloadOperation.DOWNLOAD, null -> selection.canDownload && selectedAreas.isNotEmpty()
                }
}

enum class DownloadOperation {
    DOWNLOAD,
    REFRESH,
}

class DownloadViewModel(
    private val resources: Resources,
    private val downloader: OamBundleDownloader,
    private val notificationController: OamDownloadNotificationController,
    private val networkMonitor: OamDownloadNetworkMonitor,
    private val settingsRepository: SettingsRepository,
    private val downloadServiceClient: OamDownloadServiceClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var stopRequest: DownloadStopRequest? = null
    private var pendingNonWifiRefreshRequests: List<BundleRefreshRequest> = emptyList()
    private var pausedRefreshRequests: List<BundleRefreshRequest> = emptyList()
    private var lastOwnedDownloadStatus = OamOwnedDownloadStatus.IDLE

    init {
        refreshInstalledBundles()
        settingsRepository.demSource
            .onEach { source ->
                _uiState.update { state ->
                    state.copy(selection = state.selection.copy(demSource = source))
                }
            }.launchIn(viewModelScope)
        downloadServiceClient.state
            .onEach(::onOwnedDownloadState)
            .launchIn(viewModelScope)
        downloadServiceClient.resumeIfNeeded()
    }

    fun toggleArea(areaId: String) {
        if (_uiState.value.isDownloading) return
        downloadServiceClient.discardPausedPlan()
        _uiState.update { state ->
            val nextIds =
                if (areaId in state.selectedAreaIds) {
                    state.selectedAreaIds - areaId
                } else {
                    state.selectedAreaIds + areaId
                }
            state.copy(
                selectedAreaIds = nextIds,
                isPausedDownload = false,
                pausedOperation = null,
                statusMessage = null,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
    }

    fun clearAreaSelection() {
        if (_uiState.value.isDownloading) return
        downloadServiceClient.discardPausedPlan()
        _uiState.update {
            it.copy(
                selectedAreaIds = emptySet(),
                isPausedDownload = false,
                pausedOperation = null,
                statusMessage = null,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
    }

    fun suggestAreasForLocation(
        latitude: Double,
        longitude: Double,
    ): List<OamDownloadArea> {
        val suggestedAreas = OamDownloadCatalog.areasForLocation(latitude, longitude)
        _uiState.update {
            it.copy(suggestedAreaIds = suggestedAreas.map(OamDownloadArea::id))
        }
        return suggestedAreas
    }

    fun setIncludeMap(includeMap: Boolean) {
        if (_uiState.value.isDownloading) return
        downloadServiceClient.discardPausedPlan()
        _uiState.update { state ->
            state.copy(
                selection = state.selection.copy(includeMap = includeMap),
                isPausedDownload = false,
                pausedOperation = null,
                statusMessage = null,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
    }

    fun setIncludePoi(includePoi: Boolean) {
        if (_uiState.value.isDownloading) return
        downloadServiceClient.discardPausedPlan()
        _uiState.update { state ->
            state.copy(
                selection = state.selection.copy(includePoi = includePoi),
                isPausedDownload = false,
                pausedOperation = null,
                statusMessage = null,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
    }

    fun setIncludeRouting(includeRouting: Boolean) {
        if (_uiState.value.isDownloading) return
        downloadServiceClient.discardPausedPlan()
        _uiState.update { state ->
            state.copy(
                selection = state.selection.copy(includeRouting = includeRouting),
                isPausedDownload = false,
                pausedOperation = null,
                statusMessage = null,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
    }

    fun setIncludeDem(includeDem: Boolean) {
        if (_uiState.value.isDownloading) return
        downloadServiceClient.discardPausedPlan()
        _uiState.update { state ->
            state.copy(
                selection = state.selection.copy(includeDem = includeDem),
                isPausedDownload = false,
                pausedOperation = null,
                statusMessage = null,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
    }

    fun setDemSource(source: DemSource) {
        if (_uiState.value.isDownloading) return
        downloadServiceClient.discardPausedPlan()
        viewModelScope.launch {
            settingsRepository.setDemSource(source)
        }
    }

    fun setIncludeRefugesInfo(includeRefugesInfo: Boolean) {
        if (_uiState.value.isDownloading) return
        downloadServiceClient.discardPausedPlan()
        _uiState.update { state ->
            state.copy(
                selection = state.selection.copy(includeRefugesInfo = includeRefugesInfo),
                isPausedDownload = false,
                pausedOperation = null,
                statusMessage = null,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
    }

    fun downloadSelectedBundle() {
        val state = _uiState.value
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=${if (state.isPausedDownload) "user_resume_request" else "user_download_request"} " +
                networkMonitor.currentState().telemetryFields,
        )
        if (state.pausedOperation == DownloadOperation.REFRESH && pausedRefreshRequests.isNotEmpty()) {
            refreshBundlesInternal(pausedRefreshRequests, allowNonWifi = false)
        } else {
            downloadSelectedBundleInternal(allowNonWifi = false)
        }
    }

    fun continueDownloadWithoutWifi() {
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=user_continue_without_wifi ${networkMonitor.currentState().telemetryFields}",
        )
        val refreshRequests = pendingNonWifiRefreshRequests
        pendingNonWifiRefreshRequests = emptyList()
        if (refreshRequests.isNotEmpty()) {
            refreshBundlesInternal(refreshRequests, allowNonWifi = true)
        } else {
            downloadSelectedBundleInternal(allowNonWifi = true)
        }
    }

    fun dismissNetworkWarning() {
        pendingNonWifiRefreshRequests = emptyList()
        _uiState.update { it.copy(networkWarningMessage = null) }
    }

    private fun downloadSelectedBundleInternal(allowNonWifi: Boolean) {
        val serviceIsDownloading = downloadServiceClient.state.value.status == OamOwnedDownloadStatus.RUNNING
        if (downloadJob?.isActive == true || serviceIsDownloading || _uiState.value.deletingBundleId != null) {
            return
        }
        val state = _uiState.value
        if (!state.selection.canDownload) {
            _uiState.update {
                it.copy(
                    statusMessage = resources.getString(R.string.download_nothing_selected),
                    errorMessage =
                        resources.getString(
                            R.string.download_enable_maps_poi_routing_dem_or_refuges_info_in_bundle_settings,
                        ),
                    networkWarningMessage = null,
                )
            }
            return
        }
        val areas = state.selectedAreas
        if (areas.isEmpty()) {
            _uiState.update {
                it.copy(
                    statusMessage = resources.getString(R.string.download_no_area_selected),
                    errorMessage = resources.getString(R.string.download_select_at_least_one_area_before_downloading),
                    networkWarningMessage = null,
                )
            }
            return
        }
        val selection = state.selection
        val networkState = networkMonitor.currentState()
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=download_request allowNonWifi=$allowNonWifi " +
                "areas=${areas.size} bundle=${selection.toBundleChoice().name} " +
                "map=${selection.includeMap} poi=${selection.includePoi} routing=${selection.includeRouting} " +
                "dem=${selection.includeDem} demSource=${selection.demSource.id} " +
                "refuges=${selection.includeRefugesInfo} ${networkState.telemetryFields}",
        )
        if (!allowNonWifi && !networkState.isValidatedWifi) {
            _uiState.update {
                it.copy(
                    statusMessage = resources.getString(R.string.download_wi_fi_recommended),
                    errorMessage = null,
                    networkWarningMessage =
                        resources.getString(
                            R.string.download_connect_the_watch_to_wi_fi_for_large_downloads_or_continue_and_the_app,
                            resources.getString(networkState.userLabelRes),
                        ),
                )
            }
            DebugTelemetry.log(
                OAM_DOWNLOAD_TELEMETRY_TAG,
                "event=wifi_preflight_blocked state=${networkState.transportLabel.telemetryValue()} " +
                    networkState.telemetryFields,
            )
            return
        }
        pausedRefreshRequests = emptyList()
        downloadServiceClient.start(areas, selection)
    }

    fun pauseDownload() {
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=user_pause_request ${networkMonitor.currentState().telemetryFields}",
        )
        if (downloadServiceClient.state.value.status == OamOwnedDownloadStatus.RUNNING) {
            _uiState.update {
                it.copy(
                    phase = "PAUSING",
                    statusMessage = resources.getString(R.string.download_pausing_download),
                )
            }
            downloadServiceClient.pause()
        } else {
            requestDownloadStop(
                request = DownloadStopRequest.PAUSE,
                abortReason = "user_pause",
                phase = "PAUSING",
                statusMessage = resources.getString(R.string.download_pausing_download),
            )
        }
    }

    fun cancelDownload() {
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=user_cancel_request ${networkMonitor.currentState().telemetryFields}",
        )
        if (downloadServiceClient.state.value.status == OamOwnedDownloadStatus.RUNNING) {
            _uiState.update {
                it.copy(phase = "CANCELING", statusMessage = resources.getString(R.string.download_canceling_download))
            }
            downloadServiceClient.cancel()
        } else {
            requestDownloadStop(
                request = DownloadStopRequest.CANCEL,
                abortReason = "user_cancel",
                phase = "CANCELING",
                statusMessage = resources.getString(R.string.download_canceling_download),
            )
        }
    }

    private fun requestDownloadStop(
        request: DownloadStopRequest,
        abortReason: String,
        phase: String,
        statusMessage: String,
    ) {
        stopRequest = request
        _uiState.update {
            it.copy(
                phase = phase,
                statusMessage = statusMessage,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
        downloadJob?.cancel(CancellationException(statusMessage))
        viewModelScope.launch(Dispatchers.IO) {
            downloader.abortActiveDownloads(reason = abortReason)
        }
    }

    fun checkBundleForRefresh(bundle: OamInstalledBundle) {
        if (_uiState.value.isLibraryBusy || _uiState.value.isCheckingUpdates) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingUpdates = true,
                    refreshPrompt = null,
                    statusMessage = resources.getString(R.string.download_checking_updates),
                    errorMessage = null,
                    networkWarningMessage = null,
                )
            }
            val check =
                runCatching { downloader.checkBundleUpdates(bundle) }
                    .getOrElse { error ->
                        OamBundleUpdateCheck(
                            bundle = bundle,
                            status = OamBundleUpdateStatus.UNKNOWN,
                            checkedFileCount = 0,
                            unknownFileNames =
                                listOf(
                                    error.message ?: resources.getString(R.string.download_update_check_failed),
                                ),
                        )
                    }
            _uiState.update {
                val updatedHealth =
                    it.bundleHealthByAreaId +
                        (bundle.areaId to OamBundleLocalHealth(check.repairFileNames))
                when (check.status) {
                    OamBundleUpdateStatus.REPAIR_NEEDED ->
                        it.copy(
                            bundleHealthByAreaId = updatedHealth,
                            isCheckingUpdates = false,
                            statusMessage = resources.getString(R.string.download_repair_needed),
                            errorMessage = null,
                            refreshPrompt = check,
                            networkWarningMessage = null,
                        )
                    OamBundleUpdateStatus.UP_TO_DATE ->
                        it.copy(
                            bundleHealthByAreaId = updatedHealth,
                            isCheckingUpdates = false,
                            statusMessage =
                                if (check.hasSelectedAdditions(it.selection)) {
                                    resources.getString(R.string.download_selected_components_available)
                                } else {
                                    resources.getString(R.string.download_is_up_to_date, bundle.areaLabel)
                                },
                            errorMessage = null,
                            refreshPrompt = check,
                            networkWarningMessage = null,
                        )
                    OamBundleUpdateStatus.UPDATE_AVAILABLE,
                    OamBundleUpdateStatus.UNKNOWN,
                    ->
                        it.copy(
                            bundleHealthByAreaId = updatedHealth,
                            isCheckingUpdates = false,
                            statusMessage =
                                if (check.status == OamBundleUpdateStatus.UPDATE_AVAILABLE) {
                                    resources.getString(R.string.download_update_available)
                                } else {
                                    resources.getString(R.string.download_update_check_incomplete)
                                },
                            errorMessage = null,
                            refreshPrompt = check,
                            networkWarningMessage = null,
                        )
                }
            }
        }
    }

    fun dismissRefreshPrompt() {
        _uiState.update { it.copy(refreshPrompt = null, refreshSummaryPrompt = null) }
    }

    fun confirmRefreshBundle() {
        val check = _uiState.value.refreshPrompt ?: return
        if (check.status == OamBundleUpdateStatus.UP_TO_DATE &&
            !check.hasSelectedAdditions(_uiState.value.selection)
        ) {
            _uiState.update { it.copy(refreshPrompt = null) }
            return
        }
        _uiState.update { it.copy(refreshPrompt = null) }
        refreshBundlesInternal(listOf(check.toRefreshRequest()), allowNonWifi = false)
    }

    fun toggleRefreshBundleSelection(areaId: String) {
        if (_uiState.value.isLibraryBusy || _uiState.value.isCheckingUpdates) return
        _uiState.update { state ->
            val nextSelection =
                if (areaId in state.selectedRefreshBundleIds) {
                    state.selectedRefreshBundleIds - areaId
                } else {
                    state.selectedRefreshBundleIds + areaId
                }
            state.copy(
                selectedRefreshBundleIds = nextSelection,
                refreshPrompt = null,
                refreshSummaryPrompt = null,
                statusMessage = null,
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
    }

    fun clearRefreshBundleSelection() {
        if (_uiState.value.isLibraryBusy || _uiState.value.isCheckingUpdates) return
        _uiState.update {
            it.copy(
                selectedRefreshBundleIds = emptySet(),
                refreshPrompt = null,
                refreshSummaryPrompt = null,
            )
        }
    }

    fun checkSelectedBundlesForRefresh() {
        val state = _uiState.value
        if (state.isLibraryBusy || state.isCheckingUpdates) return
        val bundles = state.installedBundles.filter { it.areaId in state.selectedRefreshBundleIds }
        if (bundles.isEmpty()) {
            _uiState.update {
                it.copy(
                    statusMessage = resources.getString(R.string.download_no_bundle_selected),
                    errorMessage = resources.getString(R.string.download_select_at_least_one_installed_bundle),
                    refreshSummaryPrompt = null,
                    networkWarningMessage = null,
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingUpdates = true,
                    refreshPrompt = null,
                    refreshSummaryPrompt = null,
                    statusMessage =
                        resources.getQuantityString(
                            R.plurals.download_checking_bundle_s,
                            bundles.size,
                            bundles.size,
                        ),
                    errorMessage = null,
                    networkWarningMessage = null,
                )
            }
            val checks =
                bundles.map { bundle ->
                    runCatching { downloader.checkBundleUpdates(bundle) }
                        .getOrElse { error ->
                            OamBundleUpdateCheck(
                                bundle = bundle,
                                status = OamBundleUpdateStatus.UNKNOWN,
                                checkedFileCount = 0,
                                unknownFileNames =
                                    listOf(
                                        error.message ?: resources.getString(R.string.download_update_check_failed),
                                    ),
                            )
                        }
                }
            val summary = OamBundleRefreshSummary(checks = checks, selection = _uiState.value.selection)
            _uiState.update {
                val updatedHealth =
                    it.bundleHealthByAreaId +
                        checks.associate { check ->
                            check.bundle.areaId to OamBundleLocalHealth(check.repairFileNames)
                        }
                it.copy(
                    bundleHealthByAreaId = updatedHealth,
                    isCheckingUpdates = false,
                    refreshSummaryPrompt = summary,
                    statusMessage =
                        when {
                            summary.repairNeededCount > 0 ->
                                resources.getQuantityString(
                                    R.plurals.download_bundle_s_need_repair,
                                    summary.repairNeededCount,
                                    summary.repairNeededCount,
                                )
                            summary.updateAvailableCount > 0 ->
                                resources.getQuantityString(
                                    R.plurals.download_bundle_s_need_refresh,
                                    summary.bundlesToRefresh.size,
                                    summary.bundlesToRefresh.size,
                                )
                            summary.selectedAdditionCount > 0 ->
                                resources.getQuantityString(
                                    R.plurals.download_bundle_s_can_add_selected_components,
                                    summary.selectedAdditionCount,
                                    summary.selectedAdditionCount,
                                )
                            summary.unknownCount > 0 -> resources.getString(R.string.download_update_check_incomplete)
                            else -> resources.getString(R.string.download_selected_bundles_are_up_to_date)
                        },
                    errorMessage = null,
                    networkWarningMessage = null,
                )
            }
        }
    }

    fun dismissRefreshSummary() {
        _uiState.update { it.copy(refreshSummaryPrompt = null) }
    }

    fun confirmRefreshSelectedBundles() {
        val state = _uiState.value
        val requests =
            state.refreshSummaryPrompt
                ?.checksToRefresh(state.selection)
                ?.map { it.toRefreshRequest() }
                .orEmpty()
        _uiState.update {
            it.copy(
                refreshSummaryPrompt = null,
                selectedRefreshBundleIds = emptySet(),
            )
        }
        if (requests.isNotEmpty()) {
            refreshBundlesInternal(requests, allowNonWifi = false)
        }
    }

    fun deleteBundle(bundle: OamInstalledBundle) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            _uiState.deleteInstalledBundle(
                bundle = bundle,
                delete = downloader::deleteBundle,
                reload = ::loadInstalledBundlesAndHealth,
                failureMessage = resources.getString(R.string.download_delete_failed),
            )
        }
    }

    fun refreshInstalledBundles() {
        viewModelScope.launch {
            _uiState.refreshInstalledBundleState(::loadInstalledBundlesAndHealth)
        }
    }

    private suspend fun loadInstalledBundlesAndHealth(): Pair<List<OamInstalledBundle>, Map<String, OamBundleLocalHealth>> {
        val installed = downloader.installedBundles()
        val healthByAreaId =
            installed.associate { bundle ->
                bundle.areaId to downloader.checkInstalledBundleHealth(bundle)
            }
        return installed to healthByAreaId
    }

    private fun refreshBundlesInternal(
        requests: List<BundleRefreshRequest>,
        allowNonWifi: Boolean,
    ) {
        if (downloadJob?.isActive == true || _uiState.value.isLibraryBusy) return
        val targets =
            requests.mapNotNull { request ->
                _uiState.value.areas.firstOrNull { it.id == request.bundle.areaId }?.let { area ->
                    RefreshTarget(
                        bundle = request.bundle,
                        area = area,
                        selection = request.bundle.toDownloadSelection(_uiState.value.selection),
                        forces = request.forces(area),
                    )
                }
            }
        if (targets.size != requests.size || targets.isEmpty()) {
            _uiState.update {
                it.copy(
                    statusMessage = resources.getString(R.string.download_refresh_failed),
                    errorMessage = resources.getString(R.string.download_one_or_more_bundle_areas_are_unknown),
                    networkWarningMessage = null,
                )
            }
            return
        }
        val networkState = networkMonitor.currentState()
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=refresh_request allowNonWifi=$allowNonWifi bundles=${targets.size} " +
                networkState.telemetryFields,
        )
        if (!allowNonWifi && !networkState.isValidatedWifi) {
            pendingNonWifiRefreshRequests = requests
            _uiState.update {
                it.copy(
                    statusMessage = resources.getString(R.string.download_wi_fi_recommended),
                    errorMessage = null,
                    networkWarningMessage =
                        resources.getString(
                            R.string.download_connect_the_watch_to_wi_fi_for_large_downloads_or_continue_and_the_app,
                            resources.getString(networkState.userLabelRes),
                        ),
                )
            }
            DebugTelemetry.log(
                OAM_DOWNLOAD_TELEMETRY_TAG,
                "event=wifi_preflight_blocked state=${networkState.transportLabel.telemetryValue()} " +
                    networkState.telemetryFields,
            )
            return
        }
        stopRequest = null
        pausedRefreshRequests = emptyList()
        _uiState.update {
            it.copy(
                isDownloading = true,
                phase = "STARTING",
                detail = resources.getQuantityString(R.plurals.download_bundle_s, targets.size, targets.size),
                bytesDone = 0L,
                totalBytes = null,
                isPausedDownload = false,
                pausedOperation = null,
                selectedRefreshBundleIds = emptySet(),
                statusMessage = resources.getString(R.string.download_refreshing_bundles),
                errorMessage = null,
                networkWarningMessage = null,
            )
        }
        downloadJob =
            viewModelScope.launch {
                val wifiReconnectHandle = watchForWifiRecovery(networkState)
                val progressThrottler = OamProgressThrottler()
                try {
                    notificationController.showProgress(
                        title = resources.getString(R.string.download_refreshing_bundles),
                        detail = resources.getQuantityString(R.plurals.download_bundle_s, targets.size, targets.size),
                        bytesDone = 0L,
                        totalBytes = null,
                    )
                    targets.forEachIndexed { index, target ->
                        downloader.downloadBundle(
                            area = target.area,
                            selection = target.selection,
                            forceMap = target.forces.forceMap,
                            forcePoi = target.forces.forcePoi,
                            forceRefugesInfo = target.forces.forceRefugesInfo,
                            forceRoutingFileNames = target.forces.forceRoutingFileNames,
                            forceDemTileIds = target.forces.forceDemTileIds,
                        ) { progress ->
                            if (!progress.shouldShowInBundleProgress()) return@downloadBundle
                            if (!progressThrottler.shouldEmit(progress)) return@downloadBundle
                            val detail =
                                resources.getString(
                                    R.string.download_area_progress_detail,
                                    index + 1,
                                    targets.size,
                                    target.area.region,
                                    progress.detail,
                                )
                            notificationController.showProgress(
                                title = resources.getString(R.string.download_refreshing_offline_bundle),
                                detail = detail,
                                bytesDone = progress.bytesDone,
                                totalBytes = progress.totalBytes,
                            )
                            _uiState.update {
                                it.copy(
                                    phase = progress.phase,
                                    detail = detail,
                                    bytesDone = progress.bytesDone,
                                    totalBytes = progress.totalBytes,
                                    statusMessage =
                                        progress.phase
                                            .lowercase()
                                            .replaceFirstChar { char -> char.uppercase() },
                                    errorMessage = null,
                                )
                            }
                        }
                    }
                    val (installed, healthByAreaId) = loadInstalledBundlesAndHealth()
                    _uiState.update {
                        it.copy(
                            installedBundles = installed,
                            bundleHealthByAreaId = healthByAreaId,
                            selectedAreaIds = emptySet(),
                            selectedRefreshBundleIds = emptySet(),
                            isDownloading = false,
                            phase = "READY",
                            detail =
                                resources.getQuantityString(
                                    R.plurals.download_bundle_s,
                                    targets.size,
                                    targets.size,
                                ),
                            bytesDone = 0L,
                            totalBytes = null,
                            isPausedDownload = false,
                            pausedOperation = null,
                            statusMessage =
                                resources.getQuantityString(
                                    R.plurals.download_bundle_status_refreshed,
                                    targets.size,
                                ),
                            errorMessage = null,
                            networkWarningMessage = null,
                            lastLibraryChangedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    notificationController.showComplete(
                        if (targets.size == 1) {
                            resources.getString(R.string.download_refreshed, targets.first().area.region)
                        } else {
                            resources.getQuantityString(
                                R.plurals.download_bundles_refreshed_2,
                                targets.size,
                                targets.size,
                            )
                        },
                    )
                } catch (cancelled: CancellationException) {
                    val request = stopRequest ?: DownloadStopRequest.PAUSE
                    pausedRefreshRequests =
                        if (request == DownloadStopRequest.PAUSE) {
                            requests
                        } else {
                            emptyList()
                        }
                    if (request == DownloadStopRequest.CANCEL) {
                        notificationController.clear()
                    } else {
                        notificationController.showPaused(
                            resources.getQuantityString(R.plurals.download_bundle_s, targets.size, targets.size),
                        )
                    }
                    _uiState.update {
                        if (request == DownloadStopRequest.CANCEL) {
                            it.copy(
                                isDownloading = false,
                                phase = "CANCELED",
                                detail =
                                    resources.getQuantityString(
                                        R.plurals.download_bundle_s,
                                        targets.size,
                                        targets.size,
                                    ),
                                bytesDone = 0L,
                                totalBytes = null,
                                isPausedDownload = false,
                                pausedOperation = null,
                                statusMessage = resources.getString(R.string.download_refresh_canceled),
                                errorMessage = null,
                                networkWarningMessage = null,
                            )
                        } else {
                            it.copy(
                                isDownloading = false,
                                phase = "PAUSED",
                                isPausedDownload = true,
                                pausedOperation = DownloadOperation.REFRESH,
                                statusMessage = resources.getString(R.string.download_refresh_paused),
                                errorMessage = null,
                                networkWarningMessage = null,
                            )
                        }
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            phase = "FAILED",
                            statusMessage = resources.getString(R.string.download_refresh_failed),
                            isPausedDownload = false,
                            pausedOperation = null,
                            errorMessage = error.message ?: resources.getString(R.string.download_refresh_failed),
                            networkWarningMessage = null,
                        )
                    }
                    notificationController.showError(
                        error.message ?: resources.getString(R.string.download_refresh_failed),
                    )
                } finally {
                    DebugTelemetry.log(
                        OAM_DOWNLOAD_TELEMETRY_TAG,
                        "event=progress_throttle_summary owner=refresh " +
                            "requested=${progressThrottler.requestedCount} " +
                            "emitted=${progressThrottler.emittedCount} " +
                            "suppressed=${progressThrottler.suppressedCount}",
                    )
                    wifiReconnectHandle.close()
                    downloadJob = null
                    stopRequest = null
                }
            }
    }

    private suspend fun onOwnedDownloadState(state: OamOwnedDownloadState) {
        val previousStatus = lastOwnedDownloadStatus
        lastOwnedDownloadStatus = state.status
        when (state.status) {
            OamOwnedDownloadStatus.IDLE -> Unit
            OamOwnedDownloadStatus.RUNNING -> {
                _uiState.update {
                    it.copy(
                        selectedAreaIds = state.areaIds.toSet().ifEmpty { it.selectedAreaIds },
                        selection = state.selection ?: it.selection,
                        isDownloading = true,
                        refreshPrompt = null,
                        refreshSummaryPrompt = null,
                        phase = state.phase ?: "STARTING",
                        detail = state.detail,
                        bytesDone = state.bytesDone,
                        totalBytes = state.totalBytes,
                        isPausedDownload = false,
                        pausedOperation = null,
                        statusMessage =
                            (state.phase ?: "STARTING")
                                .lowercase()
                                .replaceFirstChar(Char::uppercase),
                        errorMessage = null,
                        networkWarningMessage = null,
                    )
                }
            }
            OamOwnedDownloadStatus.PAUSED -> {
                _uiState.update {
                    it.copy(
                        selectedAreaIds = state.areaIds.toSet().ifEmpty { it.selectedAreaIds },
                        selection = state.selection ?: it.selection,
                        isDownloading = false,
                        phase = "PAUSED",
                        detail = state.detail,
                        bytesDone = state.bytesDone,
                        totalBytes = state.totalBytes,
                        isPausedDownload = true,
                        pausedOperation = DownloadOperation.DOWNLOAD,
                        statusMessage = resources.getString(R.string.download_download_paused),
                        errorMessage = null,
                        networkWarningMessage = null,
                    )
                }
            }
            OamOwnedDownloadStatus.COMPLETE -> {
                if (previousStatus == OamOwnedDownloadStatus.COMPLETE) return
                val (installed, healthByAreaId) = loadInstalledBundlesAndHealth()
                _uiState.update {
                    it.copy(
                        installedBundles = installed,
                        bundleHealthByAreaId = healthByAreaId,
                        selectedAreaIds = emptySet(),
                        isDownloading = false,
                        phase = "READY",
                        detail = state.detail,
                        bytesDone = 0L,
                        totalBytes = null,
                        isPausedDownload = false,
                        pausedOperation = null,
                        statusMessage =
                            resources.getQuantityString(
                                R.plurals.download_bundle_status_installed,
                                state.areaCount,
                            ),
                        errorMessage = null,
                        networkWarningMessage = null,
                        lastLibraryChangedAtMillis = System.currentTimeMillis(),
                    )
                }
            }
            OamOwnedDownloadStatus.CANCELED -> {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        phase = "CANCELED",
                        detail = state.detail,
                        bytesDone = 0L,
                        totalBytes = null,
                        isPausedDownload = false,
                        pausedOperation = null,
                        statusMessage = resources.getString(R.string.download_download_canceled),
                        errorMessage = null,
                        networkWarningMessage = null,
                    )
                }
            }
            OamOwnedDownloadStatus.FAILED -> {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        phase = "FAILED",
                        detail = state.detail,
                        isPausedDownload = false,
                        pausedOperation = null,
                        statusMessage = resources.getString(R.string.download_download_failed),
                        errorMessage = state.errorMessage ?: resources.getString(R.string.download_download_failed),
                        networkWarningMessage = null,
                    )
                }
            }
        }
    }

    private fun watchForWifiRecovery(initialState: OamDownloadNetworkState): AutoCloseable {
        val observer =
            OamDownloadNetworkRecoveryObserver(initialState) { state ->
                DebugTelemetry.log(
                    OAM_DOWNLOAD_TELEMETRY_TAG,
                    "event=wifi_recovered ${state.telemetryFields}",
                )
                downloader.requestNetworkHandoff()
            }
        return networkMonitor.watchNetworkState(observer::onChanged)
    }

    private companion object {
        private const val OAM_DOWNLOAD_TELEMETRY_TAG = "OamDownload"
    }
}

private fun String.telemetryValue(): String = replace(' ', '_')

internal fun OamDownloadProgress.shouldShowInBundleProgress(): Boolean = !phase.equals("SKIPPED", ignoreCase = true)

private enum class DownloadStopRequest {
    PAUSE,
    CANCEL,
}

private data class RefreshTarget(
    val bundle: OamInstalledBundle,
    val area: OamDownloadArea,
    val selection: OamDownloadSelection,
    val forces: OamBundleRefreshForces,
)

private data class BundleRefreshRequest(
    val bundle: OamInstalledBundle,
    val changedFileNames: Set<String>,
    val repairFileNames: Set<String>,
) {
    fun forces(area: OamDownloadArea): OamBundleRefreshForces =
        OamBundleUpdateCheck(
            bundle = bundle,
            status = OamBundleUpdateStatus.UPDATE_AVAILABLE,
            checkedFileCount = 0,
            changedFileNames = changedFileNames.toList(),
            repairFileNames = repairFileNames.toList(),
        ).refreshForces(area)
}

private fun OamBundleUpdateCheck.toRefreshRequest(): BundleRefreshRequest =
    BundleRefreshRequest(
        bundle = bundle,
        changedFileNames = changedFileNames.toSet(),
        repairFileNames = repairFileNames.toSet(),
    )

internal fun OamInstalledBundle.toDownloadSelection(currentSelection: OamDownloadSelection): OamDownloadSelection =
    OamDownloadSelection(
        includeMap = mapFileName != null || currentSelection.includeMap,
        includePoi = poiFileName != null || currentSelection.includePoi,
        includeRouting = routingFileNames.isNotEmpty() || currentSelection.includeRouting,
        includeDem = demTileIds.isNotEmpty() || currentSelection.includeDem,
        demSource = if (demTileIds.isNotEmpty()) demSource else currentSelection.demSource,
        includeRefugesInfo = refugesInfoFileName != null || currentSelection.includeRefugesInfo,
    )
