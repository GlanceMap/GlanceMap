package com.glancemap.glancemapcompanionapp.routes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteLibraryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = RouteLibraryRepository(application)
    private val _uiState = MutableStateFlow(RouteLibraryUiState())
    val uiState: StateFlow<RouteLibraryUiState> = _uiState.asStateFlow()
    private val _selectedRouteDetails = MutableStateFlow<RouteLibraryRouteDetails?>(null)
    val selectedRouteDetails: StateFlow<RouteLibraryRouteDetails?> = _selectedRouteDetails.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            publishRouteState(
                runCatching {
                    withContext(Dispatchers.IO) { repository.load() }
                }.getOrElse { error ->
                    RouteLibraryUiState(
                        isLoading = false,
                        message = error.message ?: "Could not load routes.",
                    )
                },
            )
        }
    }

    fun importRoute(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, message = null)
            publishRouteState(
                runCatching {
                    withContext(Dispatchers.IO) { repository.importRoute(uri) }
                }.getOrElse { error ->
                    _uiState.value.copy(
                        isImporting = false,
                        message = error.message ?: "Could not import the GPX route.",
                    )
                }.copy(isImporting = false),
            )
        }
    }

    fun selectRoute(routeId: String) {
        viewModelScope.launch {
            publishRouteState(
                runCatching {
                    withContext(Dispatchers.IO) { repository.selectRoute(routeId) }
                }.getOrElse { error ->
                    _uiState.value.copy(message = error.message ?: "Could not select the route.")
                },
            )
        }
    }

    fun selectedRouteContentUri(): android.net.Uri? {
        val selectedRoute = _uiState.value.selectedRoute
        return selectedRoute?.let { route -> repository.contentUriFor(route.id) }
    }

    fun contentUriFor(routeId: String): android.net.Uri? = repository.contentUriFor(routeId)

    private fun publishRouteState(state: RouteLibraryUiState) {
        _uiState.value = state
        loadSelectedRouteDetails(state.selectedRoute?.id)
    }

    private fun loadSelectedRouteDetails(routeId: String?) {
        _selectedRouteDetails.value = null
        if (routeId == null) return
        viewModelScope.launch {
            val details =
                runCatching {
                    withContext(Dispatchers.IO) { repository.routeDetails(routeId) }
                }.getOrNull()
            if (_uiState.value.selectedRouteId == routeId) {
                _selectedRouteDetails.value = details
            }
        }
    }
}
