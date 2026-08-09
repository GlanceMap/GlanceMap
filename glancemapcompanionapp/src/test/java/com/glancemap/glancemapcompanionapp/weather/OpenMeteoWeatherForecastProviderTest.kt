package com.glancemap.glancemapcompanionapp.weather

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class OpenMeteoWeatherForecastProviderTest {
    @Test
    fun `parser maps current and next hour mountain forecast fields`() {
        val forecast =
            OpenMeteoWeatherForecastParser.parse(
                body =
                    """
                    {
                      "current": {
                        "temperature_2m": 7.4,
                        "apparent_temperature": 3.9,
                        "weather_code": 3,
                        "wind_speed_10m": 24.5,
                        "wind_gusts_10m": 51.2
                      },
                      "hourly": {
                        "time": ["2026-08-05T08:00", "2026-08-05T09:00"],
                        "temperature_2m": [7.4, 8.1],
                        "apparent_temperature": [3.9, 4.7],
                        "precipitation_probability": [25, 65],
                        "precipitation": [0.1, 1.2],
                        "weather_code": [3, 61],
                        "wind_speed_10m": [18.0, 28.0],
                        "wind_gusts_10m": [32.0, 58.0],
                        "visibility": [12000, 3500],
                        "freezing_level_height": [2600, 2450]
                      },
                      "daily": {
                        "time": ["2026-08-05", "2026-08-06"],
                        "weather_code": [3, 61],
                        "temperature_2m_min": [4.2, 5.1],
                        "temperature_2m_max": [12.4, 13.3],
                        "precipitation_probability_max": [25, 65],
                        "precipitation_sum": [0.1, 4.8],
                        "wind_gusts_10m_max": [31.0, 58.0],
                        "sunrise": ["2026-08-05T06:02", "2026-08-06T06:03"],
                        "sunset": ["2026-08-05T20:34", "2026-08-06T20:33"]
                      }
                    }
                    """.trimIndent(),
                location = testLocation(),
                fetchedAtEpochMillis = 5L,
            )

        assertEquals(7.4, forecast.current.temperatureCelsius ?: 0.0, 0.001)
        assertEquals(3, forecast.current.weatherCode)
        assertEquals(65.0, forecast.nextHour?.precipitationProbabilityPercent ?: 0.0, 0.001)
        assertEquals(3_500.0, forecast.nextHour?.visibilityMeters ?: 0.0, 0.001)
        assertEquals(2_450.0, forecast.nextHour?.freezingLevelHeightMeters ?: 0.0, 0.001)
        assertEquals(2, forecast.hourly.size)
        assertEquals("2026-08-05T09:00", forecast.hourly[1].timeIso8601)
        assertEquals(8.1, forecast.hourly[1].temperatureCelsius ?: 0.0, 0.001)
        assertEquals(2, forecast.daily.size)
        assertEquals("2026-08-05", forecast.daily.first().date)
        assertEquals(12.4, forecast.daily.first().maximumTemperatureCelsius ?: 0.0, 0.001)
        assertEquals(58.0, forecast.daily[1].windGustKilometersPerHour ?: 0.0, 0.001)
    }

    @Test
    fun `parser treats unavailable optional fields as absent`() {
        val forecast =
            OpenMeteoWeatherForecastParser.parse(
                body = "{\"current\": {}, \"hourly\": {\"visibility\": [null]}}",
                location = testLocation(),
                fetchedAtEpochMillis = 5L,
            )

        assertNull(forecast.current.temperatureCelsius)
        assertNull(forecast.current.weatherCode)
        assertNull(forecast.nextHour?.visibilityMeters)
    }

    @Test
    fun `repository reuses fresh cache and falls back to it after a failed refresh`() =
        runBlocking {
            val firstForecast = forecast(fetchedAtEpochMillis = 1_000L)
            var requestCount = 0
            var failRequests = false
            val repository =
                WeatherForecastRepository(
                    provider =
                        object : WeatherForecastProvider {
                            override suspend fun forecast(location: WeatherForecastLocation): WeatherForecast {
                                requestCount += 1
                                if (failRequests) throw IOException("network unavailable")
                                return firstForecast
                            }
                        },
                    nowEpochMillis = { 2_000L },
                )

            val initial = repository.forecast(testLocation(), forceRefresh = false)
            val cached = repository.forecast(testLocation(), forceRefresh = false)
            failRequests = true
            val stale = repository.forecast(testLocation(), forceRefresh = true)

            assertEquals(2, requestCount)
            assertSame(firstForecast, initial.forecast)
            assertSame(firstForecast, cached.forecast)
            assertEquals(WeatherForecastSource.MEMORY_CACHE, cached.source)
            assertSame(firstForecast, stale.forecast)
            assertEquals(WeatherForecastSource.STALE_CACHE, stale.source)
        }

    @Test
    fun `repository uses a fresh persisted snapshot and exposes its history`() =
        runBlocking {
            val persisted = forecast(fetchedAtEpochMillis = 1_000L)
            val store = InMemoryWeatherForecastStore(persisted)
            val repository =
                WeatherForecastRepository(
                    provider =
                        object : WeatherForecastProvider {
                            override suspend fun forecast(location: WeatherForecastLocation) = unexpectedNetworkCall()
                        },
                    store = store,
                    nowEpochMillis = { 2_000L },
                )

            val result = repository.forecast(testLocation(), forceRefresh = false)

            assertSame(persisted, result.forecast)
            assertEquals(WeatherForecastSource.PERSISTED_CACHE, result.source)
            assertEquals(listOf(persisted), repository.history(testLocation()))
        }

    @Test
    fun `repository refreshes a legacy snapshot when planned weather needs hourly data`() =
        runBlocking {
            val legacySnapshot = forecast(fetchedAtEpochMillis = 1_000L)
            val hourlySnapshot =
                legacySnapshot.copy(
                    hourly =
                        listOf(
                            WeatherHourlyOutlook(
                                precipitationProbabilityPercent = 20.0,
                                precipitationMillimeters = 0.0,
                                weatherCode = 2,
                                windSpeedKilometersPerHour = 12.0,
                                windGustKilometersPerHour = 24.0,
                                visibilityMeters = 10_000.0,
                                freezingLevelHeightMeters = 2_500.0,
                                timeIso8601 = "2026-08-05T08:00",
                            ),
                        ),
                )
            var requestCount = 0
            val repository =
                WeatherForecastRepository(
                    provider =
                        object : WeatherForecastProvider {
                            override suspend fun forecast(location: WeatherForecastLocation): WeatherForecast {
                                requestCount += 1
                                return hourlySnapshot
                            }
                        },
                    store = InMemoryWeatherForecastStore(legacySnapshot),
                    nowEpochMillis = { 2_000L },
                )

            val result = repository.forecast(testLocation(), forceRefresh = false, requireHourlyForecast = true)

            assertEquals(1, requestCount)
            assertSame(hourlySnapshot, result.forecast)
        }

    private fun testLocation(): WeatherForecastLocation =
        WeatherForecastLocation(
            latitude = 46.5,
            longitude = 11.9,
            elevationMeters = 2_400.0,
            label = "Route start",
        )

    private fun forecast(fetchedAtEpochMillis: Long): WeatherForecast =
        WeatherForecast(
            location = testLocation(),
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            current =
                WeatherCurrentConditions(
                    temperatureCelsius = 6.0,
                    apparentTemperatureCelsius = 4.0,
                    weatherCode = 2,
                    windSpeedKilometersPerHour = 12.0,
                    windGustKilometersPerHour = 24.0,
                ),
            nextHour = null,
        )

    private class InMemoryWeatherForecastStore(
        initial: WeatherForecast,
    ) : WeatherForecastStore {
        private val snapshots = mutableListOf(initial)

        override suspend fun latest(location: WeatherForecastLocation) = matching(location).firstOrNull()

        override suspend fun history(location: WeatherForecastLocation) = matching(location)

        override suspend fun record(forecast: WeatherForecast) {
            snapshots.removeAll { snapshot -> snapshot.fetchedAtEpochMillis == forecast.fetchedAtEpochMillis }
            snapshots.add(0, forecast)
        }

        private fun matching(location: WeatherForecastLocation) = snapshots.filter { it.location == location }
    }

    private fun unexpectedNetworkCall(): Nothing = error("Network should not be used.")
}
