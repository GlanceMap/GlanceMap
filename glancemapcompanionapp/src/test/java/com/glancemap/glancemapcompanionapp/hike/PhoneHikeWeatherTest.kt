package com.glancemap.glancemapcompanionapp.hike

import com.glancemap.glancemapcompanionapp.weather.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PhoneHikeWeatherTest {
    @Test
    fun providerTimeZoneIsUsedUnlessThePlanOverridesIt() {
        val forecast =
            OpenMeteoWeatherForecastParser.parse(
                """{"timezone":"Europe/Paris"}""",
                WeatherForecastLocation(46.0, 11.0, null, "Route"),
                0L,
            )
        val ui = PhoneHikeUiState(weather = listOf(PhoneHikeWeatherSample(0, .5, forecast, false)))
        assertEquals(ZoneId.of("Europe/Paris"), ui.routeZone())
        val plan = PhoneHikePlan("route", zoneId = "America/New_York")
        val explicit = ui.copy(data = PhoneHikeWorkspaceData(todayId = "route", todayPlan = plan))
        assertEquals(ZoneId.of("America/New_York"), explicit.routeZone())
    }

    @Test
    fun arrivalUsesDepartureZoneTerrainEtaAndPrecedingStops() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan =
            PhoneHikePlan(
                model.route.id,
                "2026-10-15",
                "08:00",
                "Europe/Paris",
                checkpoints = listOf(checkpoint(0, .5, 15)),
            )
        val end = requireNotNull(model.atDistance(model.totalDistanceMeters))
        assertEquals(
            Instant.parse("2026-10-15T06:00:00Z").plusSeconds((end.elapsedSeconds + 900).toLong()),
            phoneHikeArrivalInstant(model, plan, end, ZoneId.of("UTC")),
        )
        assertNull(phoneHikeArrivalInstant(model, PhoneHikePlan(model.route.id), end, ZoneId.of("UTC")))
    }

    @Test
    fun liveArrivalUsesRemainingEtaAndOnlyFutureStops() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan = PhoneHikePlan(model.route.id, checkpoints = listOf(checkpoint(0, .1, 60), checkpoint(1, .5, 10)))
        val current = requireNotNull(model.position(0, .5))
        val end = requireNotNull(model.atDistance(model.totalDistanceMeters))
        val now = Instant.parse("2026-10-15T10:00:00Z")
        assertEquals(
            now.plusSeconds((end.elapsedSeconds - current.elapsedSeconds).toLong() + 600),
            phoneHikeArrivalInstant(model, plan, end, ZoneId.of("UTC"), current, now),
        )
    }

    @Test
    fun forecastUsesRouteZoneAndRejectsUncoveredDates() {
        val sample = weatherSample("Europe/Paris", "2026-10-16T00:00")
        assertNotNull(sample.outlookAt(Instant.parse("2026-10-15T22:10:00Z"), ZoneId.of("America/New_York")))
        assertNull(sample.outlookAt(Instant.parse("2026-11-15T22:10:00Z"), ZoneId.of("Europe/Paris")))
    }

    @Test
    fun weatherSamplingIsBoundedAndRetainsBothEndpoints() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan = PhoneHikePlan(model.route.id, checkpoints = (1..30).map { checkpoint(0, it / 31.0) })
        val samples = phoneHikeWeatherPositions(model, plan)
        assertEquals(8, samples.size)
        assertEquals(0.0, samples.first().distanceMeters, .001)
        assertEquals(model.totalDistanceMeters, samples.last().distanceMeters, .001)
        val reverse = PhoneHikeRouteModel(model.route, reversed = true)
        assertEquals(
            model.route.points
                .last()
                .location,
            phoneHikeWeatherPositions(reverse, plan).first().coordinate,
        )
    }

    @Test
    fun departureUsesDaylightSavingOffsetOfSelectedDate() {
        val before = PhoneHikePlan("a", "2026-10-24", "08:00", "Europe/Paris")
        val after = before.copy(date = "2026-10-25")
        assertEquals(Instant.parse("2026-10-24T06:00:00Z"), before.departureInstant(ZoneId.of("UTC")))
        assertEquals(Instant.parse("2026-10-25T07:00:00Z"), after.departureInstant(ZoneId.of("UTC")))
    }
}

private fun weatherSample(
    zone: String,
    hour: String,
): PhoneHikeWeatherSample =
    PhoneHikeWeatherSample(
        0,
        .5,
        WeatherForecast(
            WeatherForecastLocation(46.0, 11.0, null, "Route"),
            0L,
            WeatherCurrentConditions(null, null, null, null, null),
            null,
            hourly = listOf(WeatherHourlyOutlook(null, null, null, null, null, null, null, hour, 12.0)),
            timeZoneId = zone,
        ),
        false,
    )
