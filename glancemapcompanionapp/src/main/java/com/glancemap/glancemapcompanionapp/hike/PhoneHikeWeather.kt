package com.glancemap.glancemapcompanionapp.hike

import com.glancemap.glancemapcompanionapp.weather.WeatherHourlyOutlook
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun phoneHikeWeatherPositions(
    model: PhoneHikeRouteModel,
    plan: PhoneHikePlan,
): List<PhoneHikePosition> {
    val distances =
        (
            listOf(0.0, model.totalDistanceMeters / 2.0, model.totalDistanceMeters) +
                model.orderedCheckpoints(plan).map { it.second.distanceMeters }
        ).distinct().sorted()
    val sampled =
        if (distances.size <=
            8
        ) {
            distances
        } else {
            (0..7).map { distances[(it * distances.lastIndex / 7.0).roundToInt()] }
        }
    return sampled.mapNotNull(model::atDistance)
}

internal fun PhoneHikeUiState.routeZone(): ZoneId =
    sequenceOf(plan?.zoneId, weather.firstOrNull()?.forecast?.timeZoneId)
        .mapNotNull { id -> id?.let { runCatching { ZoneId.of(it) }.getOrNull() } }
        .firstOrNull() ?: ZoneId.systemDefault()

internal fun phoneHikeArrivalInstant(
    model: PhoneHikeRouteModel,
    plan: PhoneHikePlan,
    position: PhoneHikePosition,
    zone: ZoneId,
    current: PhoneHikePosition? = null,
    now: Instant? = null,
): Instant? {
    if (current != null && now != null && position.distanceMeters >= current.distanceMeters) {
        val futureStops =
            model
                .orderedCheckpoints(plan)
                .filter {
                    it.second.distanceMeters > current.distanceMeters + 0.01 &&
                        it.second.distanceMeters < position.distanceMeters - 0.01
                }.sumOf { it.first.stopMinutes * 60L }
        return now.plusSeconds(
            (position.elapsedSeconds - current.elapsedSeconds).toLong().coerceAtLeast(0L) + futureStops,
        )
    }
    return plan.departureInstant(zone)?.plusSeconds(model.plannedSecondsAt(position, plan).toLong())
}

internal fun PhoneHikeWeatherSample.outlookAt(
    instant: Instant,
    fallbackZone: ZoneId,
): WeatherHourlyOutlook? {
    val zone = forecast.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone
    return forecast.hourly
        .mapNotNull { hour ->
            val time =
                hour.timeIso8601?.let { runCatching { LocalDateTime.parse(it).atZone(zone).toInstant() }.getOrNull() }
                    ?: return@mapNotNull null
            hour to abs(Duration.between(time, instant).seconds)
        }.minByOrNull { it.second }
        ?.takeIf { it.second <= 90L * 60L }
        ?.first
}
