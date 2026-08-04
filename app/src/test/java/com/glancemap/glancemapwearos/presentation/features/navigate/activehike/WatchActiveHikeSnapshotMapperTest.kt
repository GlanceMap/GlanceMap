package com.glancemap.glancemapwearos.presentation.features.navigate.activehike

import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.shared.transfer.ActiveHikePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchActiveHikeSnapshotMapperTest {
    @Test
    fun `maps following route guidance into a companion snapshot`() {
        val snapshot =
            guidanceState(
                active = true,
                mode = GuidanceMode.FOLLOW_ROUTE,
                distanceRemainingMeters = 8_600.0,
                routeProgressFraction = 0.68f,
                offRoute = true,
            ).toActiveHikeSnapshot(
                routeId = "dolomites-loop",
                paused = false,
                pausedRouteTitle = null,
                recordedAtEpochMillis = 1L,
            )

        assertEquals(ActiveHikePhase.FOLLOWING_ROUTE, snapshot.phase)
        assertEquals("dolomites-loop", snapshot.routeId)
        assertEquals(8_600.0, snapshot.distanceRemainingMeters)
        assertEquals(0.68, snapshot.progressFraction ?: 0.0, 0.001)
        assertTrue(snapshot.offRoute)
    }

    @Test
    fun `paused guidance remains visible even though runtime guidance is inactive`() {
        val snapshot =
            guidanceState(
                active = false,
                mode = GuidanceMode.WAITING_FOR_LOCATION,
                distanceRemainingMeters = 23_400.0,
                routeProgressFraction = 0f,
                offRoute = false,
            ).copy(trackTitle = "").toActiveHikeSnapshot(
                routeId = "dolomites-loop",
                paused = true,
                pausedRouteTitle = "Dolomites Loop",
                recordedAtEpochMillis = 1L,
            )

        assertEquals(ActiveHikePhase.PAUSED, snapshot.phase)
        assertEquals("Dolomites Loop", snapshot.routeTitle)
        assertFalse(snapshot.offRoute)
    }

    private fun guidanceState(
        active: Boolean,
        mode: GuidanceMode,
        distanceRemainingMeters: Double,
        routeProgressFraction: Float,
        offRoute: Boolean,
    ): TurnByTurnGuidanceState =
        TurnByTurnGuidanceState(
            active = active,
            mode = mode,
            trackTitle = "Dolomites Loop",
            nextInstruction = null,
            distanceToInstructionMeters = null,
            distanceToStartMeters = null,
            bearingToStartDegrees = null,
            distanceToRouteMeters = null,
            bearingToRouteDegrees = null,
            distanceRemainingMeters = distanceRemainingMeters,
            routeProgressFraction = routeProgressFraction,
            offRoute = offRoute,
            distanceFromStartMeters = 12_800.0,
            estimatedRemainingSeconds = 8_100L,
            remainingAscentMeters = 620.0,
            remainingDescentMeters = 890.0,
        )
}
