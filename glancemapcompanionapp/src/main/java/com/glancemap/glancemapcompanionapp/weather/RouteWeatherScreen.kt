package com.glancemap.glancemapcompanionapp.weather

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.routes.RouteWeatherUiState
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun routeWeatherScreen(
    state: RouteWeatherScreenState,
    onBack: () -> Unit,
    onChooseRoute: () -> Unit,
    onLoadWeather: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.common_back_to_home_content_description),
                )
            }
            Text(
                stringResource(R.string.menu_route_weather),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(
                onClick = { onLoadWeather(true) },
                enabled = state.routeName != null && !state.isPreparingRoute && !state.weather.isLoading,
            ) {
                Icon(Icons.Default.Refresh, stringResource(R.string.briefing_refresh_weather))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.routeName ?: stringResource(R.string.briefing_choose_a_route),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onChooseRoute) { Text(stringResource(R.string.briefing_change_route)) }
        }
        state.weather.forecast?.let { forecast -> weatherLocationAndStatus(forecast, state.weather) }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            weatherLoadStatus(state, onLoadWeather)
            state.weather.forecast?.let { forecast -> routeWeatherForecastContent(forecast) }
            weatherAttribution()
        }
    }
}

@Composable
private fun weatherLocationAndStatus(
    forecast: WeatherForecast,
    state: RouteWeatherUiState,
) {
    val label =
        when (forecast.location.label) {
            "Route start" -> stringResource(R.string.weather_location_route_start)
            "Planned day start" -> stringResource(R.string.weather_location_planned_day_start)
            "Current route position" -> stringResource(R.string.weather_location_current_route_position)
            else -> forecast.location.label
        }
    Text(
        stringResource(
            R.string.route_weather_location,
            label,
            String.format(Locale.getDefault(), "%.4f, %.4f", forecast.location.latitude, forecast.location.longitude),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                R.string.mission_plan_updated_value,
                forecast
                    .localWeatherTime(forecast.fetchedAtEpochMillis)
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)),
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        if (state.isCached) {
            Text(
                stringResource(R.string.route_weather_cached),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (state.isStale) {
        Text(
            stringResource(R.string.briefing_showing_a_cached_forecast_unable_to_update),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun weatherLoadStatus(
    state: RouteWeatherScreenState,
    onLoad: (Boolean) -> Unit,
) {
    when {
        state.isPreparingRoute || state.weather.isLoading -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(
                        if (state.isPreparingRoute) {
                            R.string.briefing_preparing_gpx_forecast_location
                        } else {
                            R.string.briefing_loading_route_forecast
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.weather.forecast == null -> {
            Text(
                stringResource(
                    if (state.routeName == null) {
                        R.string.briefing_choose_an_imported_gpx_route_to_load
                    } else {
                        R.string.route_weather_ready_to_load
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.routeName != null) {
                Button(onClick = { onLoad(false) }) { Text(stringResource(R.string.briefing_load_route_weather)) }
            }
        }
    }
    state.weather.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun weatherAttribution() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching { uriHandler.openUri("https://open-meteo.com/") }.onFailure {
                Toast.makeText(context, R.string.briefing_unable_to_open_link, Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(stringResource(R.string.briefing_weather_data_by_open_meteo_com))
    }
}
