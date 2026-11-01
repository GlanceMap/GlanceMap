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

internal data class PhoneHikeForecastMatch(
    val sample: PhoneHikeWeatherSample,
    val outlook: WeatherHourlyOutlook,
)

internal class PhoneHikeForecastLookup(
    model: PhoneHikeRouteModel,
    plan: PhoneHikePlan,
    samples: List<PhoneHikeWeatherSample>,
) {
    private val locations =
        phoneHikeWeatherPositions(model, plan).map { expected ->
            expected.distanceMeters to
                samples.firstOrNull {
                    it.segmentIndex == expected.segmentIndex && abs(it.fraction - expected.fraction) < 0.000001
                }
        }

    fun at(
        position: PhoneHikePosition,
        instant: Instant,
        zone: ZoneId,
    ): PhoneHikeForecastMatch? {
        val sample = locations.minByOrNull { abs(it.first - position.distanceMeters) }?.second ?: return null
        return sample.outlookAt(instant, zone)?.let { PhoneHikeForecastMatch(sample, it) }
    }
}

internal enum class PhoneHikeCondition { CLEAR, CLOUDY, RAIN, SNOW, STORM }

internal fun phoneHikeCondition(code: Int?): PhoneHikeCondition? =
    when (code) {
        0, 1 -> PhoneHikeCondition.CLEAR
        2, 3, 45, 48 -> PhoneHikeCondition.CLOUDY
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> PhoneHikeCondition.RAIN
        71, 73, 75, 77, 85, 86 -> PhoneHikeCondition.SNOW
        95, 96, 99 -> PhoneHikeCondition.STORM
        else -> null
    }

internal data class PhoneHikeWeatherBand(
    val startSeconds: Double,
    val endSeconds: Double,
    val condition: PhoneHikeCondition?,
)

internal fun phoneHikeWeatherBands(
    timeline: PhoneHikeTimeline,
    lookup: PhoneHikeForecastLookup,
    departure: Instant?,
    zone: ZoneId,
): List<PhoneHikeWeatherBand> {
    if (departure == null || timeline.totalSeconds <= 0.0) return emptyList()
    val count = (timeline.totalSeconds / 600.0).roundToInt().coerceIn(64, 384)
    val bands = mutableListOf<PhoneHikeWeatherBand>()
    repeat(count) { index ->
        val start = timeline.totalSeconds * index / count
        val end = timeline.totalSeconds * (index + 1) / count
        val middle = (start + end) / 2.0
        val position = timeline.atSeconds(middle)
        val code = lookup.at(position, departure.plusSeconds(middle.toLong()), zone)?.outlook?.weatherCode
        val condition = phoneHikeCondition(code)
        val previous = bands.lastOrNull()
        if (previous != null && previous.condition == condition) {
            bands[bands.lastIndex] = previous.copy(endSeconds = end)
        } else {
            bands += PhoneHikeWeatherBand(start, end, condition)
        }
    }
    return bands
}
