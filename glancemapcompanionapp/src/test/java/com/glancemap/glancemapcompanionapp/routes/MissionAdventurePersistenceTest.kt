package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageFile
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageLocation
import com.glancemap.glancemapcompanionapp.map.reconcilePhoneOfflineMutableData
import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class MissionAdventurePersistenceTest {
    @Test
    fun `previous release field names restore the entire adventure`() {
        val restored = decodeMissionPlanIndex(previousReleasePlan, "Legacy")
        assertEquals("trip", restored.selectedAdventureId)
        assertEquals("day", restored.selectedDayId)
        assertEquals(
            MissionAdventure("trip", "Alpine trip", "2026-10-18", "2026-10-19", 2, "Trip notes"),
            restored.selectedAdventure,
        )
        assertEquals(
            MissionPlanDay(
                id = "day",
                dayNumber = 1,
                routeId = "route",
                name = "Ridge",
                plannedDate = "2026-10-18",
                plannedStartTime = "08:00",
                overnight = "Refuge",
                notes = "Day notes",
                startDistanceMeters = 0.0,
                endDistanceMeters = 1000.0,
                adventureId = "trip",
                breakfast = MissionMeal.PACK,
                lunch = MissionMeal.PROVIDED,
                dinner = MissionMeal.NOT_NEEDED,
                overnightType = MissionOvernight.REFUGE,
                booking = MissionBooking.BOOKED,
                weatherLocation = WeatherForecastLocation(46.0, 11.0, 1500.0, "Refuge"),
                stageGroupId = "stages",
                stopReviewRequired = true,
            ),
            restored.days.first(),
        )
        assertEquals(1000.0, restored.days.last().startDistanceMeters, 0.0)
        assertEquals(MissionMeal.UNPLANNED, restored.days.last().breakfast)
        assertEquals(MissionOvernight.BIVOUAC, restored.days.last().overnightType)
        assertEquals(restored, decodeMissionPlanIndex(Gson().toJson(restored), "Legacy"))
        val written = JsonParser.parseString(Gson().toJson(restored)).asJsonObject
        assertFalse(written.has("a"))
        assertEquals(
            "trip",
            written
                .getAsJsonArray("adventures")[0]
                .asJsonObject
                .get("id")
                .asString,
        )
        assertEquals(
            "Refuge",
            written
                .getAsJsonArray("days")[0]
                .asJsonObject
                .getAsJsonObject("weatherLocation")
                .get("label")
                .asString,
        )
    }

    @Test
    fun `selecting a GPX recovers a previously saved release plan without losing planning`() =
        runBlocking {
            val directory = createTempDirectory("release-adventure").toFile()
            try {
                val file = File(directory, "mission-plan.json")
                file.writeText(previousReleasePlan)
                val repository = MissionPlanRepository(directory, "Legacy")
                val before = repository.load()
                val selected = repository.assignRoute("day", "replacement-route")
                assertEquals("replacement-route", selected.days.first().routeId)
                assertEquals(before.days.first().notes, selected.days.first().notes)
                assertEquals(before.days.first().booking, selected.days.first().booking)
                assertEquals(selected, MissionPlanRepository(directory, "Legacy").load())
                assertFalse(JsonParser.parseString(file.readText()).asJsonObject.has("a"))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `damaged release plans fail without being overwritten`() =
        runBlocking {
            val directory = createTempDirectory("damaged-release-adventure").toFile()
            try {
                val file = File(directory, "mission-plan.json")
                val damaged = previousReleasePlan.replace("\"b\":1", "\"b\":0")
                file.writeText(damaged)
                val repository = MissionPlanRepository(directory, "Legacy")
                assertThrows(IllegalArgumentException::class.java) { decodeMissionPlanIndex(damaged, "Legacy") }
                assertFalse(runCatching { repository.assignRoute("day", "replacement-route") }.isSuccess)
                assertEquals(damaged, file.readText())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `storage moves recognize plans saved by the previous release`() {
        val directory = createTempDirectory("release-plan-storage").toFile()
        try {
            val file = File(directory, "mission-plan.json").apply { writeText(previousReleasePlan) }
            val record = PhoneOfflineStorageFile("mission-plan/mission-plan.json", file, file.length(), "source")
            val empty = File(directory, "empty-plan.json").apply { writeText("{\"days\":[]}") }
            val target = PhoneOfflineStorageFile("mission-plan/mission-plan.json", empty, empty.length(), "target")
            val result =
                reconcilePhoneOfflineMutableData(
                    listOf(record),
                    listOf(target),
                    PhoneOfflineStorageLocation.INTERNAL,
                    PhoneOfflineStorageLocation.EXTERNAL,
                    "test",
                )
            assertEquals(true, result.missionActiveSourceChosen)
            assertEquals(
                file,
                result.files
                    .first { it.relativePath == record.relativePath }
                    .source
                    ?.file,
            )
            assertEquals(previousReleasePlan, file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    // Field names from the previous release's R8 mapping, before adventure storage was protected.
    private val previousReleasePlan = """{
        "a":[
            {"a":"day","b":1,"c":"route","d":"Ridge","e":"2026-10-18","f":"08:00",
             "g":"Refuge","h":"Day notes","i":0.0,"j":1000.0,"k":"trip","l":false,
             "m":"PACK","n":"PROVIDED","o":"NOT_NEEDED","p":"REFUGE","q":"BOOKED",
             "r":{"a":46.0,"b":11.0,"c":1500.0,"d":"Refuge"},"s":"stages","t":true},
            {"a":"next","b":2,"c":"route","e":"2026-10-19","i":1000.0,"j":2000.0,"k":"trip",
             "m":"UNPLANNED","p":"BIVOUAC","q":"NOT_REQUIRED","s":"stages"}
        ],
        "b":"day",
        "c":[{"a":"trip","b":"Alpine trip","c":"2026-10-18","d":"2026-10-19","e":2,"f":"Trip notes"}],
        "d":"trip"
    }"""
}
