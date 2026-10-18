package com.glancemap.glancemapcompanionapp.weather

import com.glancemap.glancemapcompanionapp.routes.RouteWeatherUiState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal data class RouteWeatherScreenState(
    val routeName: String?,
    val isPreparingRoute: Boolean,
    val weather: RouteWeatherUiState,
)

internal fun weatherTime(value: String?): LocalDateTime? =
    value?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

internal fun weatherDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun WeatherForecast.localWeatherTime(epochMillis: Long): LocalDateTime =
    Instant
        .ofEpochMilli(epochMillis)
        .atZone(
            timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault(),
        ).toLocalDateTime()

/** Open-Meteo's full hourly series starts at midnight; match by time rather than array index. */
internal fun WeatherForecast.currentWeatherHour(now: LocalDateTime): WeatherHourlyOutlook? =
    hourly.firstOrNull { weatherTime(it.timeIso8601) == now.withMinute(0).withSecond(0).withNano(0) }

internal fun WeatherForecast.weatherHours(
    date: LocalDate,
    now: LocalDateTime,
): List<WeatherHourlyOutlook> =
    hourly
        .filter { hour ->
            val time = weatherTime(hour.timeIso8601)
            time?.toLocalDate() == date && !time.isBefore(now.withMinute(0).withSecond(0).withNano(0))
        }.sortedBy { it.timeIso8601 }

internal data class RouteWeatherSelection(
    val current: WeatherCurrentConditions?,
    val hour: WeatherHourlyOutlook?,
    val day: WeatherDailyOutlook?,
) {
    val temperature get() = if (current != null) current.temperatureCelsius else hour?.temperatureCelsius
    val feelsLike get() = if (current != null) current.apparentTemperatureCelsius else hour?.apparentTemperatureCelsius
    val code get() =
        if (current != null) {
            current.weatherCode
        } else if (hour != null) {
            hour.weatherCode
        } else {
            day?.weatherCode
        }
    val rainChance get() =
        if (current != null ||
            hour != null
        ) {
            hour?.precipitationProbabilityPercent
        } else {
            day?.precipitationProbabilityPercent
        }
    val rainAmount get() =
        if (current != null || hour != null) hour?.precipitationMillimeters else day?.precipitationMillimeters
    val wind get() = if (current != null) current.windSpeedKilometersPerHour else hour?.windSpeedKilometersPerHour
    val gusts get() =
        if (current != null) {
            current.windGustKilometersPerHour
        } else if (hour != null) {
            hour.windGustKilometersPerHour
        } else {
            day?.windGustKilometersPerHour
        }
}

internal fun WeatherForecast.weatherSelection(
    day: String?,
    hour: String?,
    now: LocalDateTime,
): RouteWeatherSelection {
    val selectedHour = hour?.let { time -> hourly.firstOrNull { it.timeIso8601 == time } }
    val selectedDate = weatherTime(selectedHour?.timeIso8601)?.toLocalDate() ?: weatherDate(day)
    val showCurrent = selectedHour == null && selectedDate == null
    return RouteWeatherSelection(
        current = current.takeIf { showCurrent },
        hour = selectedHour ?: currentWeatherHour(now).takeIf { showCurrent },
        day = daily.firstOrNull { weatherDate(it.date) == (selectedDate ?: now.toLocalDate()) },
    )
}

/** Report a material increase only when both probabilities are present in this day's data. */
internal fun rainIncreaseHour(hours: List<WeatherHourlyOutlook>): WeatherHourlyOutlook? {
    val known = hours.filter { it.precipitationProbabilityPercent != null && weatherTime(it.timeIso8601) != null }
    val baseline = known.firstOrNull()?.precipitationProbabilityPercent ?: return null
    return known.drop(1).firstOrNull { (it.precipitationProbabilityPercent ?: 0.0) >= baseline + 20.0 }
}
