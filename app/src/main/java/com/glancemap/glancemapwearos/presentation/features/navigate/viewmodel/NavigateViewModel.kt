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
    }

    fun onRecenterRequested() {
        isPanning.value = false
    }

    fun onToggleOrientation() {
        viewModelScope.launch {
            val current = settingsRepository.compassMode.first()
            settingsRepository.setCompassMode(!current)
        }
    }

    fun onLocationUpdate(latLong: LatLong) {
        _uiState.update { it.copy(lastKnownLocation = latLong) }
    }

    fun initZoom(defaultZoom: Int) {
        _uiState.update { state ->
            if (state.currentZoomLevel == 0) state.copy(currentZoomLevel = defaultZoom) else state
        }
    }

    fun onZoomChanged(newZoom: Int) {
        _uiState.update { it.copy(currentZoomLevel = newZoom) }
    }

    fun showCalibrationDialog() {
        _uiState.update { it.copy(showCalibrationDialog = true) }
    }

    fun hideCalibrationDialog() {
        _uiState.update { it.copy(showCalibrationDialog = false) }
    }
}
