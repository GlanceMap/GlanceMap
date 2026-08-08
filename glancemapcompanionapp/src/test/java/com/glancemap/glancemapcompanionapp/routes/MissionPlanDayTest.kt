package com.glancemap.glancemapcompanionapp.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MissionPlanDayTest {
    @Test
    fun `moving a day renumbers without losing its mission metadata`() {
        val first = day(id = "first", number = 1)
        val second =
            day(
                id = "second",
                number = 2,
                name = "Ridge day",
                plannedDate = "2026-08-12",
                overnight = "Rifugio Lagazuoi",
                notes = "Book dinner before leaving.",
            )
        val third = day(id = "third", number = 3)

        val reordered = listOf(first, second, third).moveMissionPlanDay(dayId = second.id, targetIndex = 0)

        assertEquals(listOf("second", "first", "third"), reordered.map(MissionPlanDay::id))
        assertEquals(listOf(1, 2, 3), reordered.map(MissionPlanDay::dayNumber))
        assertEquals("Ridge day", reordered.first().name)
        assertEquals("2026-08-12", reordered.first().plannedDate)
        assertEquals("Rifugio Lagazuoi", reordered.first().overnight)
        assertEquals("Book dinner before leaving.", reordered.first().notes)
    }

    @Test
    fun `moving an unknown day leaves the existing plan unchanged`() {
        val plan = listOf(day(id = "first", number = 1), day(id = "second", number = 2))

        assertSame(plan, plan.moveMissionPlanDay(dayId = "missing", targetIndex = 0))
    }

    @Suppress("LongParameterList")
    private fun day(
        id: String,
        number: Int,
        name: String? = null,
        plannedDate: String? = null,
        overnight: String? = null,
        notes: String? = null,
    ) = MissionPlanDay(
        id = id,
        dayNumber = number,
        routeId = "route-$id",
        name = name,
        plannedDate = plannedDate,
        overnight = overnight,
        notes = notes,
    )
}
