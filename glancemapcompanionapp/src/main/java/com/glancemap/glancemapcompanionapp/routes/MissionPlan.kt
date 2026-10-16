@file:Suppress("MaxLineLength")

package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation
import com.glancemap.trailcore.profile.TrailWindow
import com.glancemap.trailcore.profile.windowBetweenDistances
import com.google.gson.annotations.SerializedName

/** A lightweight reference to one day of a mission; the GPX remains in the route library. */
data class MissionPlanDay(
    @field:SerializedName(value = "id", alternate = ["a"])
    val id: String,
    @field:SerializedName(value = "dayNumber", alternate = ["b"])
    val dayNumber: Int,
    @field:SerializedName(value = "routeId", alternate = ["c"])
    val routeId: String? = null,
    @field:SerializedName(value = "name", alternate = ["d"])
    val name: String? = null,
    /** ISO-8601 local date (`YYYY-MM-DD`), deliberately stored without a time zone. */
    @field:SerializedName(value = "plannedDate", alternate = ["e"])
    val plannedDate: String? = null,
    /** Local hike start time (`HH:mm`) at the GPX location; it is used to align hourly forecasts. */
    @field:SerializedName(value = "plannedStartTime", alternate = ["f"])
    val plannedStartTime: String? = null,
    @field:SerializedName(value = "overnight", alternate = ["g"])
    val overnight: String? = null,
    @field:SerializedName(value = "notes", alternate = ["h"])
    val notes: String? = null,
    @field:SerializedName(value = "startDistanceMeters", alternate = ["i"])
    val startDistanceMeters: Double = 0.0,
    @field:SerializedName(value = "endDistanceMeters", alternate = ["j"])
    val endDistanceMeters: Double? = null,
    @field:SerializedName(value = "adventureId", alternate = ["k"])
    val adventureId: String? = null,
    @field:SerializedName(value = "isRestDay", alternate = ["l"])
    val isRestDay: Boolean = false,
    @field:SerializedName(value = "breakfast", alternate = ["m"])
    val breakfast: MissionMeal? = null,
    @field:SerializedName(value = "lunch", alternate = ["n"])
    val lunch: MissionMeal? = null,
    @field:SerializedName(value = "dinner", alternate = ["o"])
    val dinner: MissionMeal? = null,
    @field:SerializedName(value = "overnightType", alternate = ["p"])
    val overnightType: MissionOvernight? = null,
    @field:SerializedName(value = "booking", alternate = ["q"])
    val booking: MissionBooking? = null,
    @field:SerializedName(value = "weatherLocation", alternate = ["r"])
    val weatherLocation: WeatherForecastLocation? = null,
    @field:SerializedName(value = "stageGroupId", alternate = ["s"])
    val stageGroupId: String? = null,
    @field:SerializedName(value = "stopReviewRequired", alternate = ["t"])
    val stopReviewRequired: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "A planned day is missing its ID." }
        require(dayNumber > 0) { "A planned day has an invalid day number." }
        require(routeId == null || routeId.isNotBlank()) { "A planned day has an empty GPX reference." }
        weatherLocation?.let { WeatherForecastLocation(it.latitude, it.longitude, it.elevationMeters, it.label) }
        require(
            startDistanceMeters.isFinite() && startDistanceMeters >= 0.0,
        ) { "A planned day has an invalid GPX start distance." }
        require(
            endDistanceMeters == null || (endDistanceMeters.isFinite() && endDistanceMeters > startDistanceMeters),
        ) { "A planned day must finish after its GPX start." }
    }

    fun endDistanceFor(totalDistanceMeters: Double): Double =
        (
            endDistanceMeters
                ?: totalDistanceMeters
        ).coerceIn(startDistanceMeters, totalDistanceMeters)

    fun isWholeRoute(totalDistanceMeters: Double): Boolean =
        startDistanceMeters <= 0.0 &&
            endDistanceFor(totalDistanceMeters) >= totalDistanceMeters

    val meals: List<MissionMeal>
        get() = listOf(breakfast, lunch, dinner).map { it ?: MissionMeal.UNPLANNED }

    val accommodation: MissionOvernight
        get() = overnightType ?: if (overnight.isNullOrBlank()) MissionOvernight.UNPLANNED else MissionOvernight.OTHER

    fun weatherKey(): String =
        listOf(
            routeId,
            startDistanceMeters,
            endDistanceMeters,
            isRestDay,
            weatherLocation,
        ).joinToString("|")
}

/** Editable mission information. GPX content and route measurements stay in the route library. */
data class MissionPlanDayUpdate(
    val name: String?,
    val plannedDate: String?,
    val plannedStartTime: String?,
    val overnight: String?,
    val notes: String?,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double?,
    val isRestDay: Boolean = false,
    val breakfast: MissionMeal? = null,
    val lunch: MissionMeal? = null,
    val dinner: MissionMeal? = null,
    val overnightType: MissionOvernight? = null,
    val booking: MissionBooking? = null,
    val weatherLocation: WeatherForecastLocation? = null,
    val reviewedStop: Boolean = false,
)

data class MissionPlanDayUi(
    val day: MissionPlanDay,
    val route: RouteLibraryRoute,
    val briefing: TrailWindow,
    val timeline: MissionDayTimeline,
    val profile: MissionDayPlanProfile,
)

data class MissionPlanUiState(
    val days: List<MissionPlanDayUi> = emptyList(),
    val selectedDayId: String? = null,
    val weatherByDayId: Map<String, MissionDayWeatherUiState> = emptyMap(),
    val unavailableDayCount: Int = 0,
    val isLoading: Boolean = true,
    val isPreparingTransfer: Boolean = false,
    val message: String? = null,
    val adventures: List<MissionAdventure> = emptyList(),
    val selectedAdventureId: String? = null,
    val planDays: List<MissionPlanDay> = emptyList(),
    val restLocations: Map<String, WeatherForecastLocation> = emptyMap(),
) {
    val selectedAdventure: MissionAdventure?
        get() = adventures.firstOrNull { it.id == selectedAdventureId }

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

internal fun MissionPlanDay.toUpdate(): MissionPlanDayUpdate =
    MissionPlanDayUpdate(
        name,
        plannedDate,
        plannedStartTime,
        overnight,
        notes,
        startDistanceMeters,
        endDistanceMeters,
        isRestDay,
        breakfast,
        lunch,
        dinner,
        overnightType,
        booking,
        weatherLocation,
    )

internal fun MissionPlanDay.withUpdate(update: MissionPlanDayUpdate): MissionPlanDay =
    copy(
        name = update.name?.trim()?.takeIf(String::isNotEmpty),
        plannedDate = update.plannedDate?.trim()?.takeIf(String::isNotEmpty),
        plannedStartTime = update.plannedStartTime?.trim()?.takeIf(String::isNotEmpty),
        overnight = update.overnight?.trim()?.takeIf(String::isNotEmpty),
        notes = update.notes?.trim()?.takeIf(String::isNotEmpty),
        startDistanceMeters = update.startDistanceMeters,
        endDistanceMeters = update.endDistanceMeters,
        isRestDay = update.isRestDay,
        breakfast = update.breakfast,
        lunch = update.lunch,
        dinner = update.dinner,
        overnightType = update.overnightType,
        booking = update.booking,
        weatherLocation = update.weatherLocation,
        stopReviewRequired = stopReviewRequired && !update.reviewedStop,
    )

internal fun MissionPlanUiState.weatherPlanDay(dayId: String): MissionPlanDay? =
    planDays.firstOrNull { it.id == dayId }?.let { day ->
        if (day.isRestDay) day.copy(weatherLocation = restLocations[dayId]) else day
    }

internal fun MissionPlanUiState.weatherFor(day: MissionPlanDay): MissionDayWeatherUiState? =
    weatherByDayId[day.id]?.takeIf { weather ->
        val planned = weatherPlanDay(day.id) ?: day
        weather.plannedDate == planned.plannedDate &&
            weather.plannedStartTime == planned.plannedStartTime &&
            weather.planKey == planned.weatherKey()
    }

/** The GPX is shared; only the day boundaries are saved with the adventure. */
data class MissionRouteStageSource(
    val route: RouteLibraryRouteDetails,
    val days: List<MissionPlanDay>,
)

/** Reordering a day off keeps links; only a disrupted hiking sequence is unlinked. */
internal fun List<MissionPlanDay>.unlinkReorderedStageGroups(): List<MissionPlanDay> {
    val hikes = filterNot { it.isRestDay }.sortedBy { it.dayNumber }
    val broken =
        filter { it.stageGroupId != null }
            .groupBy { it.stageGroupId }
            .filterValues { group ->
                val ordered = group.sortedBy { it.dayNumber }
                val positions = ordered.map { stage -> hikes.indexOfFirst { it.id == stage.id } }
                val adjacent = positions.zipWithNext().all { (a, b) -> b == a + 1 }
                val connected = ordered.zipWithNext().all { (a, b) -> a.endDistanceMeters == b.startDistanceMeters }
                !adjacent || !connected
            }.keys
            .filterNotNull()
            .toSet()
    return unlinkStageGroups(broken)
}
