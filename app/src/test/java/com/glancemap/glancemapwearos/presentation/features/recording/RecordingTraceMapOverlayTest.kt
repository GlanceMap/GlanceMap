package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.model.LatLong

class RecordingTraceMapOverlayTest {
    @Test
    fun liveTraceWithholdsMutableTailAndEndsFromLastStableFixAtMarker() {
        val first = LatLong(45.0, 6.0)
        val second = LatLong(45.0001, 6.0001)
        val third = LatLong(45.0002, 6.0002)
        val newest = LatLong(45.0003, 6.0003)

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
                LatLong(45.0, 6.0),
                LatLong(45.0001, 6.0001),
                LatLong(45.0002, 6.0002),
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
