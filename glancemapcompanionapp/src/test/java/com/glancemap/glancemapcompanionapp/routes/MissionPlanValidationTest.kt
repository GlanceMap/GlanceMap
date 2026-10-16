package com.glancemap.glancemapcompanionapp.routes

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class MissionPlanValidationTest {
    @Test
    fun invalidSavedDayExplainsTheFieldInsteadOfFailedRequirement() {
        val json = """{"days":[{"id":"day","dayNumber":0}]}"""
        val error = assertThrows(IllegalArgumentException::class.java) { decodeMissionPlanIndex(json, "Legacy") }
        assertEquals("A planned day has an invalid day number.", error.message)
    }

    @Test
    fun savedPlanStructureErrorsRemainDistinct() {
        val missing = assertThrows(IllegalArgumentException::class.java) { decodeMissionPlanIndex("{}", "Legacy") }
        assertEquals("The saved adventure plan is missing its day list.", missing.message)
        val unreadable =
            assertThrows(IllegalArgumentException::class.java) {
                decodeMissionPlanIndex("{\"days\":null}", "Legacy")
            }
        assertEquals("The saved adventure day list could not be read.", unreadable.message)
        val plan =
            MissionPlanRepository.MissionPlanIndex(
                days = listOf(MissionPlanDay("day", 1, adventureId = "missing")),
                adventures = listOf(MissionAdventure("trip", "Trip")),
            )
        val orphan =
            assertThrows(IllegalArgumentException::class.java) {
                decodeMissionPlanIndex(Gson().toJson(plan), "Legacy")
            }
        assertEquals("A saved day belongs to an adventure that is missing.", orphan.message)
    }

    @Test
    fun invalidAdventureDetailsExplainWhyTheyWereRejected() {
        val people = assertThrows(IllegalArgumentException::class.java) { MissionAdventure("trip", "Trip", people = 0) }
        assertEquals("An adventure must have between 1 and 99 hikers.", people.message)
        val date =
            assertThrows(IllegalArgumentException::class.java) {
                MissionAdventure("trip", "Trip", startDate = "2026-10-18", endDate = "2026-10-17")
            }
        assertEquals("The adventure end date is before its start date.", date.message)
    }

    @Test
    fun stageDialogShowsValidationReasonsAndKeepsStorageErrorsGeneric() {
        val fallback = "Could not load this GPX. Choose another route or try again."
        val reason = "This adventure no longer exists. Reopen Adventures and try again."
        assertEquals(reason, IllegalArgumentException(reason).missionStageErrorMessage(fallback))
        assertEquals(fallback, IllegalArgumentException().missionStageErrorMessage(fallback))
        assertEquals(fallback, IllegalArgumentException(" ").missionStageErrorMessage(fallback))
        assertEquals(fallback, IOException("Private storage path").missionStageErrorMessage(fallback))
    }

    @Test
    fun staleDayEditsExplainTheFailureAndPreserveTheSavedPlan() =
        runBlocking {
            val directory = createTempDirectory("adventure-validation").toFile()
            try {
                val repository = MissionPlanRepository(directory, "Legacy")
                repository.saveAdventure(null, MissionAdventureUpdate("Trip", "2026-10-18", "2026-10-19", 1, null))
                val file = File(directory, "mission-plan.json")
                val before = file.readText()
                val failure = runCatching { repository.assignRoute("removed-day", "route") }.exceptionOrNull()
                assertEquals("This day no longer exists. Reopen the adventure and try again.", failure?.message)
                assertEquals(before, file.readText())
            } finally {
                directory.deleteRecursively()
            }
        }
}
