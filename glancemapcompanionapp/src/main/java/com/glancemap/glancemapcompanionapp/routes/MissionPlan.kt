@file:Suppress("MaxLineLength")

package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.profile.TrailWindow
import com.glancemap.trailcore.profile.windowBetweenDistances

/** A lightweight reference to one day of a mission; the GPX remains in the route library. */
data class MissionPlanDay(
    val id: String,
    val dayNumber: Int,
    val routeId: String,
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

data class MissionPlanDayUi(
    val day: MissionPlanDay,
    val route: RouteLibraryRoute,
    val briefing: TrailWindow,
)

data class MissionPlanUiState(
    val days: List<MissionPlanDayUi> = emptyList(),
    val selectedDayId: String? = null,
    val isLoading: Boolean = true,
    val isPreparingTransfer: Boolean = false,
    val message: String? = null,
) {
    val selectedDay: MissionPlanDayUi?
        get() = days.firstOrNull { it.day.id == selectedDayId }
}

fun RouteLibraryRouteDetails.missionPlanBriefing(day: MissionPlanDay): TrailWindow {
    val startDistance = day.startDistanceMeters.coerceIn(0.0, profile.totalDistanceMeters)
    return profile.windowBetweenDistances(
        startDistanceMeters = startDistance,
        endDistanceMeters = day.endDistanceFor(profile.totalDistanceMeters),
    )
}
