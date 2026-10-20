package com.glancemap.glancemapcompanionapp.weather

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routes.RouteWeatherUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocationWeatherViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository =
        WeatherForecastRepository(
            OpenMeteoWeatherForecastProvider(),
            FileWeatherForecastStore(application),
        )
    val controller =
        LocationWeatherController(
            scope = viewModelScope,
            forecast = { location, forceRefresh ->
                withContext(Dispatchers.IO) {
                    val result = repository.forecast(location, forceRefresh, requireHourlyForecast = true)
                    val forecast = result.forecast
                    RouteWeatherUiState(
                        forecast = forecast.copy(location = forecast.location.copy(label = location.label)),
                        isCached = result.source != WeatherForecastSource.NETWORK,
                        isStale = result.source == WeatherForecastSource.STALE_CACHE,
                        savedSnapshotCount = repository.history(location).size,
                    )
                }
            },
            currentLocation = {
                requestCurrentWeatherLocation(application, application.getString(R.string.weather_current_location))
            },
            locationUnavailable = application.getString(R.string.weather_location_unavailable),
            forecastUnavailable =
                application.getString(
                    R.string.route_library_weather_forecast_is_unavailable_check_your_connection,
                ),
        )
}
