package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PhoneMapPoiUiState(
    val pois: List<PhoneMapPoi> = emptyList(),
    val sources: List<PhoneMapPoiSource> = emptyList(),
)

/** Owns cancellable, debounced phone POI viewport queries outside the MapLibre composable. */
internal class PhoneMapPoiViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = PhoneMapPoiRepository(application)
    private val _uiState = MutableStateFlow(PhoneMapPoiUiState())
    val uiState: StateFlow<PhoneMapPoiUiState> = _uiState.asStateFlow()

    private var currentViewport: PhoneMapViewport? = null
    private var poiVisible = true
    private var requestId = 0L
    private var queryJob: Job? = null

    init {
        refreshSources()
    }

    fun onViewportChanged(viewport: PhoneMapViewport) {
        currentViewport = viewport
        if (poiVisible) query(viewport)
    }

    fun setPoiVisible(visible: Boolean) {
        if (poiVisible == visible) return
        poiVisible = visible
        if (!visible) {
            queryJob?.cancel()
            _uiState.value = _uiState.value.copy(pois = emptyList())
        } else {
            currentViewport?.let(::query)
        }
    }

    fun refresh() {
        refreshSources()
        if (poiVisible) currentViewport?.let(::query)
    }

    private fun refreshSources() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sources = repository.sources())
        }
    }

    private fun query(viewport: PhoneMapViewport) {
        queryJob?.cancel()
        val expectedRequestId = ++requestId
        queryJob =
            viewModelScope.launch {
                delay(QUERY_DEBOUNCE_MILLIS)
                val pois = repository.queryViewport(viewport = viewport, limit = MAXIMUM_POI_RESULTS)
                if (poiVisible && expectedRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(pois = pois)
                }
            }
    }

    private companion object {
        private const val MAXIMUM_POI_RESULTS = 180
        private const val QUERY_DEBOUNCE_MILLIS = 200L
    }
}
