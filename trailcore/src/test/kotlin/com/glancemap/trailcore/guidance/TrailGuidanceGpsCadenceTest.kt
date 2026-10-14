package com.glancemap.trailcore.guidance

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailGuidanceGpsCadenceTest {
    @Test
    fun foregroundUsesNormalCadenceAndScreenOffUsesAdaptiveCadence() {
        val state =
            TrailGuidanceState(
                active = true,
                mode = TrailGuidanceMode.FOLLOW_ROUTE,
                trackTitle = "Route",
                nextInstruction = null,
                distanceToInstructionMeters = null,
                distanceToStartMeters = null,
                bearingToStartDegrees = null,
                distanceToRouteMeters = null,
                bearingToRouteDegrees = null,
                distanceRemainingMeters = null,
                routeProgressFraction = null,
                offRoute = false,
            )

        assertEquals(FOREGROUND_HIKE_GUIDANCE_GPS_INTERVAL_MS, resolveTrailGuidanceGpsIntervalMs(true, state, null, "HIKE"))
        assertEquals(FOREGROUND_BIKE_GUIDANCE_GPS_INTERVAL_MS, resolveTrailGuidanceGpsIntervalMs(true, state, null, "BIKE"))
        assertEquals(ADAPTIVE_TBT_CRUISE_INTERVAL_MS, resolveTrailGuidanceGpsIntervalMs(false, state, null, "HIKE"))
        assertEquals(BIKE_ADAPTIVE_TBT_CRUISE_INTERVAL_MS, resolveTrailGuidanceGpsIntervalMs(false, state, null, "BIKE"))
    }
}
