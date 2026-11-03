package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong

class WorldWindGpxTest {
    @Test
    fun `track segment boundaries remain separate`() {
        val points =
            listOf(
                TrackPoint(LatLong(0.0, 0.0), null),
                TrackPoint(LatLong(0.1, 0.1), null),
                TrackPoint(LatLong(1.0, 1.0), null, startsNewSegment = true),
                TrackPoint(LatLong(1.1, 1.1), null),
            )
        val segments = worldWindTrackSegments(points)
        assertEquals(2, segments.size)
        assertEquals(LatLong(0.1, 0.1), segments[0].last())
        assertEquals(LatLong(1.0, 1.0), segments[1].first())
    }
}
