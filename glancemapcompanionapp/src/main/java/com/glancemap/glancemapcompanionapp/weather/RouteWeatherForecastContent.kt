package com.glancemap.glancemapcompanionapp.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun routeWeatherForecastContent(forecast: WeatherForecast) {
    var day by rememberSaveable(forecast.location, forecast.fetchedAtEpochMillis) { mutableStateOf<String?>(null) }
    var hour by rememberSaveable(forecast.location, forecast.fetchedAtEpochMillis) { mutableStateOf<String?>(null) }
    val now = forecast.localWeatherTime(System.currentTimeMillis())
    val selection = forecast.weatherSelection(day, hour, forecast.localWeatherTime(forecast.fetchedAtEpochMillis))
    val date = weatherTime(hour)?.toLocalDate() ?: weatherDate(day) ?: now.toLocalDate()
    val hours = forecast.weatherHours(date, now)
    weatherSummary(selection, selectionLabel(day, hour, forecast))
    key(date) {
        weatherHoursStrip(hours, hour, date == now.toLocalDate() && day == null) { chosen ->
            hour = chosen?.timeIso8601
            day = chosen?.timeIso8601?.let { weatherTime(it)?.toLocalDate()?.toString() }
        }
    }
    rainIncreaseHour(hours)?.let { increase ->
        Text(
            stringResource(
                R.string.route_weather_rain_increase,
                weatherNumber(increase.precipitationProbabilityPercent, "%"),
                weatherClock(increase.timeIso8601),
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    weatherMetricCards(selection)
    if (forecast.daily.isNotEmpty()) {
        weatherDaysStrip(forecast.daily, date, now.toLocalDate()) { chosen ->
            day = chosen.date
            hour = null
        }
    }
    weatherMountainDetails(selection)
}

@Composable
private fun selectionLabel(
    day: String?,
    hour: String?,
    forecast: WeatherForecast,
): String =
    when {
        hour != null -> weatherTime(hour)?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) ?: hour
        day != null ->
            weatherDayLabel(
                weatherDate(day),
                forecast.localWeatherTime(System.currentTimeMillis()).toLocalDate(),
            )
        else ->
            stringResource(
                R.string.route_weather_at_update,
                forecast
                    .localWeatherTime(forecast.fetchedAtEpochMillis)
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)),
            )
    }

@Composable
private fun weatherHoursStrip(
    hours: List<WeatherHourlyOutlook>,
    selectedHour: String?,
    showCurrent: Boolean,
    onSelect: (WeatherHourlyOutlook?) -> Unit,
) {
    Text(stringResource(R.string.route_weather_hourly), style = MaterialTheme.typography.titleSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("weather-hours")) {
        item {
            weatherChoice(showCurrent, "weather-current", { onSelect(null) }) {
                Text(
                    stringResource(R.string.route_weather_at_update_short),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        items(hours, key = { checkNotNull(it.timeIso8601) }) { outlook ->
            weatherChoice(
                outlook.timeIso8601 == selectedHour,
                "weather-hour-${outlook.timeIso8601}",
                { onSelect(outlook) },
            ) {
                Text(weatherClock(outlook.timeIso8601), style = MaterialTheme.typography.labelMedium)
                weatherConditionIcon(outlook.weatherCode)
                Text(weatherNumber(outlook.temperatureCelsius, "°C"), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.route_weather_rain_chance,
                        weatherNumber(outlook.precipitationProbabilityPercent, "%"),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    if (hours.isEmpty()) {
        Text(stringResource(R.string.route_weather_no_hours), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun weatherDaysStrip(
    days: List<WeatherDailyOutlook>,
    selectedDate: LocalDate,
    today: LocalDate,
    onSelect: (WeatherDailyOutlook) -> Unit,
) {
    Text(stringResource(R.string.route_weather_upcoming), style = MaterialTheme.typography.titleSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("weather-days")) {
        items(days, key = { it.date }) { day ->
            weatherChoice(weatherDate(day.date) == selectedDate, "weather-day-${day.date}", { onSelect(day) }) {
                Text(weatherDayLabel(weatherDate(day.date), today), style = MaterialTheme.typography.labelMedium)
                weatherConditionIcon(day.weatherCode)
                Text(weatherHighLow(day), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        R.string.route_weather_rain_chance,
                        weatherNumber(day.precipitationProbabilityPercent, "%"),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun weatherChoice(
    isSelected: Boolean,
    tag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        modifier =
            Modifier.testTag(tag).semantics {
                selected = isSelected
                role = Role.Tab
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}
