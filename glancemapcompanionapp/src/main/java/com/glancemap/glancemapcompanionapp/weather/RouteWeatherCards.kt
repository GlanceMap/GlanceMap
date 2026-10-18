package com.glancemap.glancemapcompanionapp.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun weatherSummary(
    selection: RouteWeatherSelection,
    label: String,
) {
    Card(Modifier.fillMaxWidth().testTag("weather-summary").semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                weatherConditionIcon(selection.code, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selection.current == null && selection.hour == null) {
                            weatherNumber(selection.day?.maximumTemperatureCelsius, "°C")
                        } else {
                            weatherNumber(selection.temperature, "°C")
                        },
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(weatherConditionLabel(selection.code), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (selection.current == null && selection.hour == null) {
                Text(weatherHighLow(selection.day), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    stringResource(R.string.route_weather_feels_like, weatherNumber(selection.feelsLike, "°C")),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun weatherMetricCards(selection: RouteWeatherSelection) {
    val daily = selection.current == null && selection.hour == null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.WaterDrop, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.route_weather_rain), style = MaterialTheme.typography.titleMedium)
                Text(weatherNumber(selection.rainChance, "%"), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.route_weather_probability), style = MaterialTheme.typography.labelSmall)
                Text(
                    stringResource(
                        if (daily) R.string.route_weather_daily_amount else R.string.route_weather_hourly_amount,
                        weatherNumber(selection.rainAmount, " mm", 1),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Air, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.route_weather_wind), style = MaterialTheme.typography.titleMedium)
                Text(weatherNumber(selection.wind, " km/h"), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.route_weather_speed), style = MaterialTheme.typography.labelSmall)
                Text(
                    stringResource(
                        if (daily) R.string.route_weather_max_gusts else R.string.route_weather_gusts,
                        weatherNumber(selection.gusts, " km/h"),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun weatherMountainDetails(selection: RouteWeatherSelection) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expansion = stringResource(if (expanded) R.string.route_weather_expanded else R.string.route_weather_collapsed)
    Card(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier =
                Modifier.fillMaxWidth().testTag("weather-mountain-toggle").semantics {
                    stateDescription =
                        expansion
                },
        ) {
            Text(stringResource(R.string.route_weather_mountain), Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                weatherDetailRow(
                    stringResource(R.string.route_weather_visibility),
                    weatherNumber(selection.hour?.visibilityMeters?.div(1000.0), " km", 1),
                )
                weatherDetailRow(
                    stringResource(R.string.route_weather_freezing),
                    weatherNumber(selection.hour?.freezingLevelHeightMeters, " m"),
                )
                weatherDetailRow(
                    stringResource(R.string.route_weather_sunrise),
                    weatherClock(selection.day?.sunriseIso8601),
                )
                weatherDetailRow(
                    stringResource(R.string.route_weather_sunset),
                    weatherClock(selection.day?.sunsetIso8601),
                )
                if (selection.hour == null) {
                    Text(stringResource(R.string.route_weather_pick_hour), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun weatherDetailRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
