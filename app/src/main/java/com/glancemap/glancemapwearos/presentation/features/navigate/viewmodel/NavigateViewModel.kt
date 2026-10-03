package com.glancemap.glancemapwearos.presentation.features.navigate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong

class NavigateViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NavigateUiState())
    val uiState: StateFlow<NavigateUiState> = _uiState.asStateFlow()

    private val settingsRepository: SettingsRepository =
        SettingsRepositoryImpl.getInstance(application)

    private val isPanning = MutableStateFlow(false)

    val navMode: StateFlow<NavMode> =
        combine(
            settingsRepository.compassMode,
            isPanning,
        ) { isCompass, panning ->
            when {
                panning -> NavMode.PANNING
                isCompass -> NavMode.COMPASS_FOLLOW
                else -> NavMode.NORTH_UP_FOLLOW
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavMode.COMPASS_FOLLOW)

    init {
        navMode
            .onEach { mode ->
                _uiState.update { it.copy(navMode = mode) }
            }.launchIn(viewModelScope)
    }

    fun onUserPanStarted() {
        isPanning.value = true
        onStartupMapFallbackEvent(StartupMapFallbackEvent.CANCELLED)
    }

    fun onRecenterRequested() {
        isPanning.value = false
        onStartupMapFallbackEvent(StartupMapFallbackEvent.CANCELLED)
    }

    fun onToggleOrientation() {
        viewModelScope.launch {
            val current = settingsRepository.compassMode.first()
            settingsRepository.setCompassMode(!current)
        }
    }

    fun onAcceptedLocationUpdate(
        latLong: LatLong,
        fixElapsedRealtimeMs: Long,
        accuracyM: Float,
        sourceEpoch: Long,
        sourceModeName: String? = null,
    ) {
        _uiState.update { state ->
            state.copy(
                lastKnownLocation = latLong,
                retainedLocationAnchor =
                    RetainedLocationAnchor(
                        latLong = latLong,
                        fixElapsedRealtimeMs = fixElapsedRealtimeMs,
                        accuracyM = accuracyM,
                        sourceEpoch = sourceEpoch,
                        sourceModeName = sourceModeName,
                    ),
                startupMapFallbackState =
                    if (state.startupMapFallbackState == StartupMapFallbackState.COMPLETED) {
                        StartupMapFallbackState.COMPLETED
                    } else {
                        StartupMapFallbackState.CANCELLED
                    },
            )
        }
    }

    fun onDisplayedLocationAnchor(anchor: RetainedLocationAnchor) {
        _uiState.update { state ->
            val existing = state.retainedLocationAnchor
            val nextAnchor =
                when {
                    existing == null || existing.fixElapsedRealtimeMs < anchor.fixElapsedRealtimeMs -> anchor
                    existing.fixElapsedRealtimeMs == anchor.fixElapsedRealtimeMs ->
                        existing.copy(latLong = anchor.latLong)
                    else -> existing
                }
            state.copy(
                retainedLocationAnchor = nextAnchor,
                startupMapFallbackState =
                    if (state.startupMapFallbackState == StartupMapFallbackState.COMPLETED) {
                        StartupMapFallbackState.COMPLETED
                    } else {
                        StartupMapFallbackState.CANCELLED
                    },
            )
        }
    }

    fun onRenderedLocationUpdate(latLong: LatLong) {
        _uiState.update { state ->
            val anchor = state.retainedLocationAnchor ?: return@update state
            state.copy(
                retainedLocationAnchor = anchor.copy(latLong = latLong),
            )
        }
    }

    fun onStartupMapFallbackEvent(event: StartupMapFallbackEvent) {
        _uiState.update { state ->
            when (event) {
                StartupMapFallbackEvent.TIMER_EXPIRED ->
                    if (state.startupMapFallbackState == StartupMapFallbackState.WAITING) {
                        state.copy(startupMapFallbackState = StartupMapFallbackState.READY)
                    } else {
                        state
                    }
                StartupMapFallbackEvent.CENTERING_APPLIED ->
                    if (state.startupMapFallbackState == StartupMapFallbackState.READY) {
                        state.copy(startupMapFallbackState = StartupMapFallbackState.COMPLETED)
                    } else {
                        state
                    }
                StartupMapFallbackEvent.CANCELLED ->
                    if (state.startupMapFallbackState == StartupMapFallbackState.COMPLETED) {
                        state
                    } else {
                        state.copy(startupMapFallbackState = StartupMapFallbackState.CANCELLED)
                    }
            }
        }
    }

    fun initZoom(defaultZoom: Int) {
        _uiState.update { state ->
            if (state.currentZoomLevel == 0) state.copy(currentZoomLevel = defaultZoom) else state
        }
    }

    fun onZoomChanged(newZoom: Int) {
        _uiState.update { it.copy(currentZoomLevel = newZoom) }
    }

    fun setCalibrationDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showCalibrationDialog = visible) }
    }
}
