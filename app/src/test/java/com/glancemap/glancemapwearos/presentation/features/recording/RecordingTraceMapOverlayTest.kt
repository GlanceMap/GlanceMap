package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTraceMapOverlayTest {
    @Test
    fun liveTraceWithholdsMutableTailAndEndsFromLastStableFixAtMarker() {
        val first = GeoPoint(45.0, 6.0)
        val second = GeoPoint(45.0001, 6.0001)
        val third = GeoPoint(45.0002, 6.0002)
        val newest = GeoPoint(45.0003, 6.0003)

        val state =
            recordingTraceRenderState(
                segments = listOf(listOf(first, second, third, newest)),
                followLocationMarker = true,
            )

        assertEquals(listOf(listOf(first, second)), state.segments)
        assertEquals(second, state.liveTailStart)
    }

    @Test
    fun pausedTraceKeepsEveryCanonicalPoint() {
        val points =
            listOf(
                GeoPoint(45.0, 6.0),
                GeoPoint(45.0001, 6.0001),
                GeoPoint(45.0002, 6.0002),
            )

        val state =
            recordingTraceRenderState(
                segments = listOf(points),
                followLocationMarker = false,
            )

        assertEquals(listOf(points), state.segments)
        assertNull(state.liveTailStart)
    }
}
