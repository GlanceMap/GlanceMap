package com.glancemap.glancemapcompanionapp.weather

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun weatherConditionLabel(code: Int?): String = weatherConditionText(LocalResources.current, code)

@Composable
internal fun weatherConditionIcon(
    code: Int?,
    modifier: Modifier = Modifier,
) {
    val icon =
        when (code) {
            0, 1 -> Icons.Default.WbSunny
            2 -> Icons.Default.WbCloudy
            3 -> Icons.Default.Cloud
            45, 48 -> Icons.Default.BlurOn
            51, 53, 55, 56, 57 -> Icons.Default.Grain
            61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Default.WaterDrop
            71, 73, 75, 77, 85, 86 -> Icons.Default.AcUnit
            95, 96, 99 -> Icons.Default.Thunderstorm
            else -> Icons.AutoMirrored.Filled.HelpOutline
        }
    Icon(icon, weatherConditionLabel(code), modifier, tint = MaterialTheme.colorScheme.primary)
}

internal fun weatherNumber(
    value: Double?,
    suffix: String,
    decimals: Int = 0,
): String =
    value?.takeIf(Double::isFinite)?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) + suffix } ?: "—"

internal fun weatherClock(value: String?): String =
    weatherTime(value)?.toLocalTime()?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: "—"

@Composable
internal fun weatherDayLabel(
    date: LocalDate?,
    today: LocalDate,
): String =
    when (date) {
        today -> stringResource(R.string.route_weather_today)
        today.plusDays(1) -> stringResource(R.string.route_weather_tomorrow)
        null -> "—"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }

@Composable
internal fun weatherHighLow(day: WeatherDailyOutlook?): String =
    stringResource(
        R.string.route_weather_high_low,
        weatherNumber(day?.maximumTemperatureCelsius, "°"),
        weatherNumber(day?.minimumTemperatureCelsius, "°"),
    )
