package com.glancemap.shared.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveHikeSnapshotTest {
    @Test
    fun `codec round trips a route with optional metrics`() {
        val snapshot =
            ActiveHikeSnapshot(
                phase = ActiveHikePhase.FOLLOWING_ROUTE,
                routeId = "dolomites-loop",
                routeTitle = "Dolomites Loop • Day 2",
                distanceFromStartMeters = 12_800.0,
                distanceRemainingMeters = 8_600.0,
                progressFraction = 0.68,
                estimatedRemainingSeconds = 8_100L,
                remainingAscentMeters = 620.0,
                remainingDescentMeters = 890.0,
                offRoute = false,
                recordedAtEpochMillis = 1_726_000_000_000L,
            )

        val decoded = ActiveHikeSnapshotCodec.decode(ActiveHikeSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `codec rejects unknown versions and invalid values`() {
        val invalidVersion = "version=99\nphase=IDLE\nrecorded_at=1".toByteArray()
        val invalidProgress =
            "version=1\nphase=FOLLOWING_ROUTE\nprogress=1.4\noff_route=0\nrecorded_at=1".toByteArray()
        val malformedMetric =
            "version=1\nphase=FOLLOWING_ROUTE\nprogress=climb\noff_route=0\nrecorded_at=1".toByteArray()
        val malformedBoolean =
            "version=1\nphase=IDLE\noff_route=false\nrecorded_at=1".toByteArray()

        assertNull(ActiveHikeSnapshotCodec.decode(invalidVersion))
        assertNull(ActiveHikeSnapshotCodec.decode(invalidProgress))
        assertNull(ActiveHikeSnapshotCodec.decode(malformedMetric))
        assertNull(ActiveHikeSnapshotCodec.decode(malformedBoolean))
    }

    @Test
    fun `codec treats blank optional fields as absent`() {
        val snapshot =
            ActiveHikeSnapshot(
                phase = ActiveHikePhase.WAITING_FOR_LOCATION,
                routeId = null,
                routeTitle = null,
                distanceFromStartMeters = null,
                distanceRemainingMeters = null,
                progressFraction = null,
                estimatedRemainingSeconds = null,
                remainingAscentMeters = null,
                remainingDescentMeters = null,
                offRoute = false,
                recordedAtEpochMillis = 1L,
            )

        val decoded = ActiveHikeSnapshotCodec.decode(ActiveHikeSnapshotCodec.encode(snapshot))

        assertNull(decoded?.routeId)
        assertNull(decoded?.distanceRemainingMeters)
        assertFalse(decoded?.offRoute ?: true)
        assertTrue(decoded?.phase == ActiveHikePhase.WAITING_FOR_LOCATION)
    }

    @Test
    fun `codec decodes version one snapshots without recording metrics`() {
        val versionOnePayload =
            """
            version=1
            phase=FOLLOWING_ROUTE
            route_id=ZG9sb21pdGVz
            route_title=RG9sb21pdGVzIExvb3A
            distance_from_start=12800.0
            distance_remaining=8600.0
            progress=0.68
            estimated_remaining=8100
            remaining_ascent=620.0
            remaining_descent=890.0
            off_route=0
            recorded_at=1726000000000
            """.trimIndent().toByteArray()

        val decoded = ActiveHikeSnapshotCodec.decode(versionOnePayload)

        assertEquals(ActiveHikePhase.FOLLOWING_ROUTE, decoded?.phase)
        assertNull(decoded?.activeDurationSeconds)
        assertNull(decoded?.currentSpeedMetersPerSecond)
        assertNull(decoded?.currentAltitudeMeters)
    }
}
