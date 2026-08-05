package com.glancemap.glancemapcompanionapp.weather

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Open-Meteo forecast client for the companion's non-commercial development use.
 * Callers must show attribution and must replace the hosted endpoint or licence it before
 * commercial distribution.
 */
internal class OpenMeteoWeatherForecastProvider(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : WeatherForecastProvider {
    override suspend fun forecast(location: WeatherForecastLocation): WeatherForecast =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(forecastUrl(location))
                    .get()
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Open-Meteo forecast request failed (${response.code}).")
                }
                val body = response.body.string()
                OpenMeteoWeatherForecastParser.parse(
                    body = body,
                    location = location,
                    fetchedAtEpochMillis = nowEpochMillis(),
                )
            }
        }

    private fun forecastUrl(location: WeatherForecastLocation) =
        FORECAST_ENDPOINT
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("current", CURRENT_VARIABLES)
            .addQueryParameter("hourly", HOURLY_VARIABLES)
            .addQueryParameter("daily", DAILY_VARIABLES)
            .addQueryParameter("forecast_hours", FORECAST_HOURS.toString())
            .addQueryParameter("forecast_days", FORECAST_DAYS.toString())
            .addQueryParameter("wind_speed_unit", "kmh")
            .addQueryParameter("timezone", "auto")
            .apply {
                location.elevationMeters?.let { elevation ->
                    addQueryParameter("elevation", elevation.toString())
                }
            }.build()

    private companion object {
        const val FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        const val FORECAST_HOURS = 3
        const val FORECAST_DAYS = 10
        const val CURRENT_VARIABLES =
            "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m"
        const val HOURLY_VARIABLES =
            "precipitation_probability,precipitation,weather_code,wind_speed_10m," +
                "wind_gusts_10m,visibility,freezing_level_height"
        const val DAILY_VARIABLES =
            "weather_code,temperature_2m_min,temperature_2m_max,precipitation_probability_max," +
                "precipitation_sum,wind_gusts_10m_max,sunrise,sunset"

        val defaultHttpClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}

@Suppress("TooManyFunctions")
internal object OpenMeteoWeatherForecastParser {
    fun parse(
        body: String,
        location: WeatherForecastLocation,
        fetchedAtEpochMillis: Long,
    ): WeatherForecast {
        val root = JsonParser.parseString(body).asJsonObject
        return WeatherForecast(
            location = location,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            current = root.childObjectOrNull("current").toCurrentConditions(),
            nextHour = root.childObjectOrNull("hourly").toHourlyOutlook(),
            daily = root.childObjectOrNull("daily").toDailyOutlook(),
        )
    }

    private fun JsonObject?.toCurrentConditions(): WeatherCurrentConditions =
        WeatherCurrentConditions(
            temperatureCelsius = this.finiteDoubleOrNull("temperature_2m"),
            apparentTemperatureCelsius = this.finiteDoubleOrNull("apparent_temperature"),
            weatherCode = this?.weatherCodeOrNull("weather_code"),
            windSpeedKilometersPerHour = this.finiteDoubleOrNull("wind_speed_10m"),
            windGustKilometersPerHour = this.finiteDoubleOrNull("wind_gusts_10m"),
        )

    private fun JsonObject?.toHourlyOutlook(): WeatherHourlyOutlook? {
        val hourly = this ?: return null
        return WeatherHourlyOutlook(
            precipitationProbabilityPercent = hourly.finiteDoubleForNextHour("precipitation_probability"),
            precipitationMillimeters = hourly.finiteDoubleForNextHour("precipitation"),
            weatherCode = hourly.weatherCodeForNextHour("weather_code"),
            windSpeedKilometersPerHour = hourly.finiteDoubleForNextHour("wind_speed_10m"),
            windGustKilometersPerHour = hourly.finiteDoubleForNextHour("wind_gusts_10m"),
            visibilityMeters = hourly.finiteDoubleForNextHour("visibility"),
            freezingLevelHeightMeters = hourly.finiteDoubleForNextHour("freezing_level_height"),
        )
    }

    private fun JsonObject?.toDailyOutlook(): List<WeatherDailyOutlook> {
        val daily = this ?: return emptyList()
        val dates = daily.stringValues("time")
        return dates.mapIndexed { index, date ->
            WeatherDailyOutlook(
                date = date,
                weatherCode = daily.weatherCodeAt("weather_code", index),
                minimumTemperatureCelsius = daily.finiteDoubleAt("temperature_2m_min", index),
                maximumTemperatureCelsius = daily.finiteDoubleAt("temperature_2m_max", index),
                precipitationProbabilityPercent = daily.finiteDoubleAt("precipitation_probability_max", index),
                precipitationMillimeters = daily.finiteDoubleAt("precipitation_sum", index),
                windGustKilometersPerHour = daily.finiteDoubleAt("wind_gusts_10m_max", index),
                sunriseIso8601 = daily.stringAt("sunrise", index),
                sunsetIso8601 = daily.stringAt("sunset", index),
            )
        }
    }

    private fun JsonObject.childObjectOrNull(key: String): JsonObject? =
        get(key)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject

    private fun JsonObject?.finiteDoubleOrNull(key: String): Double? =
        this
            ?.get(key)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.let { value -> runCatching { value.asDouble }.getOrNull() }
            ?.takeIf(Double::isFinite)

    private fun JsonObject.weatherCodeOrNull(key: String): Int? = finiteDoubleOrNull(key)?.roundToInt()

    /** Open-Meteo's hourly response starts with the current hour. */
    private fun JsonObject.finiteDoubleForNextHour(key: String): Double? =
        getAsJsonArray(key)
            .finiteDoubleAt(NEXT_HOUR_INDEX)
            ?: getAsJsonArray(key).finiteDoubleAt(CURRENT_HOUR_INDEX)

    private fun JsonObject.weatherCodeForNextHour(key: String): Int? = finiteDoubleForNextHour(key)?.roundToInt()

    private fun JsonObject.finiteDoubleAt(
        key: String,
        index: Int,
    ): Double? = getAsJsonArray(key).finiteDoubleAt(index)

    private fun JsonObject.weatherCodeAt(
        key: String,
        index: Int,
    ): Int? = finiteDoubleAt(key, index)?.roundToInt()

    private fun JsonObject.stringValues(key: String): List<String> =
        getAsJsonArray(key)
            ?.mapNotNull { value ->
                value
                    .takeUnless(JsonElement::isJsonNull)
                    ?.let { item -> runCatching { item.asString }.getOrNull() }
                    ?.takeIf(String::isNotBlank)
            }.orEmpty()

    private fun JsonObject.stringAt(
        key: String,
        index: Int,
    ): String? =
        getAsJsonArray(key)
            ?.takeIf { values -> values.size() > index }
            ?.get(index)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.let { value -> runCatching { value.asString }.getOrNull() }
            ?.takeIf(String::isNotBlank)

    private fun JsonArray?.finiteDoubleAt(index: Int): Double? =
        this
            ?.takeIf { values -> values.size() > index && !values[index].isJsonNull }
            ?.get(index)
            ?.let { value -> runCatching { value.asDouble }.getOrNull() }
            ?.takeIf(Double::isFinite)

    private const val CURRENT_HOUR_INDEX = 0
    private const val NEXT_HOUR_INDEX = 1
}
