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
    val download: PhoneOfflineBundleDownloadState = PhoneOfflineBundleDownloadState.Idle,
)

/** Keeps the temporary bundle dialog independent of the companion map renderer and storage UI. */
internal class PhoneOfflineBundleViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val bundleStore = PhoneOfflineBundleStore(application)
    private val downloader = PhoneOfflineBundleDownloader(application, bundleStore = bundleStore)
    private val _uiState = MutableStateFlow(PhoneOfflineBundleUiState())
    val uiState: StateFlow<PhoneOfflineBundleUiState> = _uiState.asStateFlow()
    private var downloadJob: Job? = null

    init {
        refreshInstalledBundles()
    }

    fun start(areaId: String) {
        val area = OamDownloadCatalog.areas.firstOrNull { it.id == areaId } ?: return
        if (downloadJob?.isActive == true) return
        downloadJob =
            viewModelScope.launch {
                val outcome =
                    try {
                        downloader.download(PhoneOfflineBundleSelection(area)) { progress ->
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
                        val installedAreaIds =
                            withContext(Dispatchers.IO) {
                                bundleStore.list().mapTo(linkedSetOf()) { it.areaId }
                            }
                        _uiState.value =
                            PhoneOfflineBundleUiState(
                                installedAreaIds = installedAreaIds,
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
            val installedAreaIds =
                withContext(Dispatchers.IO) {
                    bundleStore.list().mapTo(linkedSetOf()) { it.areaId }
                }
            _uiState.value = _uiState.value.copy(installedAreaIds = installedAreaIds)
        }
    }

    override fun onCleared() {
        downloader.cancelActiveDownloads()
        super.onCleared()
    }
}
