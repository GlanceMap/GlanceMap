package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PhoneMapGpxUiState(
    val items: List<PhoneMapGpxItem> = emptyList(),
    val isLoading: Boolean = false,
)

/** Loads canonical Route Library geometry once, while retaining per-item map visibility choices. */
internal class PhoneMapGpxViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = RouteLibraryRepository(application)
    private val _uiState = MutableStateFlow(PhoneMapGpxUiState())
    val uiState: StateFlow<PhoneMapGpxUiState> = _uiState.asStateFlow()
    private var sources: List<PhoneMapGpxSource> = emptyList()
    private var loadJob: Job? = null

    fun synchronize(
        nextSources: List<PhoneMapGpxSource>,
        initiallyEnabledId: String?,
    ) {
        if (nextSources == sources) return
        sources = nextSources
        loadJob?.cancel()
        if (nextSources.isEmpty()) {
            _uiState.value = PhoneMapGpxUiState()
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadJob =
            viewModelScope.launch {
                val loaded =
                    withContext(Dispatchers.IO) {
                        nextSources.mapNotNull { source ->
                            repository.routeDetails(source.id)?.let { details ->
                                PhoneMapGpxItem(
                                    id = source.id,
                                    displayName = source.displayName,
                                    track = PhoneMapGpxTrack(source.id, details.profile.points),
                                    enabled = false,
                                )
                            }
                        }
                    }
                if (sources != nextSources) return@launch
                _uiState.value =
                    PhoneMapGpxUiState(
                        items = mergePhoneMapGpxItems(_uiState.value.items, loaded, initiallyEnabledId),
                    )
            }
    }

    fun toggle(id: String) {
        _uiState.value = _uiState.value.copy(items = _uiState.value.items.toggleEnabled(id))
    }
}
