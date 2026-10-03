package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.presentation.features.recording.FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class GpxSegmentParsingTest {
    @Test
    fun profilePreservesTrackSegmentBoundaryAndExcludesGapDistance() {
        val points =
            listOf(
                point(latitude = 0.0, longitude = 0.0),
                point(latitude = 0.0, longitude = 0.001),
                point(latitude = 1.0, longitude = 1.0, startsNewSegment = true),
                point(latitude = 1.0, longitude = 1.001),
            )

        val profile =
            buildProfile(
                sig = FileSig(lastModified = 0L, length = 0L),
                pts = points,
            )

        assertTrue(points[2].startsNewSegment)
        assertEquals(0.0, profile.segLen[1], 0.0)
        assertEquals(222.4, profile.totalDistance, 1.0)
    }

    @Test
    fun importedFastestSpeedOnlyTrustsAnExplicitGeometryMethod() {
        assertNull(
            resolveImportedFastestSpeedMps(
                recomputedSpeedMps = null,
                persistedSpeedMps = 18.0,
                persistedMethod = null,
            ),
        )
        assertEquals(
            18.0,
            resolveImportedFastestSpeedMps(
                recomputedSpeedMps = null,
                persistedSpeedMps = 18.0,
                persistedMethod = FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1,
            ) ?: -1.0,
            0.0,
        )
        assertEquals(
            5.0,
            resolveImportedFastestSpeedMps(
                recomputedSpeedMps = 5.0,
                persistedSpeedMps = 18.0,
                persistedMethod = FASTEST_SPEED_METHOD_CONTINUOUS_SEGMENT_GEOMETRY_V1,
            ) ?: -1.0,
            0.0,
        )
    }

    private fun point(
        latitude: Double,
        longitude: Double,
        startsNewSegment: Boolean = false,
    ): TrackPoint =
        TrackPoint(
            latLong = LatLong(latitude, longitude),
            elevation = null,
            startsNewSegment = startsNewSegment,
        )
}
