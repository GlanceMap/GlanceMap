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
                        "precipitation_probability": [25, 65],
                        "precipitation": [0.1, 1.2],
                        "weather_code": [3, 61],
                        "wind_speed_10m": [18.0, 28.0],
                        "wind_gusts_10m": [32.0, 58.0],
                        "visibility": [12000, 3500],
                        "freezing_level_height": [2600, 2450]
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
            assertEquals(WeatherForecastSource.CACHE, cached.source)
            assertSame(firstForecast, stale.forecast)
            assertEquals(WeatherForecastSource.STALE_CACHE, stale.source)
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
}
