package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class MissionRouteStagesTest {
    @Test
    fun `stages keep one source and skip rest days without losing planning`() {
        val original = itinerary()
        val planned = original.withLinkedStages("trip", "route", listOf("a", "b", "c"), listOf(20.0, 40.0), 60.0)
        val hikes = planned.filterNot { it.isRestDay }
        assertEquals(listOf(0.0, 20.0, 40.0), hikes.map { it.startDistanceMeters })
        assertEquals(listOf(20.0, 40.0, 60.0), hikes.map { it.endDistanceMeters })
        assertEquals(listOf("route"), hikes.map { it.routeId }.distinct())
        assertEquals(1, hikes.mapNotNull { it.stageGroupId }.distinct().size)
        assertEquals(original[1], planned[1])
        assertEquals(MissionMeal.PACK, planned[0].lunch)
        assertEquals("Keep me", planned[2].notes)
        planned.validateStageLinks()
    }

    @Test
    fun `moving an arrival atomically changes the next hiking start across days off`() {
        val planned = itinerary().withLinkedStages("trip", "route", listOf("a", "b", "c"), listOf(20.0, 40.0), 60.0)
        val changed = planned.changeLinkedDay(planned.first().copy(endDistanceMeters = 25.0))
        assertEquals(25.0, requireNotNull(changed[0].endDistanceMeters), 0.0)
        assertEquals(25.0, changed[2].startDistanceMeters, 0.0)
        assertEquals(40.0, requireNotNull(changed[2].endDistanceMeters), 0.0)
        assertEquals(planned[1], changed[1])
        assertTrue(changed[0].stopReviewRequired)
        assertEquals(MissionBooking.BOOKED, changed[0].booking)
        assertEquals(planned[3], changed[3])
        changed.validateStageLinks()
    }

    @Test
    fun `editing the next departure also moves the previous arrival`() {
        val planned = itinerary().withLinkedStages("trip", "route", listOf("a", "b", "c"), listOf(20.0, 40.0), 60.0)
        val changed = planned.changeLinkedDay(planned[2].copy(startDistanceMeters = 15.0))
        assertEquals(15.0, requireNotNull(changed[0].endDistanceMeters), 0.0)
        assertEquals(15.0, changed[2].startDistanceMeters, 0.0)
    }

    @Test
    fun `boundaries cannot cross other stops or change the full route endpoints`() {
        val planned = itinerary().withLinkedStages("trip", "route", listOf("a", "b", "c"), listOf(20.0, 40.0), 60.0)
        assertThrows(
            IllegalArgumentException::class.java,
        ) { planned.changeLinkedDay(planned.first().copy(endDistanceMeters = 40.0)) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { planned.changeLinkedDay(planned.first().copy(startDistanceMeters = 1.0)) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { planned.changeLinkedDay(planned.last().copy(endDistanceMeters = 59.0)) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { itinerary().withLinkedStages("trip", "route", listOf("a", "c"), listOf(20.0), 60.0) }
    }

    @Test
    fun `independent days using the same GPX never acquire implicit links`() {
        val days =
            listOf(
                MissionPlanDay("a", 1, "route", endDistanceMeters = 20.0),
                MissionPlanDay("b", 2, "route", startDistanceMeters = 20.0, endDistanceMeters = 60.0),
            )
        val changed = days.changeLinkedDay(days.first().copy(endDistanceMeters = 25.0))
        assertEquals(days[1], changed[1])
    }

    @Test
    fun `replacing one stage unlinks its set and preserves the remaining sections`() {
        val planned = itinerary().withLinkedStages("trip", "route", listOf("a", "b", "c"), listOf(20.0, 40.0), 60.0)
        val changed =
            planned.changeLinkedDay(
                planned[2].copy(routeId = "other", startDistanceMeters = 0.0, endDistanceMeters = null),
            )
        assertTrue(changed.all { it.stageGroupId == null })
        assertEquals(planned[0].endDistanceMeters, changed[0].endDistanceMeters)
        assertEquals("Keep me", changed[2].notes)
    }

    @Test
    fun `suggestions are ordered GPX points and leave one section for every day`() {
        val profile =
            buildTrailRouteProfile(
                (0..12).map { index ->
                    TrailPoint(GeoPoint(46.0, 11.0 + index * 0.001), index * 10.0)
                },
            )
        val stops = profile.suggestStageStops(5)
        assertEquals(4, stops.size)
        assertTrue(stops.all { it in profile.cumulativeDistanceMeters })
        assertTrue((listOf(0.0) + stops + profile.totalDistanceMeters).zipWithNext().all { (a, b) -> b > a })
        assertThrows(IllegalArgumentException::class.java) { profile.suggestStageStops(20) }
    }

    @Test
    fun `repository persists shared boundaries and rejects invalid moves without a partial save`() =
        runBlocking {
            val directory = createTempDirectory("linked-stages").toFile()
            try {
                val repository = MissionPlanRepository(directory, "Legacy")
                val index =
                    repository.saveAdventure(
                        null,
                        MissionAdventureUpdate("Trip", "2026-10-15", "2026-10-18", 2, null),
                    )
                val adventure = requireNotNull(index.selectedAdventureId)
                val ids = index.days.map { it.id }
                repository.planStages(adventure, "route", ids, listOf(10.0, 20.0, 30.0), 40.0)
                val moved = repository.updateSegment(ids[2], 20.0, 35.0)
                assertEquals(35.0, moved.days[3].startDistanceMeters, 0.0)
                val file = File(directory, "mission-plan.json")
                val saved = file.readText()
                assertTrue(runCatching { repository.updateSegment(ids[2], 20.0, 40.0) }.isFailure)
                assertEquals(saved, file.readText())
                val restored = MissionPlanRepository(directory, "Legacy").load()
                assertEquals(moved, restored)
                assertEquals(restored, decodeMissionPlanIndex(Gson().toJson(restored), "Legacy"))
                repository.editDay(ids[0]) { it.toUpdate().copy(isRestDay = true) }
                assertTrue(repository.load().days.all { it.stageGroupId == null })
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `stay review is explicit and does not discard the booking`() {
        val day = MissionPlanDay("a", 1, booking = MissionBooking.BOOKED, stopReviewRequired = true)
        assertTrue(day.withUpdate(day.toUpdate().copy(lunch = MissionMeal.PACK)).stopReviewRequired)
        val reviewed = day.withUpdate(day.toUpdate().copy(reviewedStop = true))
        assertFalse(reviewed.stopReviewRequired)
        assertEquals(MissionBooking.BOOKED, reviewed.booking)
        assertNull(reviewed.stageGroupId)
    }

    @Test
    fun `rest day lodging is flagged when its inherited stop moves`() {
        val planned =
            itinerary()
                .withLinkedStages("trip", "route", listOf("a", "b", "c"), listOf(20.0, 40.0), 60.0)
                .map {
                    if (
                        it.isRestDay
                    ) {
                        it.copy(overnightType = MissionOvernight.REFUGE)
                    } else {
                        it.copy(stopReviewRequired = false)
                    }
                }
        val changed =
            planned
                .changeLinkedDay(
                    planned.first().copy(endDistanceMeters = 25.0),
                ).reviewMovedRestStays(planned)
        assertTrue(changed.first().stopReviewRequired)
        assertTrue(changed[1].stopReviewRequired)
    }

    @Test
    fun `moving a rest day preserves links and reordering a hike only unlinks affected groups`() {
        val original = itinerary() + MissionPlanDay("d", 5, adventureId = "trip")
        val planned =
            original
                .withLinkedStages("trip", "route", listOf("a", "b"), listOf(20.0), 40.0)
                .withLinkedStages("trip", "other", listOf("c", "d"), listOf(10.0), 30.0)
        val restMoved = planned.moveMissionPlanDay("rest", 4).unlinkReorderedStageGroups()
        assertEquals(
            planned.map { it.stageGroupId }.filterNotNull().toSet(),
            restMoved.map { it.stageGroupId }.filterNotNull().toSet(),
        )
        restMoved.validateStageLinks()
        val hikeMoved = planned.moveMissionPlanDay("a", 2).unlinkReorderedStageGroups()
        assertNull(hikeMoved.first { it.id == "a" }.stageGroupId)
        assertNull(hikeMoved.first { it.id == "b" }.stageGroupId)
        assertEquals(planned.first { it.id == "c" }.stageGroupId, hikeMoved.first { it.id == "c" }.stageGroupId)
        hikeMoved.validateStageLinks()
    }

    private fun itinerary(): List<MissionPlanDay> =
        listOf(
            MissionPlanDay(
                "a",
                1,
                adventureId = "trip",
                lunch = MissionMeal.PACK,
                overnightType = MissionOvernight.REFUGE,
                booking = MissionBooking.BOOKED,
            ),
            MissionPlanDay("rest", 2, adventureId = "trip", isRestDay = true),
            MissionPlanDay("b", 3, adventureId = "trip", notes = "Keep me"),
            MissionPlanDay("c", 4, adventureId = "trip"),
        )
}
