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

    @Test
    fun routeWeatherDoesNotFillAFailedSampleFromADistantLocation() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan = PhoneHikePlan(model.route.id)
        val start = requireNotNull(model.atDistance(0.0))
        val end = requireNotNull(model.atDistance(model.totalDistanceMeters))
        val forecast = weatherSample("UTC", "2026-10-15T08:00")
        val samples = listOf(forecast.copy(segmentIndex = start.segmentIndex, fraction = start.fraction))
        val time = Instant.parse("2026-10-15T08:00:00Z")
        val lookup = PhoneHikeForecastLookup(model, plan, samples)
        assertNotNull(lookup.at(start, time, ZoneId.of("UTC")))
        assertNull(lookup.at(end, time, ZoneId.of("UTC")))
    }

    @Test
    fun forecastAtSameStopChangesWhenTheProjectedTimeChanges() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan = PhoneHikePlan(model.route.id)
        val start = requireNotNull(model.atDistance(0.0))
        val first = weatherSample("UTC", "2026-10-15T08:00")
        val secondHour =
            first.forecast.hourly
                .single()
                .copy(timeIso8601 = "2026-10-15T09:00", temperatureCelsius = 4.0)
        val sample =
            first.copy(
                segmentIndex = start.segmentIndex,
                fraction = start.fraction,
                forecast = first.forecast.copy(hourly = first.forecast.hourly + secondHour),
            )
        val lookup = PhoneHikeForecastLookup(model, plan, listOf(sample))
        val zone = ZoneId.of("UTC")
        assertEquals(12.0, lookup.at(start, Instant.parse("2026-10-15T08:00:00Z"), zone)?.outlook?.temperatureCelsius)
        assertEquals(4.0, lookup.at(start, Instant.parse("2026-10-15T09:00:00Z"), zone)?.outlook?.temperatureCelsius)
        assertNull(lookup.at(start, Instant.parse("2026-10-16T09:00:00Z"), zone))
    }

    @Test
    fun changingDepartureRecomputesBandsFromTheSameForecasts() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan = PhoneHikePlan(model.route.id, checkpoints = listOf(checkpoint(0, 0.0, 180)))
        val timeline = PhoneHikeTimeline(model, plan)
        val base = weatherSample("UTC", "2026-10-15T08:00")
        val firstHour =
            base.forecast.hourly
                .single()
                .copy(weatherCode = 0)
        val rainyHour = firstHour.copy(timeIso8601 = "2026-10-15T09:00", weatherCode = 61)
        val samples =
            phoneHikeWeatherPositions(model, plan).map {
                base.copy(
                    segmentIndex = it.segmentIndex,
                    fraction = it.fraction,
                    forecast = base.forecast.copy(hourly = listOf(firstHour, rainyHour)),
                )
            }
        val lookup = PhoneHikeForecastLookup(model, plan, samples)
        val zone = ZoneId.of("UTC")
        val early = phoneHikeWeatherBands(timeline, lookup, Instant.parse("2026-10-15T08:00:00Z"), zone)
        val late = phoneHikeWeatherBands(timeline, lookup, Instant.parse("2026-10-15T09:00:00Z"), zone)
        assertEquals(PhoneHikeCondition.CLEAR, early.first().condition)
        assertEquals(PhoneHikeCondition.RAIN, late.first().condition)
        assertNull(late.last().condition)
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
