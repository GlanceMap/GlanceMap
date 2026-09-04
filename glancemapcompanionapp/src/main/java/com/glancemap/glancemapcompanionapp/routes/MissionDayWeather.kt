@file:Suppress("MaxLineLength", "ReturnCount")

package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.glancemapcompanionapp.weather.WeatherDailyOutlook
import com.glancemap.glancemapcompanionapp.weather.WeatherForecast
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation
import com.glancemap.glancemapcompanionapp.weather.WeatherHourlyOutlook
import com.glancemap.shared.transfer.ActiveHikeSnapshot
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToLong

/** Three explicit GPX samples make weather context useful for longer planned days. */
enum class MissionDayWeatherSamplePosition(
    val label: String,
) {
    START("Start"),
    MIDPOINT("Midpoint"),
    FINISH("Finish"),
}

data class MissionDayWeatherSampleTarget(
    val position: MissionDayWeatherSamplePosition,
    val distanceFromDayStartMeters: Double,
    val plannedOffsetSeconds: Long,
    val location: WeatherForecastLocation,
)

data class MissionDayWeatherSampleUi(
    val target: MissionDayWeatherSampleTarget,
    val forecast: WeatherForecast? = null,
    val dailyOutlook: WeatherDailyOutlook? = null,
    val scheduledOutlook: WeatherHourlyOutlook? = null,
    val scheduledTimeIso8601: String? = null,
    val isCached: Boolean = false,
    val isStale: Boolean = false,
    val savedSnapshotCount: Int = 0,
    val message: String? = null,
)

data class MissionDayWeatherUiState(
    val plannedDate: String? = null,
    val plannedStartTime: String? = null,
    val isLoading: Boolean = false,
    val samples: List<MissionDayWeatherSampleUi> = emptyList(),
    val message: String? = null,
)

/** Prepared Mission Plan weather that can be safely shown for the matching watch route. */
data class LiveHikeWeatherContext(
    val dayName: String,
    val plannedDate: String?,
    val plannedStartTime: String?,
    val samples: List<MissionDayWeatherSampleUi>,
    val isLoading: Boolean,
    val message: String?,
)

fun MissionPlanDayUi.matchesActiveHike(snapshot: ActiveHikeSnapshot): Boolean {
    val activeFileName = snapshot.routeId?.substringAfterLast('/')?.substringAfterLast('\\')
    val isMissionDayExport = activeFileName == "mission-day-${day.id}.gpx"
    val isOriginalRoute = activeFileName == route.storedFileName
    val expectedDayTitle = "${route.displayName} — Day ${day.dayNumber}"
    val isMatchingTitle =
        snapshot.routeTitle?.trim()?.let { title ->
            title.equals(route.displayName.trim(), ignoreCase = true) ||
                title.equals(route.metadataTitle?.trim(), ignoreCase = true) ||
                title.equals(expectedDayTitle, ignoreCase = true)
        } == true
    return isMissionDayExport || isOriginalRoute || isMatchingTitle
}

fun MissionPlanDayUi.liveHikeWeatherContext(weather: MissionDayWeatherUiState?): LiveHikeWeatherContext =
    LiveHikeWeatherContext(
        dayName = day.name ?: route.displayName,
        plannedDate = day.plannedDate,
        plannedStartTime = day.plannedStartTime,
        samples = weather?.samples.orEmpty(),
        isLoading = weather?.isLoading == true,
        message = weather?.message,
    )

/**
 * Samples the exact bounds of one planned GPX day plus its distance midpoint. The daily forecast
 * is selected separately by the view model using the user's planned date. A planned start time,
 * when set, aligns each sample with its expected point in the hiking day.
 */
fun RouteLibraryRouteDetails.missionDayWeatherTargets(
    day: MissionPlanDay,
): List<MissionDayWeatherSampleTarget> {
    val window = missionPlanBriefing(day)
    if (window.distanceMeters <= 0.0) return emptyList()

    return listOf(
        MissionDayWeatherSamplePosition.START to window.startDistanceMeters,
        MissionDayWeatherSamplePosition.MIDPOINT to (window.startDistanceMeters + window.endDistanceMeters) / 2.0,
        MissionDayWeatherSamplePosition.FINISH to window.endDistanceMeters,
    ).mapNotNull { (position, distanceFromStartMeters) ->
        val progress =
            ((distanceFromStartMeters - window.startDistanceMeters) / window.distanceMeters)
                .coerceIn(0.0, 1.0)
        weatherLocationAt(
            distanceFromStartMeters = distanceFromStartMeters,
            label = "Day ${day.dayNumber} ${position.label.lowercase()}",
        )?.let { location ->
            MissionDayWeatherSampleTarget(
                position = position,
                distanceFromDayStartMeters = distanceFromStartMeters - window.startDistanceMeters,
                plannedOffsetSeconds = (window.estimatedDurationSeconds * progress).roundToLong(),
                location = location,
            )
        }
    }
}

fun MissionDayWeatherSampleTarget.plannedDateTime(day: MissionPlanDay): LocalDateTime? = day.plannedStartDateTime()?.plusSeconds(plannedOffsetSeconds)

fun WeatherForecast.hourlyOutlookNear(plannedDateTime: LocalDateTime): WeatherHourlyOutlook? =
    hourly
        .mapNotNull { outlook ->
            outlook.timeIso8601
                ?.let { time -> runCatching { LocalDateTime.parse(time) }.getOrNull() }
                ?.let { time -> outlook to abs(Duration.between(time, plannedDateTime).seconds) }
        }.minByOrNull { (_, differenceSeconds) -> differenceSeconds }
        ?.takeIf { (_, differenceSeconds) -> differenceSeconds <= MAX_HOURLY_MATCH_DIFFERENCE_SECONDS }
        ?.first

private fun MissionPlanDay.plannedStartDateTime(): LocalDateTime? {
    val date = plannedDate?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() } ?: return null
    val time = plannedStartTime?.let { value -> runCatching { LocalTime.parse(value) }.getOrNull() } ?: return null
    return LocalDateTime.of(date, time)
}

private const val MAX_HOURLY_MATCH_DIFFERENCE_SECONDS = 90L * 60L
