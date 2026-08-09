@file:Suppress("MaxLineLength")

package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.profile.TrailWindow
import com.glancemap.trailcore.profile.windowBetweenDistances

/** A lightweight reference to one day of a mission; the GPX remains in the route library. */
data class MissionPlanDay(
    val id: String,
    val dayNumber: Int,
    val routeId: String,
    val name: String? = null,
    /** ISO-8601 local date (`YYYY-MM-DD`), deliberately stored without a time zone. */
    val plannedDate: String? = null,
    val overnight: String? = null,
    val notes: String? = null,
    val startDistanceMeters: Double = 0.0,
    val endDistanceMeters: Double? = null,
) {
    init {
        require(id.isNotBlank())
        require(dayNumber > 0)
        require(routeId.isNotBlank())
        require(startDistanceMeters.isFinite() && startDistanceMeters >= 0.0)
        require(endDistanceMeters == null || (endDistanceMeters.isFinite() && endDistanceMeters > startDistanceMeters))
    }

    fun endDistanceFor(totalDistanceMeters: Double): Double = (endDistanceMeters ?: totalDistanceMeters).coerceIn(startDistanceMeters, totalDistanceMeters)

    fun isWholeRoute(totalDistanceMeters: Double): Boolean = startDistanceMeters <= 0.0 && endDistanceFor(totalDistanceMeters) >= totalDistanceMeters
}

/** Editable mission information. GPX content and route measurements stay in the route library. */
data class MissionPlanDayUpdate(
    val name: String?,
    val plannedDate: String?,
    val overnight: String?,
    val notes: String?,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double?,
)

data class MissionPlanDayUi(
    val day: MissionPlanDay,
    val route: RouteLibraryRoute,
    val briefing: TrailWindow,
    val timeline: MissionDayTimeline,
)

data class MissionPlanUiState(
    val days: List<MissionPlanDayUi> = emptyList(),
    val selectedDayId: String? = null,
    val weatherByDayId: Map<String, MissionDayWeatherUiState> = emptyMap(),
    val unavailableDayCount: Int = 0,
    val isLoading: Boolean = true,
    val isPreparingTransfer: Boolean = false,
    val message: String? = null,
) {
    val selectedDay: MissionPlanDayUi?
        get() = days.firstOrNull { it.day.id == selectedDayId }
}

internal fun List<MissionPlanDay>.moveMissionPlanDay(
    dayId: String,
    targetIndex: Int,
): List<MissionPlanDay> =
    indexOfFirst { day -> day.id == dayId }.let { currentIndex ->
        if (currentIndex == -1) {
            this
        } else {
            val destination = targetIndex.coerceIn(0, lastIndex)
            if (destination == currentIndex) {
                this
            } else {
                val reordered = toMutableList()
                val moved = reordered.removeAt(currentIndex)
                reordered.add(destination, moved)
                reordered.mapIndexed { index, day -> day.copy(dayNumber = index + 1) }
            }
        }
    }

fun RouteLibraryRouteDetails.missionPlanBriefing(day: MissionPlanDay): TrailWindow {
    val startDistance = day.startDistanceMeters.coerceIn(0.0, profile.totalDistanceMeters)
    return profile.windowBetweenDistances(
        startDistanceMeters = startDistance,
        endDistanceMeters = day.endDistanceFor(profile.totalDistanceMeters),
    )
}
