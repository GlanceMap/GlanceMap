package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.trailcore.oam.OamDownloadCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface PhoneOfflineBundleDownloadState {
    data object Idle : PhoneOfflineBundleDownloadState

    data class Downloading(
        val areaId: String,
        val progress: PhoneOfflineBundleProgress,
    ) : PhoneOfflineBundleDownloadState

    data class Completed(
        val bundle: PhoneInstalledBundle,
    ) : PhoneOfflineBundleDownloadState

    data class Failed(
        val reason: PhoneOfflineBundleFailure,
    ) : PhoneOfflineBundleDownloadState

    data object Cancelled : PhoneOfflineBundleDownloadState
}

internal data class PhoneOfflineBundleUiState(
    val installedBundles: List<PhoneInstalledBundle> = emptyList(),
    val installedAreaIds: Set<String> = emptySet(),
    val statusByAreaId: Map<String, PhoneOfflineBundleHealth> = emptyMap(),
    val download: PhoneOfflineBundleDownloadState = PhoneOfflineBundleDownloadState.Idle,
    val isCheckingUpdates: Boolean = false,
    val updateChecks: List<PhoneOfflineBundleUpdateCheck> = emptyList(),
    val selectedRefreshAreaIds: Set<String> = emptySet(),
)

/** Keeps the temporary bundle dialog independent of the companion map renderer and storage UI. */
internal class PhoneOfflineBundleViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val bundleStore = PhoneOfflineBundleStore(application)
    private val mapStore = PhoneOfflineMapStore(application)
    private val downloader = PhoneOfflineBundleDownloader(application, bundleStore = bundleStore)
    private val _uiState = MutableStateFlow(PhoneOfflineBundleUiState())
    val uiState: StateFlow<PhoneOfflineBundleUiState> = _uiState.asStateFlow()
    private var downloadJob: Job? = null

    init {
        refreshInstalledBundles()
    }

    fun start(selection: PhoneOfflineBundleSelection) {
        val area = OamDownloadCatalog.areas.firstOrNull { it.id == selection.area.id } ?: return
        if (downloadJob?.isActive == true) return
        downloadJob =
            viewModelScope.launch {
                val outcome =
                    try {
                        downloader.download(selection.copy(area = area)) { progress ->
                            _uiState.value =
                                _uiState.value.copy(
                                    download =
                                        PhoneOfflineBundleDownloadState.Downloading(
                                            areaId = area.id,
                                            progress = progress,
                                        ),
                                )
                        }
                    } catch (_: CancellationException) {
                        _uiState.value =
                            _uiState.value.copy(download = PhoneOfflineBundleDownloadState.Cancelled)
                        return@launch
                    }
                when (outcome) {
                    is PhoneOfflineBundleOutcome.Success -> {
                        val statuses =
                            withContext(Dispatchers.IO) {
                                bundleStatuses()
                            }
                        _uiState.value =
                            PhoneOfflineBundleUiState(
                                installedBundles = bundleStore.list(),
                                installedAreaIds = statuses.completeAreaIds(),
                                statusByAreaId = statuses,
                                download = PhoneOfflineBundleDownloadState.Completed(outcome.bundle),
                            )
                    }
                    is PhoneOfflineBundleOutcome.Failure -> {
                        _uiState.value =
                            _uiState.value.copy(
                                download = PhoneOfflineBundleDownloadState.Failed(outcome.reason),
                            )
                    }
                }
            }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloader.cancelActiveDownloads()
    }

    fun refreshInstalledBundles() {
        viewModelScope.launch {
            val statuses =
                withContext(Dispatchers.IO) {
                    bundleStatuses()
                }
            _uiState.value =
                _uiState.value.copy(
                    installedBundles = bundleStore.list(),
                    installedAreaIds = statuses.completeAreaIds(),
                    statusByAreaId = statuses,
                )
        }
    }

    fun checkForUpdates() {
        if (downloadJob?.isActive == true || _uiState.value.isCheckingUpdates) return
        viewModelScope.launch {
            val bundles =
                withContext(Dispatchers.IO) {
                    bundleStore.list()
                }
            _uiState.update {
                it.copy(
                    isCheckingUpdates = true,
                    updateChecks = emptyList(),
                    selectedRefreshAreaIds = emptySet(),
                )
            }
            val checks =
                bundles.map { bundle ->
                    runCatching { downloader.checkBundleUpdates(bundle) }
                        .getOrElse {
                            PhoneOfflineBundleUpdateCheck(
                                bundle = bundle,
                                status = PhoneOfflineBundleUpdateStatus.UNKNOWN,
                                checkedFileCount = 0,
                                unknownFileNames = listOf(it.message ?: "Update check failed"),
                            )
                        }
                }
            _uiState.update {
                val selectedRefreshAreaIds =
                    checks
                        .filter { check ->
                            check.status == PhoneOfflineBundleUpdateStatus.UPDATE_AVAILABLE ||
                                check.status == PhoneOfflineBundleUpdateStatus.REPAIR_NEEDED ||
                                check.status == PhoneOfflineBundleUpdateStatus.UNKNOWN
                        }.map { check -> check.bundle.areaId }
                        .toSet()
                it.copy(
                    isCheckingUpdates = false,
                    updateChecks = checks,
                    selectedRefreshAreaIds = selectedRefreshAreaIds,
                )
            }
        }
    }

    fun toggleRefreshSelection(areaId: String) {
        if (downloadJob?.isActive == true || _uiState.value.isCheckingUpdates) return
        _uiState.update { state ->
            val selected = state.selectedRefreshAreaIds
            state.copy(
                selectedRefreshAreaIds =
                    if (areaId in selected) selected - areaId else selected + areaId,
            )
        }
    }

    fun clearRefreshSelection() {
        _uiState.update { it.copy(selectedRefreshAreaIds = emptySet()) }
    }

    fun clearUpdateChecks() {
        _uiState.update { it.copy(updateChecks = emptyList(), selectedRefreshAreaIds = emptySet()) }
    }

    @Suppress("LongMethod") // Refreshing multiple bundles reuses one sequential download/recovery flow.
    fun refreshSelected() {
        if (downloadJob?.isActive == true || _uiState.value.isCheckingUpdates) return
        val state = _uiState.value
        val checks = state.updateChecks.filter { it.bundle.areaId in state.selectedRefreshAreaIds }
        if (checks.isEmpty()) return
        downloadJob =
            viewModelScope.launch {
                try {
                    var lastBundle: PhoneInstalledBundle? = null
                    checks.forEach { check ->
                        val area =
                            OamDownloadCatalog.areas.firstOrNull { it.id == check.bundle.areaId }
                                ?: return@forEach
                        val outcome =
                            downloader.download(
                                selection = check.bundle.toSelection(area),
                                forces = check.refreshForces(area),
                            ) { progress ->
                                _uiState.update {
                                    it.copy(
                                        download =
                                            PhoneOfflineBundleDownloadState.Downloading(
                                                areaId = area.id,
                                                progress = progress,
                                            ),
                                    )
                                }
                            }
                        when (outcome) {
                            is PhoneOfflineBundleOutcome.Success -> lastBundle = outcome.bundle
                            is PhoneOfflineBundleOutcome.Failure -> {
                                _uiState.update {
                                    it.copy(
                                        updateChecks = emptyList(),
                                        selectedRefreshAreaIds = emptySet(),
                                        download = PhoneOfflineBundleDownloadState.Failed(outcome.reason),
                                    )
                                }
                                return@launch
                            }
                        }
                    }
                    val statuses = withContext(Dispatchers.IO) { bundleStatuses() }
                    _uiState.value =
                        PhoneOfflineBundleUiState(
                            installedBundles = bundleStore.list(),
                            installedAreaIds = statuses.completeAreaIds(),
                            statusByAreaId = statuses,
                            download =
                                lastBundle?.let(PhoneOfflineBundleDownloadState::Completed)
                                    ?: PhoneOfflineBundleDownloadState.Idle,
                        )
                } catch (_: CancellationException) {
                    _uiState.update {
                        it.copy(
                            updateChecks = emptyList(),
                            selectedRefreshAreaIds = emptySet(),
                            download = PhoneOfflineBundleDownloadState.Cancelled,
                        )
                    }
                }
            }
    }

    override fun onCleared() {
        downloader.cancelActiveDownloads()
        super.onCleared()
    }

    private fun bundleStatuses(): Map<String, PhoneOfflineBundleHealth> {
        val statuses =
            bundleStore
                .list()
                .associate { bundle ->
                    bundle.areaId to
                        phoneOfflineBundleHealth(
                            context = getApplication(),
                            mapStore = mapStore,
                            bundle = bundle,
                            recovery = bundleStore.findRecovery(bundle.areaId),
                        )
                }
        return bundleStore.recoveries().fold(statuses) { result, recovery ->
            if (recovery.areaId in result) {
                result
            } else {
                result +
                    (
                        recovery.areaId to
                            PhoneOfflineBundleHealth(
                                status = PhoneOfflineBundleStatus.RECOVERY_NEEDED,
                                missingFileNames = recovery.routingFileNames + recovery.demTileIds,
                                hasRecovery = true,
                            )
                    )
            }
        }
    }
}

private fun PhoneInstalledBundle.toSelection(
    area: com.glancemap.trailcore.oam.OamDownloadArea,
): PhoneOfflineBundleSelection =
    PhoneOfflineBundleSelection(
        area = area,
        includeRouting = routingFileNames.isNotEmpty(),
        includeDem = demTileIds.isNotEmpty(),
        demSource = demSource,
        includeRefugesInfo = refugesInfoFileName != null,
    )

private fun Map<String, PhoneOfflineBundleHealth>.completeAreaIds(): Set<String> {
    val complete = filterValues { health -> health.isComplete }
    return complete.keys
}
