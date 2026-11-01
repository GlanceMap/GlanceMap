package com.glancemap.glancemapcompanionapp.hike

import org.junit.Assert.*
import org.junit.Test

class PhoneHikeTimelineTest {
    @Test
    fun stopConsumesTimeWithoutMovingAndFinishIncludesStop() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val checkpoint = checkpoint(0, .5, 15)
        val position = requireNotNull(model.position(0, .5))
        val timeline = PhoneHikeTimeline(model, PhoneHikePlan(model.route.id, checkpoints = listOf(checkpoint)))

        val during = timeline.atSeconds(position.elapsedSeconds + 450.0)
        assertEquals(position.distanceMeters, during.distanceMeters, .01)
        assertEquals(position.coordinate, during.coordinate)
        assertEquals(model.totalSeconds + 900.0, timeline.totalSeconds, .01)
        assertEquals(position.elapsedSeconds, timeline.secondsAt(position), .01)
        assertTrue(timeline.atSeconds(position.elapsedSeconds + 901.0).distanceMeters > position.distanceMeters)
    }

    @Test
    fun reversedRouteKeepsStopAtItsCanonicalCoordinate() {
        val model = PhoneHikeRouteModel(hikeRoute(), reversed = true)
        val position = requireNotNull(model.position(0, .5))
        val plan = PhoneHikePlan(model.route.id, checkpoints = listOf(checkpoint(0, .5, 10)))
        val timeline = PhoneHikeTimeline(model, plan)
        assertEquals(position.coordinate, timeline.atSeconds(position.elapsedSeconds + 300.0).coordinate)
        assertEquals(model.totalSeconds + 600.0, timeline.totalSeconds, .01)
    }

    @Test
    fun finishStopDoesNotExtendTheArrivalTime() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan = PhoneHikePlan(model.route.id, checkpoints = listOf(checkpoint(1, 1.0, 10)))
        val timeline = PhoneHikeTimeline(model, plan)
        assertEquals(model.totalSeconds, timeline.totalSeconds, .01)
    }

    @Test
    fun timelineEndpointsAreExactRouteEndpoints() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val timeline = PhoneHikeTimeline(model, PhoneHikePlan(model.route.id))
        assertEquals(
            model.route.points
                .first()
                .location,
            timeline.atSeconds(0.0).coordinate,
        )
        assertEquals(
            model.route.points
                .last()
                .location,
            timeline.atSeconds(timeline.totalSeconds).coordinate,
        )
    }

    @Test
    fun timelineUsesTheExistingPlannedTimeAcrossMultipleStops() {
        val model = PhoneHikeRouteModel(hikeRoute())
        val plan =
            PhoneHikePlan(
                model.route.id,
                checkpoints = listOf(checkpoint(0, .25, 10), checkpoint(1, .5, 20)),
            )
        val timeline = PhoneHikeTimeline(model, plan)
        listOf(0.0, .2, .5, .8, 1.0).forEach { fraction ->
            val position = requireNotNull(model.atDistance(model.totalDistanceMeters * fraction))
            assertEquals(model.plannedSecondsAt(position, plan), timeline.secondsAt(position), .001)
        }
    }
}
