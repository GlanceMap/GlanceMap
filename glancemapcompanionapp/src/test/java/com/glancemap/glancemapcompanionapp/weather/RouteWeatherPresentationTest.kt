package com.glancemap.glancemapcompanionapp.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class RouteWeatherPresentationTest {
    private val now = LocalDateTime.parse("2026-10-17T13:25")

    @Test
    fun `current rain is matched to the actual hour rather than the second midnight entry`() {
        val early = hour("2026-10-17T01:00", rain = 5.0)
        val afternoon = hour("2026-10-17T13:00", rain = 70.0)
        val forecast = forecast(listOf(early, afternoon)).copy(nextHour = early)
        val selected = forecast.weatherSelection(null, null, now)
        assertEquals(70.0, selected.rainChance)
        assertSame(forecast.current, selected.current)
        assertEquals(12.0, selected.temperature)
    }

    @Test
    fun `hourly strip keeps the current hour and future hours in order and rejects invalid times`() {
        val hours =
            listOf(
                hour("2026-10-17T15:00"),
                hour("invalid"),
                hour("2026-10-17T12:00"),
                hour("2026-10-18T08:00"),
                hour("2026-10-17T13:00"),
            )
        assertEquals(
            listOf("2026-10-17T13:00", "2026-10-17T15:00"),
            forecast(hours).weatherHours(now.toLocalDate(), now).map { it.timeIso8601 },
        )
        assertEquals(1, forecast(hours).weatherHours(now.toLocalDate().plusDays(1), now).size)
    }

    @Test
    fun `forecast timezone sets the local calendar day independently of the phone`() {
        val forecast = forecast(emptyList()).copy(timeZoneId = "Pacific/Auckland")
        val local = forecast.localWeatherTime(Instant.parse("2026-10-17T18:30:00Z").toEpochMilli())
        assertEquals(LocalDateTime.parse("2026-10-18T07:30"), local)
        assertEquals(LocalDate.parse("2026-10-18"), local.toLocalDate())
    }

    @Test
    fun `choosing an hour selects its temperature rain wind and matching sunrise day`() {
        val hour = hour("2026-10-18T08:00", rain = 60.0)
        val selected = forecast(listOf(hour)).weatherSelection("2026-10-17", hour.timeIso8601, now)
        assertNull(selected.current)
        assertSame(hour, selected.hour)
        assertEquals(8.0, selected.temperature)
        assertEquals(60.0, selected.rainChance)
        assertEquals(14.0, selected.wind)
        assertEquals(35.0, selected.gusts)
        assertEquals("2026-10-18T07:30", selected.day?.sunriseIso8601)
    }

    @Test
    fun `daily summary uses daily aggregates and never borrows current wind or temperature`() {
        val selected = forecast(emptyList()).weatherSelection("2026-10-18", null, now)
        assertNull(selected.current)
        assertNull(selected.hour)
        assertNull(selected.temperature)
        assertNull(selected.feelsLike)
        assertNull(selected.wind)
        assertEquals(80.0, selected.rainChance)
        assertEquals(5.0, selected.rainAmount)
        assertEquals(50.0, selected.gusts)
    }

    @Test
    fun `missing hourly values remain absent rather than borrowing daily values`() {
        val hour =
            hour("2026-10-18T08:00").copy(
                precipitationProbabilityPercent = null,
                precipitationMillimeters = null,
                windGustKilometersPerHour = null,
                weatherCode = null,
            )
        val selected = forecast(listOf(hour)).weatherSelection("2026-10-18", hour.timeIso8601, now)
        assertNull(selected.rainChance)
        assertNull(selected.rainAmount)
        assertNull(selected.gusts)
        assertNull(selected.code)
    }

    @Test
    fun `legacy cached forecast without hourly data does not invent hourly rain`() {
        val selected =
            forecast(emptyList())
                .copy(nextHour = hour("2026-10-17T01:00"))
                .weatherSelection(null, null, now)
        assertNull(selected.hour)
        assertNull(selected.rainChance)
        assertEquals(12.0, selected.temperature)
    }

    @Test
    fun `highlight requires known probabilities with a material increase`() {
        val hours =
            listOf(
                hour("2026-10-17T13:00", rain = 10.0),
                hour("2026-10-17T14:00", rain = 15.0),
                hour("2026-10-17T15:00", rain = 60.0),
            )
        assertSame(hours.last(), rainIncreaseHour(hours))
        assertNull(rainIncreaseHour(hours.take(2)))
        assertNull(rainIncreaseHour(listOf(hours.last())))
        assertNull(rainIncreaseHour(hours.map { it.copy(precipitationProbabilityPercent = null) }))
    }

    private fun forecast(hours: List<WeatherHourlyOutlook>) =
        WeatherForecast(
            location = WeatherForecastLocation(45.0, 6.0, 1500.0, "Route start"),
            fetchedAtEpochMillis = Instant.parse("2026-10-17T13:25:00Z").toEpochMilli(),
            current = WeatherCurrentConditions(12.0, 10.0, 2, 10.0, 20.0),
            nextHour = null,
            hourly = hours,
            daily =
                listOf(
                    WeatherDailyOutlook(
                        "2026-10-18",
                        61,
                        4.0,
                        14.0,
                        80.0,
                        5.0,
                        50.0,
                        "2026-10-18T07:30",
                        "2026-10-18T18:30",
                    ),
                ),
            timeZoneId = "UTC",
        )

    private fun hour(
        time: String,
        rain: Double = 20.0,
    ) = WeatherHourlyOutlook(
        precipitationProbabilityPercent = rain,
        precipitationMillimeters = 1.5,
        weatherCode = 61,
        windSpeedKilometersPerHour = 14.0,
        windGustKilometersPerHour = 35.0,
        visibilityMeters = 5000.0,
        freezingLevelHeightMeters = 2500.0,
        timeIso8601 = time,
        temperatureCelsius = 8.0,
        apparentTemperatureCelsius = 5.0,
    )
}
