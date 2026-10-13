package com.glancemap.glancemapcompanionapp.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneMapSourcePreferences
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleStore
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMapStore
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.map.phoneOfflineBundleHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal data class PhoneLibraryLocalState(
    val items: List<PhoneLibraryItem> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
)

internal class PhoneLibraryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val storage = PhoneOfflineStorage(application)
    private val maps = PhoneOfflineMapStore(application)
    private val bundles = PhoneOfflineBundleStore(application)
    private val preferences = PhoneMapSourcePreferences(application)
    private val state = MutableStateFlow(PhoneLibraryLocalState())
    val uiState = state.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                state.value = state.value.copy(isLoading = true, message = null)
                try {
                    val items =
                        withContext(Dispatchers.IO) {
                            receivedPhoneLibraryFiles(storage.watchGpxExportsDirectory()) +
                                maps.discover().map { PhoneLibraryItem.OfflineMap(it, it.file.length()) } +
                                bundles.list().map { bundle ->
                                    PhoneLibraryItem.Bundle(
                                        bundle = bundle,
                                        health =
                                            phoneOfflineBundleHealth(
                                                context = getApplication(),
                                                mapStore = maps,
                                                bundle = bundle,
                                                recovery = bundles.findRecovery(bundle.areaId),
                                            ),
                                        map = maps.findValidBundleMap(bundle.mapFileName),
                                    )
                                }
                        }
                    state.value = PhoneLibraryLocalState(items)
                } catch (_: IOException) {
                    showError(R.string.library_load_error)
                } catch (_: SecurityException) {
                    showError(R.string.library_load_error)
                }
            }
    }

    suspend fun openOfflineMap(
        map: PhoneOfflineMap,
        onReady: () -> Unit,
    ) {
        state.value = state.value.copy(isLoading = true, message = null)
        try {
            val error = withContext(Dispatchers.IO) { maps.validate(map) }
            if (error != null) {
                showError(R.string.library_map_open_error)
            } else {
                preferences.saveOffline(map)
                state.value = state.value.copy(isLoading = false)
                onReady()
            }
        } catch (_: IOException) {
            showError(R.string.library_map_open_error)
        } catch (_: SecurityException) {
            showError(R.string.library_map_open_error)
        }
    }

    private fun showError(message: Int) {
        state.value =
            state.value.copy(
                isLoading = false,
                message = getApplication<Application>().getString(message),
            )
    }
}
