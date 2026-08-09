package com.glancemap.glancemapcompanionapp.routes

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Persists a single local mission plan without duplicating any GPX content. */
internal class MissionPlanRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val mutex = Mutex()
    private val directory = File(appContext.filesDir, DIRECTORY_NAME)
    private val indexFile = File(directory, INDEX_FILE_NAME)

    suspend fun load(): MissionPlanIndex = mutex.withLock { readIndex() }

    suspend fun addDay(routeId: String): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val nextDay =
                MissionPlanDay(
                    id = UUID.randomUUID().toString(),
                    dayNumber = current.days.maxOfOrNull(MissionPlanDay::dayNumber)?.plus(1) ?: 1,
                    routeId = routeId,
                )
            writeIndex(current.copy(days = current.days + nextDay, selectedDayId = nextDay.id))
        }

    suspend fun selectDay(dayId: String): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val selectedDayId = dayId.takeIf { selected -> current.days.any { it.id == selected } }
            writeIndex(current.copy(selectedDayId = selectedDayId))
        }

    suspend fun updateSegment(
        dayId: String,
        startDistanceMeters: Double,
        endDistanceMeters: Double?,
    ): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val updatedDays =
                current.days.map { day ->
                    if (day.id == dayId) {
                        day.copy(
                            startDistanceMeters = startDistanceMeters,
                            endDistanceMeters = endDistanceMeters,
                        )
                    } else {
                        day
                    }
                }
            writeIndex(current.copy(days = updatedDays))
        }

    suspend fun updateDay(
        dayId: String,
        update: MissionPlanDayUpdate,
    ): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val updatedDays =
                current.days.map { day ->
                    if (day.id == dayId) {
                        day.copy(
                            name = update.name.normalizedMissionPlanText(),
                            plannedDate = update.plannedDate.normalizedMissionPlanText(),
                            plannedStartTime = update.plannedStartTime.normalizedMissionPlanText(),
                            overnight = update.overnight.normalizedMissionPlanText(),
                            notes = update.notes.normalizedMissionPlanText(),
                            startDistanceMeters = update.startDistanceMeters,
                            endDistanceMeters = update.endDistanceMeters,
                        )
                    } else {
                        day
                    }
                }
            writeIndex(current.copy(days = updatedDays))
        }

    suspend fun moveDay(
        dayId: String,
        targetIndex: Int,
    ): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            writeIndex(current.copy(days = current.days.moveMissionPlanDay(dayId, targetIndex)))
        }

    suspend fun removeDay(dayId: String): MissionPlanIndex =
        mutex.withLock {
            val current = readIndex()
            val remainingDays = current.days.filterNot { day -> day.id == dayId }
            val renumberedDays = remainingDays.mapIndexed { index, day -> day.copy(dayNumber = index + 1) }
            val selectedDayId =
                current.selectedDayId
                    ?.takeIf { selected -> renumberedDays.any { it.id == selected } }
                    ?: renumberedDays.firstOrNull()?.id
            writeIndex(current.copy(days = renumberedDays, selectedDayId = selectedDayId))
        }

    private fun readIndex(): MissionPlanIndex {
        if (!indexFile.isFile) return MissionPlanIndex()
        return runCatching {
            indexFile.reader().use { reader ->
                gson.fromJson(reader, MissionPlanIndex::class.java) ?: MissionPlanIndex()
            }
        }.getOrDefault(MissionPlanIndex())
    }

    private fun writeIndex(index: MissionPlanIndex): MissionPlanIndex {
        directory.mkdirs()
        val temporaryFile = File(directory, "$INDEX_FILE_NAME.tmp")
        temporaryFile.writer().use { writer -> gson.toJson(index, writer) }
        if (!temporaryFile.renameTo(indexFile)) {
            temporaryFile.copyTo(indexFile, overwrite = true)
            temporaryFile.delete()
        }
        return index
    }

    private fun String?.normalizedMissionPlanText(): String? =
        this
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    internal data class MissionPlanIndex(
        val days: List<MissionPlanDay> = emptyList(),
        val selectedDayId: String? = null,
    )

    private companion object {
        const val DIRECTORY_NAME = "mission-plan"
        const val INDEX_FILE_NAME = "mission-plan.json"
    }
}
