package com.glancemap.glancemapcompanionapp.routes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.diagnostics.CompanionJourneyDiagnostics
import com.glancemap.glancemapcompanionapp.weather.FileWeatherForecastStore
import com.glancemap.glancemapcompanionapp.weather.OpenMeteoWeatherForecastProvider
import com.glancemap.glancemapcompanionapp.weather.WeatherForecast
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastRepository
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastSource
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RouteWeatherUiState(
    val isLoading: Boolean = false,
    val forecast: WeatherForecast? = null,
    val isCached: Boolean = false,
    val isStale: Boolean = false,
    val savedSnapshotCount: Int = 0,
    val message: String? = null,
)

class RouteLibraryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = RouteLibraryRepository(application)
    private val _uiState = MutableStateFlow(RouteLibraryUiState())
    val uiState: StateFlow<RouteLibraryUiState> = _uiState.asStateFlow()
    private val _selectedRouteDetails = MutableStateFlow<RouteLibraryRouteDetails?>(null)
    val selectedRouteDetails: StateFlow<RouteLibraryRouteDetails?> = _selectedRouteDetails.asStateFlow()
    private val weatherForecastRepository =
        WeatherForecastRepository(
            provider = OpenMeteoWeatherForecastProvider(),
            store = FileWeatherForecastStore(application),
        )
    private val _routeWeatherUiState = MutableStateFlow(RouteWeatherUiState())
    val routeWeatherUiState: StateFlow<RouteWeatherUiState> = _routeWeatherUiState.asStateFlow()

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
        CompanionJourneyDiagnostics.routeImportStarted()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, message = null)
            val result = runCatching { withContext(Dispatchers.IO) { repository.importRoute(uri) } }
            result.onFailure { error ->
                if (error !is CancellationException) CompanionJourneyDiagnostics.routeImportFailed()
            }
            publishRouteState(
                result
                    .getOrElse { error ->
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

    suspend fun renameRoute(
        routeId: String,
        newTitle: String,
    ) {
        publishRouteState(withContext(Dispatchers.IO) { repository.renameRoute(routeId, newTitle) })
    }

    suspend fun deleteRoute(routeId: String) {
        publishRouteState(withContext(Dispatchers.IO) { repository.deleteRoute(routeId) })
    }

    fun selectedRouteContentUri(): android.net.Uri? {
        val selectedRoute = _uiState.value.selectedRoute
        return selectedRoute?.let { route -> repository.contentUriFor(route.id) }
    }

    fun contentUriFor(routeId: String): android.net.Uri? = repository.contentUriFor(routeId)

    fun loadRouteWeather(
        activeHikeSnapshot: ActiveHikeSnapshot?,
        forceRefresh: Boolean,
        plannedStartDistanceMeters: Double = 0.0,
    ) {
        val routeDetails =
            _selectedRouteDetails.value
                ?: run {
                    CompanionJourneyDiagnostics.routeWeatherUnavailable()
                    return
                }
        val weatherLocation =
            routeDetails.weatherLocationFor(
                activeHikeSnapshot = activeHikeSnapshot,
                plannedStartDistanceMeters = plannedStartDistanceMeters,
            )
                ?: run {
                    CompanionJourneyDiagnostics.routeWeatherUnavailable()
                    return
                }
        CompanionJourneyDiagnostics.routeWeatherRequested(forceRefresh)
        viewModelScope.launch {
            _routeWeatherUiState.value =
                _routeWeatherUiState.value.copy(
                    isLoading = true,
                    message = null,
                )
            runCatching {
                withContext(Dispatchers.IO) {
                    val result =
                        weatherForecastRepository.forecast(
                            location = weatherLocation,
                            forceRefresh = forceRefresh,
                        )
                    result to weatherForecastRepository.history(weatherLocation)
                }
            }.onSuccess { result ->
                CompanionJourneyDiagnostics.routeWeatherSucceeded(result.first.source)
                _routeWeatherUiState.value =
                    RouteWeatherUiState(
                        forecast = result.first.forecast,
                        isCached = result.first.source != WeatherForecastSource.NETWORK,
                        isStale = result.first.source == WeatherForecastSource.STALE_CACHE,
                        savedSnapshotCount = result.second.size,
                    )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                CompanionJourneyDiagnostics.routeWeatherFailed(error)
                _routeWeatherUiState.value =
                    _routeWeatherUiState.value.copy(
                        isLoading = false,
                        message = "Weather forecast is unavailable. Check your connection and try again.",
                    )
            }
        }
    }

    private fun publishRouteState(state: RouteLibraryUiState) {
        _uiState.value = state
        loadSelectedRouteDetails(state.selectedRoute?.id)
        _routeWeatherUiState.value = RouteWeatherUiState()
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
