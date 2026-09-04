package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.trailcore.oam.OamDownloadCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface PhoneOfflineBundleDownloadState {
    data object Idle : PhoneOfflineBundleDownloadState

    data class Downloading(
        val areaId: String,
        val progress: PhoneOfflineBundleProgress,
    ) : PhoneOfflineBundleDownloadState

    data class Paused(
        val areaId: String,
        val progress: PhoneOfflineBundleProgress,
    ) : PhoneOfflineBundleDownloadState

    data class Stopped(
        val areaId: String,
        val progress: PhoneOfflineBundleProgress,
    ) : PhoneOfflineBundleDownloadState

    data class Completed(
        val bundle: PhoneInstalledBundle,
    ) : PhoneOfflineBundleDownloadState

    data class Failed(
        val reason: PhoneOfflineBundleFailure,
        val areaId: String? = null,
        val context: PhoneOfflineBundleFailureContext? = null,
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
    private val downloadClient = PhoneOfflineBundleDownloadClient(application)
    private val _uiState = MutableStateFlow(PhoneOfflineBundleUiState())
    val uiState: StateFlow<PhoneOfflineBundleUiState> = _uiState.asStateFlow()

    init {
        PhoneOfflineBundleDownloadRuntime.state
            .onEach(::handleDownloadState)
            .launchIn(viewModelScope)
        refreshInstalledBundles()
        downloadClient.resumeIfNeeded()
    }

    fun start(selection: PhoneOfflineBundleSelection) {
        val area = OamDownloadCatalog.areas.firstOrNull { it.id == selection.area.id } ?: return
        downloadClient.start(selection.copy(area = area))
    }

    fun pause() = downloadClient.pause()

    fun stop() = downloadClient.stop()

    fun cancel() = downloadClient.cancel()

    fun resume() = downloadClient.resume()

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
        if (isDownloadActive() || _uiState.value.isCheckingUpdates) return
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
        if (isDownloadActive() || _uiState.value.isCheckingUpdates) return
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

    fun refreshSelected() {
        if (isDownloadActive() || _uiState.value.isCheckingUpdates) return
        val state = _uiState.value
        val checks = state.updateChecks.filter { it.bundle.areaId in state.selectedRefreshAreaIds }
        if (checks.isEmpty()) return
        val requests =
            checks.mapNotNull { check ->
                OamDownloadCatalog.areas
                    .firstOrNull { area -> area.id == check.bundle.areaId }
                    ?.let { area ->
                        check.bundle.toSelection(area) to check.refreshForces(area)
                    }
            }
        if (requests.isNotEmpty()) {
            downloadClient.start(
                selections = requests.map { request -> request.first },
                refreshForces = requests.map { request -> request.second },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    private fun isDownloadActive(): Boolean = PhoneOfflineBundleDownloadRuntime.state.value is PhoneOfflineBundleDownloadState.Downloading

    private fun handleDownloadState(state: PhoneOfflineBundleDownloadState) {
        _uiState.update { current ->
            current.copy(
                download = state,
                updateChecks =
                    if (state is PhoneOfflineBundleDownloadState.Completed) emptyList() else current.updateChecks,
                selectedRefreshAreaIds =
                    if (state is PhoneOfflineBundleDownloadState.Completed) {
                        emptySet()
                    } else {
                        current.selectedRefreshAreaIds
                    },
            )
        }
        if (state !is PhoneOfflineBundleDownloadState.Downloading) {
            refreshInstalledBundles()
        }
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

internal fun PhoneOfflineBundleDownloadState.canCancelSavedOperation(): Boolean =
    this is PhoneOfflineBundleDownloadState.Paused ||
        this is PhoneOfflineBundleDownloadState.Stopped ||
        this is PhoneOfflineBundleDownloadState.Failed

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
