package com.glancemap.glancemapcompanionapp.weather

import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.CancellationException

data class WeatherForecastLocation(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val label: String,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(elevationMeters == null || elevationMeters.isFinite())
        require(label.isNotBlank())
    }
}

data class WeatherForecast(
    val location: WeatherForecastLocation,
    val fetchedAtEpochMillis: Long,
    val current: WeatherCurrentConditions,
    val nextHour: WeatherHourlyOutlook?,
    val hourly: List<WeatherHourlyOutlook> = emptyList(),
    val daily: List<WeatherDailyOutlook> = emptyList(),
) {
    init {
        require(fetchedAtEpochMillis >= 0L)
    }
}

data class WeatherCurrentConditions(
    val temperatureCelsius: Double?,
    val apparentTemperatureCelsius: Double?,
    val weatherCode: Int?,
    val windSpeedKilometersPerHour: Double?,
    val windGustKilometersPerHour: Double?,
)

data class WeatherHourlyOutlook(
    val precipitationProbabilityPercent: Double?,
    val precipitationMillimeters: Double?,
    val weatherCode: Int?,
    val windSpeedKilometersPerHour: Double?,
    val windGustKilometersPerHour: Double?,
    val visibilityMeters: Double?,
    val freezingLevelHeightMeters: Double?,
    /** Local ISO-8601 time returned for this location by Open-Meteo. */
    val timeIso8601: String? = null,
    val temperatureCelsius: Double? = null,
    val apparentTemperatureCelsius: Double? = null,
)

data class WeatherDailyOutlook(
    val date: String,
    val weatherCode: Int?,
    val minimumTemperatureCelsius: Double?,
    val maximumTemperatureCelsius: Double?,
    val precipitationProbabilityPercent: Double?,
    val precipitationMillimeters: Double?,
    val windGustKilometersPerHour: Double?,
    val sunriseIso8601: String?,
    val sunsetIso8601: String?,
)

interface WeatherForecastProvider {
    suspend fun forecast(location: WeatherForecastLocation): WeatherForecast
}

internal data class WeatherForecastLoad(
    val forecast: WeatherForecast,
    val source: WeatherForecastSource,
)

internal enum class WeatherForecastSource {
    NETWORK,
    MEMORY_CACHE,
    PERSISTED_CACHE,
    STALE_CACHE,
}

/** Keeps weather available through transient connection loss without continuously re-querying a route. */
internal class WeatherForecastRepository(
    private val provider: WeatherForecastProvider,
    private val store: WeatherForecastStore? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val cache = mutableMapOf<WeatherForecastCacheKey, WeatherForecast>()

    suspend fun forecast(
        location: WeatherForecastLocation,
        forceRefresh: Boolean,
        requireHourlyForecast: Boolean = false,
    ): WeatherForecastLoad {
        val cacheKey = WeatherForecastCacheKey.from(location)
        val memoryCached = synchronized(cache) { cache[cacheKey] }
        val persistedCached = memoryCached ?: persistedLatest(location)
        val freshCached = persistedCached?.takeIf { forecast -> forecast.isFresh(nowEpochMillis()) }
        val cachedForecastHasRequiredCoverage =
            freshCached?.let { forecast -> !requireHourlyForecast || forecast.hourly.isNotEmpty() } == true
        if (!forceRefresh && cachedForecastHasRequiredCoverage) {
            val forecast = checkNotNull(freshCached)
            val source =
                if (memoryCached != null) {
                    WeatherForecastSource.MEMORY_CACHE
                } else {
                    WeatherForecastSource.PERSISTED_CACHE
                }
            return WeatherForecastLoad(forecast, source)
        } else {
            return requestForecast(location, cacheKey, persistedCached)
        }
    }

    /** Cache history is supplementary; a broken local cache must never hide live weather. */
    @Suppress("MaxLineLength")
    suspend fun history(location: WeatherForecastLocation): List<WeatherForecast> = runStoreOperation { history(location) }.orEmpty()

    private suspend fun requestForecast(
        location: WeatherForecastLocation,
        cacheKey: WeatherForecastCacheKey,
        cached: WeatherForecast?,
    ): WeatherForecastLoad =
        runCatching { provider.forecast(location) }
            .fold(
                onSuccess = { forecast ->
                    synchronized(cache) { cache[cacheKey] = forecast }
                    runStoreOperation { record(forecast) }
                    WeatherForecastLoad(forecast, WeatherForecastSource.NETWORK)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    cached?.let { forecast ->
                        WeatherForecastLoad(forecast, WeatherForecastSource.STALE_CACHE)
                    } ?: throw error
                },
            )

    @Suppress("MaxLineLength")
    private suspend fun persistedLatest(location: WeatherForecastLocation): WeatherForecast? = runStoreOperation { latest(location) }

    private suspend fun <T> runStoreOperation(operation: suspend WeatherForecastStore.() -> T): T? =
        try {
            store?.operation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun WeatherForecast.isFresh(now: Long): Boolean = now - fetchedAtEpochMillis in 0L..FRESH_CACHE_MILLIS

    private data class WeatherForecastCacheKey(
        val latitudeBucket: Int,
        val longitudeBucket: Int,
        val elevationBucket: Int?,
    ) {
        companion object {
            fun from(location: WeatherForecastLocation): WeatherForecastCacheKey =
                WeatherForecastCacheKey(
                    latitudeBucket = (location.latitude * CACHE_COORDINATE_SCALE).toInt(),
                    longitudeBucket = (location.longitude * CACHE_COORDINATE_SCALE).toInt(),
                    elevationBucket = location.elevationMeters?.toInt(),
                )
        }
    }

    private companion object {
        const val CACHE_COORDINATE_SCALE = 1_000.0
        const val FRESH_CACHE_MILLIS = 30L * 60L * 1_000L
    }
}

fun weatherConditionText(
    resources: android.content.res.Resources,
    weatherCode: Int?,
): String =
    when (weatherCode) {
        0 -> resources.getString(R.string.weather_condition_clear_sky)
        1 -> resources.getString(R.string.weather_condition_mainly_clear)
        2 -> resources.getString(R.string.weather_condition_partly_cloudy)
        3 -> resources.getString(R.string.weather_condition_overcast)
        45, 48 -> resources.getString(R.string.weather_condition_fog)
        51, 53, 55, 56, 57 -> resources.getString(R.string.weather_condition_drizzle)
        61, 63, 65, 66, 67 -> resources.getString(R.string.weather_condition_rain)
        71, 73, 75, 77 -> resources.getString(R.string.weather_condition_snow)
        80, 81, 82 -> resources.getString(R.string.weather_condition_rain_showers)
        85, 86 -> resources.getString(R.string.weather_condition_snow_showers)
        95, 96, 99 -> resources.getString(R.string.weather_condition_thunderstorm)
        else -> resources.getString(R.string.mission_plan_forecast_unavailable)
    }
