package com.glancemap.glancemapcompanionapp.routes

import com.google.gson.annotations.SerializedName
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class MissionMeal { UNPLANNED, PACK, PROVIDED, NOT_NEEDED }

enum class MissionOvernight { UNPLANNED, BIVOUAC, REFUGE, CAMPSITE, OTHER, NONE }

enum class MissionBooking { NOT_REQUIRED, TO_BOOK, BOOKED }

data class MissionAdventure(
    @field:SerializedName(value = "id", alternate = ["a"])
    val id: String,
    @field:SerializedName(value = "name", alternate = ["b"])
    val name: String,
    @field:SerializedName(value = "startDate", alternate = ["c"])
    val startDate: String? = null,
    @field:SerializedName(value = "endDate", alternate = ["d"])
    val endDate: String? = null,
    @field:SerializedName(value = "people", alternate = ["e"])
    val people: Int = 1,
    @field:SerializedName(value = "notes", alternate = ["f"])
    val notes: String? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "An adventure is missing its ID or name." }
        require(people in 1..99) { "An adventure must have between 1 and 99 hikers." }
        require(
            startDate == null || runCatching { LocalDate.parse(startDate) }.isSuccess,
        ) { "The adventure start date is invalid." }
        require(
            endDate == null || runCatching { LocalDate.parse(endDate) }.isSuccess,
        ) { "The adventure end date is invalid." }
        require(
            startDate == null || endDate == null || endDate >= startDate,
        ) { "The adventure end date is before its start date." }
    }
}

data class MissionAdventureUpdate(
    val name: String,
    val startDate: String,
    val endDate: String,
    val people: Int,
    val notes: String?,
) {
    val dayCount: Int
        get() = (ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)) + 1).toInt()

    init {
        require(name.isNotBlank()) { "Give this adventure a name." }
        require(people in 1..99) { "An adventure must have between 1 and 99 hikers." }
        require(
            ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)) in 0..365,
        ) { "Choose an adventure lasting between 1 and 366 days." }
    }
}

/** Assigns calendar slots without discarding a day's route, meals, accommodation, or notes. */
internal fun List<MissionPlanDay>.scheduleAdventure(
    adventureId: String,
    update: MissionAdventureUpdate,
): List<MissionPlanDay> {
    require(update.dayCount >= size) { "These dates would remove planned days. Remove extra days first." }
    val start = LocalDate.parse(update.startDate)
    return List(update.dayCount) { index ->
        (getOrNull(index) ?: MissionPlanDay(id = UUID.randomUUID().toString(), dayNumber = index + 1))
            .copy(
                adventureId = adventureId,
                dayNumber = index + 1,
                plannedDate = start.plusDays(index.toLong()).toString(),
            )
    }
}

data class MissionAdventureTotals(
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val walkingSeconds: Double,
    val hikingDays: Int,
    val restDays: Int,
    val nights: Int,
    val breakfasts: Int,
    val lunches: Int,
    val dinners: Int,
    val providedMeals: Int,
    val unplannedItems: Int,
    val unbookedNights: Int,
    val missingRoutes: Int,
) {
    val packedMeals: Int
        get() = breakfasts + lunches + dinners
}

internal fun MissionPlanUiState.adventureTotals(): MissionAdventureTotals {
    val people = selectedAdventure?.people ?: 1
    val hikes = days.filterNot { it.day.isRestDay }
    val plannedHikes = planDays.filterNot(MissionPlanDay::isRestDay)
    val portions = (0..2).map { meal -> planDays.count { it.meals[meal] == MissionMeal.PACK } * people }
    return MissionAdventureTotals(
        distanceMeters = hikes.sumOf { it.briefing.distanceMeters },
        ascentMeters = hikes.sumOf { it.briefing.ascentMeters },
        descentMeters = hikes.sumOf { it.briefing.descentMeters },
        walkingSeconds = hikes.sumOf { it.briefing.estimatedDurationSeconds },
        hikingDays = plannedHikes.size,
        restDays = planDays.count(MissionPlanDay::isRestDay),
        nights = planDays.count { it.accommodation !in listOf(MissionOvernight.UNPLANNED, MissionOvernight.NONE) },
        breakfasts = portions[0],
        lunches = portions[1],
        dinners = portions[2],
        providedMeals = planDays.sumOf { it.meals.count { meal -> meal == MissionMeal.PROVIDED } } * people,
        unplannedItems =
            planDays.sumOf { day ->
                day.meals.count { it == MissionMeal.UNPLANNED } +
                    if (
                        day.accommodation == MissionOvernight.UNPLANNED
                    ) {
                        1
                    } else {
                        0
                    }
            },
        unbookedNights =
            planDays.count {
                it.booking == MissionBooking.TO_BOOK &&
                    it.accommodation in
                    listOf(
                        MissionOvernight.REFUGE,
                        MissionOvernight.CAMPSITE,
                        MissionOvernight.OTHER,
                    )
            },
        missingRoutes = plannedHikes.size - hikes.size,
    )
}
