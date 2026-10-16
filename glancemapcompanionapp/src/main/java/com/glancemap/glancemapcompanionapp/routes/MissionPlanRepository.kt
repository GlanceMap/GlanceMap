package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Keeps adventure metadata local; GPX files remain in the shared route library. */
@Suppress("TooManyFunctions") // Each operation is one atomic edit of the same local adventure index.
internal class MissionPlanRepository private constructor(
    private val directory: () -> File,
    private val legacyName: String,
) {
    constructor(context: Context) : this(
        directory = { PhoneOfflineStorage(context.applicationContext).missionPlanDirectory() },
        legacyName = context.getString(R.string.adventure_existing_plan),
    )

    internal constructor(directory: File, legacyName: String) : this({ directory }, legacyName)

    private val gson = Gson()
    private val indexFile: File
        get() = File(directory(), INDEX_FILE_NAME)

    suspend fun load(): MissionPlanIndex = mutex.withLock { readIndex() }

    suspend fun saveAdventure(
        id: String?,
        update: MissionAdventureUpdate,
    ): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val adventureId = id ?: UUID.randomUUID().toString()
            require(
                id == null || current.adventures.any { it.id == id },
            ) { "This adventure no longer exists. Reopen Adventures and try again." }
            val adventure =
                MissionAdventure(
                    adventureId,
                    update.name.trim(),
                    update.startDate,
                    update.endDate,
                    update.people,
                    update.notes.normalizedMissionPlanText(),
                )
            val days = current.days.filter { it.adventureId == adventureId }.scheduleAdventure(adventureId, update)
            writeIndex(
                current.copy(
                    adventures = current.adventures.filterNot { it.id == adventureId } + adventure,
                    days = current.days.filterNot { it.adventureId == adventureId } + days,
                    selectedAdventureId = adventureId,
                    selectedDayId = current.selectedDayId?.takeIf { selected -> days.any { it.id == selected } },
                ),
            )
        }

    suspend fun selectAdventure(id: String): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            require(
                current.adventures.any { it.id == id },
            ) { "This adventure no longer exists. Reopen Adventures and try again." }
            writeIndex(current.copy(selectedAdventureId = id, selectedDayId = null))
        }

    suspend fun addDay(
        routeId: String? = null,
        isRestDay: Boolean = false,
    ): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val adventure =
                requireNotNull(
                    current.selectedAdventure,
                ) { "Choose an adventure before changing its days." }
            val days = current.days.filter { it.adventureId == adventure.id }
            val date = adventure.startDate?.let { LocalDate.parse(it).plusDays(days.size.toLong()).toString() }
            val next =
                MissionPlanDay(
                    UUID.randomUUID().toString(),
                    days.size + 1,
                    routeId,
                    adventureId = adventure.id,
                    plannedDate = date,
                    isRestDay = isRestDay,
                )
            writeIndex(
                current.copy(
                    days = current.days + next,
                    adventures =
                        current.adventures.map {
                            if (
                                it.id == adventure.id
                            ) {
                                it.copy(endDate = date ?: it.endDate)
                            } else {
                                it
                            }
                        },
                ),
            )
        }

    suspend fun assignRoute(
        dayId: String,
        routeId: String,
    ): MissionPlanIndex =
        changeDay(dayId) { day ->
            if (
                day.routeId == routeId
            ) {
                day
            } else {
                day.copy(routeId = routeId, startDistanceMeters = 0.0, endDistanceMeters = null)
            }
        }

    suspend fun planStages(
        adventureId: String,
        routeId: String,
        dayIds: List<String>,
        stops: List<Double>,
        totalDistance: Double,
    ): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            require(
                current.adventures.any { it.id == adventureId },
            ) { "This adventure no longer exists. Reopen Adventures and try again." }
            val days =
                current.days
                    .withLinkedStages(adventureId, routeId, dayIds, stops, totalDistance)
                    .reviewMovedRestStays(current.days)
            days.validateStageLinks()
            writeIndex(current.copy(days = days, selectedAdventureId = adventureId))
        }

    suspend fun selectDay(dayId: String): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val day = current.days.firstOrNull { it.id == dayId && !it.isRestDay && it.routeId != null }
            writeIndex(
                current.copy(
                    selectedDayId = day?.id,
                    selectedAdventureId = day?.adventureId ?: current.selectedAdventureId,
                ),
            )
        }

    suspend fun updateSegment(
        dayId: String,
        startDistanceMeters: Double,
        endDistanceMeters: Double?,
    ): MissionPlanIndex =
        changeDay(dayId) {
            it.copy(
                startDistanceMeters = startDistanceMeters,
                endDistanceMeters = endDistanceMeters,
            )
        }

    suspend fun updateDay(
        dayId: String,
        update: MissionPlanDayUpdate,
    ): MissionPlanIndex = editDay(dayId) { update }

    suspend fun editDay(
        dayId: String,
        edit: (MissionPlanDay) -> MissionPlanDayUpdate,
    ): MissionPlanIndex = changeDay(dayId) { day -> day.withUpdate(edit(day)) }

    suspend fun moveDay(
        dayId: String,
        targetIndex: Int,
    ): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val adventure =
                requireNotNull(
                    current.selectedAdventure,
                ) { "Choose an adventure before changing its days." }
            val adventureDays = current.days.filter { it.adventureId == adventure.id }
            val days = adventureDays.moveMissionPlanDay(dayId, targetIndex).unlinkReorderedStageGroups()
            writeIndex(current.withAdventureDays(adventure, days))
        }

    suspend fun removeDay(dayId: String): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val adventure =
                requireNotNull(
                    current.selectedAdventure,
                ) { "Choose an adventure before changing its days." }
            val group = current.days.firstOrNull { it.id == dayId }?.stageGroupId
            val days =
                current.days
                    .unlinkStageGroups(setOfNotNull(group))
                    .filter { it.adventureId == adventure.id && it.id != dayId }
            require(days.isNotEmpty()) { "Keep at least one day in this adventure." }
            writeIndex(current.withAdventureDays(adventure, days))
        }

    private suspend fun changeDay(
        dayId: String,
        change: (MissionPlanDay) -> MissionPlanDay,
    ): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            require(
                current.days.any { it.id == dayId },
            ) { "This day no longer exists. Reopen the adventure and try again." }
            val original = current.days.first { it.id == dayId }
            val days = current.days.changeLinkedDay(change(original)).reviewMovedRestStays(current.days)
            days.validateStageLinks()
            writeIndex(current.copy(days = days))
        }

    private fun MissionPlanIndex.withAdventureDays(
        adventure: MissionAdventure,
        updated: List<MissionPlanDay>,
    ): MissionPlanIndex {
        val start = adventure.startDate?.let(LocalDate::parse)
        val scheduled =
            updated.mapIndexed { index, day ->
                day.copy(
                    dayNumber = index + 1,
                    plannedDate = start?.plusDays(index.toLong())?.toString() ?: day.plannedDate,
                )
            }
        return copy(
            days = days.filterNot { it.adventureId == adventure.id } + scheduled,
            adventures =
                adventures.map {
                    if (it.id == adventure.id &&
                        start != null
                    ) {
                        it.copy(endDate = start.plusDays((scheduled.size - 1).toLong()).toString())
                    } else {
                        it
                    }
                },
            selectedDayId = selectedDayId?.takeIf { selected -> scheduled.any { it.id == selected && !it.isRestDay } },
        )
    }

    private fun readIndex(): MissionPlanIndex =
        if (indexFile.isFile) {
            decodeMissionPlanIndex(
                indexFile.readText(),
                legacyName,
            )
        } else {
            MissionPlanIndex()
        }

    private fun writeIndex(index: MissionPlanIndex): MissionPlanIndex {
        directory().mkdirs()
        val temporaryFile = File(directory(), "$INDEX_FILE_NAME.tmp")
        temporaryFile.writeText(gson.toJson(index))
        check(temporaryFile.renameTo(indexFile)) { "Could not save the adventure plan." }
        return index
    }

    internal data class MissionPlanIndex(
        @field:SerializedName(value = "days", alternate = ["a"])
        val days: List<MissionPlanDay> = emptyList(),
        @field:SerializedName(value = "selectedDayId", alternate = ["b"])
        val selectedDayId: String? = null,
        @field:SerializedName(value = "adventures", alternate = ["c"])
        val adventures: List<MissionAdventure> = emptyList(),
        @field:SerializedName(value = "selectedAdventureId", alternate = ["d"])
        val selectedAdventureId: String? = null,
    ) {
        val selectedAdventure: MissionAdventure?
            get() = adventures.firstOrNull { it.id == selectedAdventureId }
    }

    private companion object {
        const val INDEX_FILE_NAME = "mission-plan.json"
        val mutex = Mutex()
    }
}

private fun String?.normalizedMissionPlanText(): String? = this?.trim()?.takeIf(String::isNotEmpty)

/** A failed decode must surface to the caller, never become an empty plan that can overwrite data. */
internal fun decodeMissionPlanIndex(
    json: String,
    legacyName: String,
): MissionPlanRepository.MissionPlanIndex {
    val root = JsonParser.parseString(json).asJsonObject
    require(root.has("days") || root.has("a")) { "The saved adventure plan is missing its day list." }
    val saved = Gson().fromJson(root, SavedMissionPlanIndex::class.java)
    val days = requireNotNull(saved.days) { "The saved adventure day list could not be read." }.map { it.copy() }
    val adventures = saved.adventures.orEmpty().map { it.copy() }
    days.validateStageLinks()
    require(
        days.map { it.id }.distinct().size == days.size,
    ) { "The saved adventure plan contains duplicate day IDs." }
    require(
        adventures.map { it.id }.distinct().size == adventures.size,
    ) { "The saved adventure plan contains duplicate adventure IDs." }
    if (adventures.isEmpty() && days.isNotEmpty()) {
        val id = "legacy-mission"
        return MissionPlanRepository.MissionPlanIndex(
            days = days.map { it.copy(adventureId = id) },
            selectedDayId = saved.selectedDayId,
            adventures = listOf(MissionAdventure(id, legacyName)),
            selectedAdventureId = id,
        )
    }
    require(
        days.all { day -> adventures.any { it.id == day.adventureId } },
    ) { "A saved day belongs to an adventure that is missing." }
    return MissionPlanRepository.MissionPlanIndex(
        days = days,
        selectedDayId = saved.selectedDayId,
        adventures = adventures,
        selectedAdventureId =
            saved.selectedAdventureId?.takeIf { id -> adventures.any { it.id == id } }
                ?: adventures.firstOrNull()?.id,
    )
}

private data class SavedMissionPlanIndex(
    @field:SerializedName(value = "days", alternate = ["a"])
    val days: List<MissionPlanDay>? = null,
    @field:SerializedName(value = "selectedDayId", alternate = ["b"])
    val selectedDayId: String? = null,
    @field:SerializedName(value = "adventures", alternate = ["c"])
    val adventures: List<MissionAdventure>? = null,
    @field:SerializedName(value = "selectedAdventureId", alternate = ["d"])
    val selectedAdventureId: String? = null,
)
