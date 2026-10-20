package com.glancemap.glancemapcompanionapp.weather

import com.glancemap.glancemapcompanionapp.routes.RouteWeatherUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal data class LocationWeatherState(
    val location: WeatherForecastLocation? = null,
    val isLocating: Boolean = false,
    val weather: RouteWeatherUiState = RouteWeatherUiState(),
)

/** Explicit location forecasts have independent state from route, mission and live-hike weather. */
internal class LocationWeatherController(
    private val scope: CoroutineScope,
    private val forecast: suspend (WeatherForecastLocation, Boolean) -> RouteWeatherUiState,
    private val currentLocation: suspend () -> WeatherForecastLocation?,
    private val locationUnavailable: String,
    private val forecastUnavailable: String,
) {
    private val mutableState = MutableStateFlow(LocationWeatherState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun current() = request(null, false)

    fun load(
        location: WeatherForecastLocation,
        forceRefresh: Boolean = false,
    ) = request(location, forceRefresh)

    fun cancel() {
        generation++
        job?.cancel()
        mutableState.value = state.value.copy(isLocating = false, weather = state.value.weather.copy(isLoading = false))
    }

    fun locationDenied() {
        cancel()
        mutableState.value = LocationWeatherState(weather = RouteWeatherUiState(message = locationUnavailable))
    }

    fun clear() {
        cancel()
        mutableState.value = LocationWeatherState()
    }

    private fun request(
        location: WeatherForecastLocation?,
        forceRefresh: Boolean,
    ) {
        cancel()
        val requestGeneration = generation
        val previous = state.value.weather.takeIf { location != null && state.value.location == location }
        mutableState.value =
            LocationWeatherState(
                location = location,
                isLocating = location == null,
                weather = (previous ?: RouteWeatherUiState()).copy(isLoading = true, message = null),
            )
        job =
            scope.launch {
                runCatching {
                    val target = location ?: currentLocation() ?: error("Location unavailable")
                    coroutineContext.ensureActive()
                    if (generation == requestGeneration) {
                        mutableState.value = state.value.copy(location = target, isLocating = false)
                    }
                    target to forecast(target, forceRefresh)
                }.onSuccess { (target, weather) ->
                    if (generation == requestGeneration) {
                        mutableState.value = LocationWeatherState(target, weather = weather)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    if (generation == requestGeneration) {
                        val message = if (state.value.location == null) locationUnavailable else forecastUnavailable
                        mutableState.value =
                            state.value.copy(
                                isLocating = false,
                                weather = state.value.weather.copy(isLoading = false, message = message),
                            )
                    }
                }
            }
    }
}
