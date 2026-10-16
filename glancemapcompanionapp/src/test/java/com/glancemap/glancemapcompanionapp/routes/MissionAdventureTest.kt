package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.glancemapcompanionapp.weather.WeatherForecastLocation
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory

class MissionAdventureTest {
    @Test
    fun `migrates the original single plan without losing metadata or selection`() {
        val legacy = """{"days":[{"id":"day" ,
"dayNumber":1 ,
"routeId":"route" ,
"name":"Ridge" ,
"plannedDate":"2026-10-18" ,
"startDistanceMeters":1000 ,
"endDistanceMeters":2000 ,
"overnight":"Old refuge" ,
"notes":"Keep this"}] ,
"selectedDayId":"day"}"""
        val index = decodeMissionPlanIndex(legacy, "My first adventure")
        assertEquals("My first adventure", index.selectedAdventure?.name)
        assertEquals("day", index.selectedDayId)
        assertEquals("route", index.days.single().routeId)
        assertEquals("Keep this", index.days.single().notes)
        assertEquals("2026-10-18", index.days.single().plannedDate)
        assertEquals(1000.0, index.days.single().startDistanceMeters, 0.0)
        assertEquals(MissionOvernight.OTHER, index.days.single().accommodation)
        assertEquals(List(3) { MissionMeal.UNPLANNED }, index.days.single().meals)
        assertEquals(index, decodeMissionPlanIndex(Gson().toJson(index), "Other name"))
    }

    @Test
    fun `new adventures generate empty dated days and remain independent`() =
        withRepository { repository, directory ->
            val first = repository.saveAdventure(null, update("First", 3))
            val firstId = requireNotNull(first.selectedAdventureId)
            val firstDay = first.days.first()
            repository.assignRoute(firstDay.id, "route")
            val second = repository.saveAdventure(null, update("Second", 2))
            assertEquals(2, second.adventures.size)
            assertEquals(5, second.days.size)
            assertEquals(
                listOf("2026-10-18", "2026-10-19"),
                second.days.filter { it.adventureId == second.selectedAdventureId }.map { it.plannedDate },
            )
            val selected = repository.selectAdventure(firstId)
            assertEquals("route", selected.days.first { it.id == firstDay.id }.routeId)
            assertEquals(selected, MissionPlanRepository(directory, "Legacy").load())
        }

    @Test
    fun `shortening dates refuses to discard planning and leaves the file unchanged`() =
        withRepository { repository, directory ->
            val index = repository.saveAdventure(null, update("Trip", 3))
            val file = File(directory, "mission-plan.json")
            val before = file.readText()
            val result = runCatching { repository.saveAdventure(index.selectedAdventureId, update("Trip", 2)) }
            assertTrue(result.isFailure)
            assertEquals(before, file.readText())
        }

    @Test
    fun `shifting dates and extending preserves day identity meals and notes`() =
        withRepository { repository, _ ->
            val index = repository.saveAdventure(null, update("Trip", 2))
            val day = index.days.first()
            repository.assignRoute(day.id, "route")
            repository.updateDay(
                day.id,
                day.toUpdate().copy(
                    breakfast = MissionMeal.PACK,
                    notes = "Keep me",
                    overnightType = MissionOvernight.REFUGE,
                ),
            )
            val changed =
                repository.saveAdventure(
                    index.selectedAdventureId,
                    update(
                        "Trip",
                        4,
                    ).copy(startDate = "2026-11-01", endDate = "2026-11-04"),
                )
            assertEquals(day.id, changed.days.first().id)
            assertEquals("2026-11-01", changed.days.first().plannedDate)
            assertEquals(MissionMeal.PACK, changed.days.first().breakfast)
            assertEquals("Keep me", changed.days.first().notes)
            assertEquals("route", changed.days.first().routeId)
            assertEquals(4, changed.days.size)
        }

    @Test
    fun `days off retain route segments and planning when changed back to hiking`() =
        withRepository { repository, _ ->
            val day = repository.saveAdventure(null, update("Trip", 2)).days.first()
            repository.assignRoute(day.id, "route")
            val planned =
                repository
                    .updateDay(
                        day.id,
                        day.toUpdate().copy(
                            startDistanceMeters = 100.0,
                            endDistanceMeters = 2000.0,
                            lunch = MissionMeal.PACK,
                            notes = "Rest here",
                            isRestDay = true,
                        ),
                    ).days
                    .first()
            assertEquals("route", planned.routeId)
            assertNull(repository.selectDay(day.id).selectedDayId)
            val restored = repository.updateDay(day.id, planned.toUpdate().copy(isRestDay = false)).days.first()
            assertEquals(100.0, restored.startDistanceMeters, 0.0)
            assertEquals(2000.0, requireNotNull(restored.endDistanceMeters), 0.0)
            assertEquals(MissionMeal.PACK, restored.lunch)
            assertEquals("Rest here", restored.notes)
            assertFalse(restored.isRestDay)
        }

    @Test
    fun `replacing a GPX resets only its segment and keeps day metadata`() =
        withRepository { repository, _ ->
            val day = repository.saveAdventure(null, update("Trip", 1)).days.first()
            repository.assignRoute(day.id, "first-route")
            repository.updateDay(
                day.id,
                day.toUpdate().copy(
                    startDistanceMeters = 100.0,
                    endDistanceMeters = 2000.0,
                    dinner = MissionMeal.PACK,
                ),
            )
            val same = repository.assignRoute(day.id, "first-route").days.single()
            assertEquals(100.0, same.startDistanceMeters, 0.0)
            val changed = repository.assignRoute(day.id, "second-route").days.single()
            assertEquals(0.0, changed.startDistanceMeters, 0.0)
            assertNull(changed.endDistanceMeters)
            assertEquals(MissionMeal.PACK, changed.dinner)
        }

    @Test
    fun `reordering and removing update calendar slots within the selected adventure`() =
        withRepository { repository, _ ->
            val first = repository.saveAdventure(null, update("First", 3))
            val day = first.days.last()
            repository.updateDay(day.id, day.toUpdate().copy(notes = "Moves with me"))
            repository.saveAdventure(null, update("Second", 2))
            repository.selectAdventure(requireNotNull(first.selectedAdventureId))
            val moved = repository.moveDay(day.id, 0)
            val days = moved.days.filter { it.adventureId == first.selectedAdventureId }
            assertEquals(day.id, days.first().id)
            assertEquals("2026-10-18", days.first().plannedDate)
            assertEquals("Moves with me", days.first().notes)
            val removed = repository.removeDay(day.id)
            assertEquals("2026-10-19", removed.selectedAdventure?.endDate)
            assertEquals(2, removed.days.count { it.adventureId != first.selectedAdventureId })
        }

    @Test
    fun `totals count rest day meals and nights but flag unassigned hiking routes`() {
        val day =
            MissionPlanDay(
                "rest",
                1,
                isRestDay = true,
                breakfast = MissionMeal.PACK,
                lunch = MissionMeal.PACK,
                dinner = MissionMeal.PROVIDED,
                overnightType = MissionOvernight.REFUGE,
                booking = MissionBooking.TO_BOOK,
            )
        val totals =
            MissionPlanUiState(
                planDays = listOf(day, MissionPlanDay("hike", 2)),
                adventures = listOf(MissionAdventure("trip", "Trip", people = 3)),
                selectedAdventureId = "trip",
            ).adventureTotals()
        assertEquals(6, totals.packedMeals)
        assertEquals(3, totals.providedMeals)
        assertEquals(1, totals.restDays)
        assertEquals(1, totals.missingRoutes)
        assertEquals(1, totals.nights)
        assertEquals(1, totals.unbookedNights)
        assertEquals(0.0, totals.distanceMeters, 0.0)
    }

    @Test
    fun `malformed storage is reported and never overwritten by a mutation`() =
        withRepository { repository, directory ->
            directory.mkdirs()
            val file = File(directory, "mission-plan.json")
            file.writeText("{broken")
            assertTrue(runCatching { repository.saveAdventure(null, update("Trip", 1)) }.isFailure)
            assertEquals("{broken", file.readText())
            assertThrows(IllegalArgumentException::class.java) { decodeMissionPlanIndex("{}", "Legacy") }
        }

    @Test
    fun `rest day forecast identity includes the inherited location and ignores meals`() {
        val location = WeatherForecastLocation(46.0, 11.0, null, "Refuge")
        val day = MissionPlanDay("rest", 1, isRestDay = true, plannedDate = "2026-10-18")
        val effective = day.copy(weatherLocation = location)
        val weather = MissionDayWeatherUiState(plannedDate = day.plannedDate, planKey = effective.weatherKey())
        val state =
            MissionPlanUiState(
                planDays = listOf(day),
                restLocations = mapOf(day.id to location),
                weatherByDayId = mapOf(day.id to weather),
            )
        assertEquals(weather, state.weatherFor(day))
        assertEquals(weather, state.copy(planDays = listOf(day.copy(lunch = MissionMeal.PACK))).weatherFor(day))
        assertNull(state.copy(restLocations = mapOf(day.id to location.copy(latitude = 47.0))).weatherFor(day))
        assertTrue(day.copy(plannedDate = "2027-06-01").forecastTooFarAhead(LocalDate.parse("2026-10-15")))
    }

    @Test
    fun `rapid independent meal edits preserve earlier choices`() =
        withRepository { repository, _ ->
            val day = repository.saveAdventure(null, update("Trip", 1)).days.single()
            repository.editDay(day.id) { current -> current.toUpdate().copy(breakfast = MissionMeal.PACK) }
            repository.editDay(day.id) { current -> current.toUpdate().copy(lunch = MissionMeal.PROVIDED) }
            val saved = repository.load().days.single()
            assertEquals(MissionMeal.PACK, saved.breakfast)
            assertEquals(MissionMeal.PROVIDED, saved.lunch)
        }

    private fun update(
        name: String,
        days: Int,
    ) = MissionAdventureUpdate(
        name,
        "2026-10-18",
        LocalDate.parse("2026-10-18").plusDays(days - 1L).toString(),
        2,
        null,
    )

    private fun withRepository(block: suspend (MissionPlanRepository, File) -> Unit) =
        runBlocking {
            val directory = createTempDirectory("mission-adventure-test").toFile()
            try {
                block(MissionPlanRepository(directory, "Legacy"), directory)
            } finally {
                directory.deleteRecursively()
            }
        }
}
