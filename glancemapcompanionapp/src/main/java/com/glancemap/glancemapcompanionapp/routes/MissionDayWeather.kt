package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.glancemapcompanionapp.weather.WeatherDailyOutlook
import com.glancemap.glancemapcompanionapp.weather.WeatherForecast
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation

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
    val location: WeatherForecastLocation,
)

data class MissionDayWeatherSampleUi(
    val target: MissionDayWeatherSampleTarget,
    val forecast: WeatherForecast? = null,
    val dailyOutlook: WeatherDailyOutlook? = null,
    val isCached: Boolean = false,
    val isStale: Boolean = false,
    val savedSnapshotCount: Int = 0,
    val message: String? = null,
)

data class MissionDayWeatherUiState(
    val plannedDate: String? = null,
    val isLoading: Boolean = false,
    val samples: List<MissionDayWeatherSampleUi> = emptyList(),
    val message: String? = null,
)

/**
 * Samples the exact bounds of one planned GPX day plus its distance midpoint. The daily forecast
 * is selected separately by the view model using the user's planned date.
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
        weatherLocationAt(
            distanceFromStartMeters = distanceFromStartMeters,
            label = "Day ${day.dayNumber} ${position.label.lowercase()}",
        )?.let { location ->
            MissionDayWeatherSampleTarget(
                position = position,
                distanceFromDayStartMeters = distanceFromStartMeters - window.startDistanceMeters,
                location = location,
            )
        }
    }
}
