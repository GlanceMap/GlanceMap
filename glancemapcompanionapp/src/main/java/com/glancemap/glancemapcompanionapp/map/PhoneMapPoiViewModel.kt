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
    private val sourceVisibility = PhoneMapPoiSourceVisibilityPreferences(application)
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
    }

    fun setSourceVisible(
        fileName: String,
        visible: Boolean,
    ) {
        sourceVisibility.setEnabled(fileName, visible)
        _uiState.value =
            _uiState.value.copy(
                pois = emptyList(),
                sources =
                    _uiState.value.sources.map { source ->
                        if (source.fileName == fileName) source.copy(isEnabled = visible) else source
                    },
            )
        if (poiVisible) currentViewport?.let(::query)
    }

    suspend fun renameSource(
        fileName: String,
        newName: String,
    ): String {
        val renamedFileName = repository.renameSource(fileName, newName)
        sourceVisibility.rename(fileName, renamedFileName)
        refreshSourcesNow()
        return renamedFileName
    }

    suspend fun deleteSource(fileName: String) {
        repository.deleteSource(fileName)
        sourceVisibility.remove(fileName)
        refreshSourcesNow()
    }

    private fun refreshSources() {
        viewModelScope.launch {
            refreshSourcesNow()
        }
    }

    private suspend fun refreshSourcesNow() {
        queryJob?.cancel()
        requestId += 1L
        _uiState.value =
            _uiState.value.copy(
                pois = emptyList(),
                sources = repository.sources(sourceVisibility.disabledFileNames()),
            )
        if (poiVisible) currentViewport?.let(::query)
    }

    private fun query(viewport: PhoneMapViewport) {
        queryJob?.cancel()
        val expectedRequestId = ++requestId
        val enabledSourceFileNames = _uiState.value.sources.enabledFileNames()
        if (enabledSourceFileNames.isEmpty()) {
            _uiState.value = _uiState.value.copy(pois = emptyList())
            return
        }
        queryJob =
            viewModelScope.launch {
                delay(QUERY_DEBOUNCE_MILLIS)
                val pois =
                    repository.queryViewport(
                        viewport = viewport,
                        limit = MAXIMUM_POI_RESULTS,
                        enabledSourceFileNames = enabledSourceFileNames,
                    )
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
