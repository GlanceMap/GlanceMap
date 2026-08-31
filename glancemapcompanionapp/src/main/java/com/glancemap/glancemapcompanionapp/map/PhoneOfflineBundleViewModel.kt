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
    val installedAreaIds: Set<String> = emptySet(),
    val statusByAreaId: Map<String, PhoneOfflineBundleHealth> = emptyMap(),
    val download: PhoneOfflineBundleDownloadState = PhoneOfflineBundleDownloadState.Idle,
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
                    installedAreaIds = statuses.completeAreaIds(),
                    statusByAreaId = statuses,
                )
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

private fun Map<String, PhoneOfflineBundleHealth>.completeAreaIds(): Set<String> {
    val complete = filterValues { health -> health.isComplete }
    return complete.keys
}
