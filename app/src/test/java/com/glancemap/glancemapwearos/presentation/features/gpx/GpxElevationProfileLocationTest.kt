package com.glancemap.glancemapwearos.presentation.features.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mapsforge.core.model.LatLong

class GpxElevationProfileLocationTest {
    @Test
    fun nearbyAccurateFixProjectsOntoTheProfile() {
        val profile = profile()

        val marker =
            elevationProfileLocationMarker(
                profile = profile,
                currentLocation = LatLong(48.00018, 2.005),
                accuracyMeters = 6f,
            )

        assertNotNull(marker)
        assertEquals(profile.totalDistance / 2.0, marker!!.distance, 4.0)
    }

    @Test
    fun distantOrInaccurateFixDoesNotCreateAMarker() {
        val profile = profile()

        val distant =
            elevationProfileLocationMarker(
                profile = profile,
                currentLocation = LatLong(48.002, 2.005),
                accuracyMeters = 6f,
            )
        val inaccurate =
            elevationProfileLocationMarker(
                profile = profile,
                currentLocation = LatLong(48.0, 2.005),
                accuracyMeters = 51f,
            )

        assertNull(distant)
        assertNull(inaccurate)
    }

    @Test
    fun chartElevationInterpolatesBetweenProfileSamples() {
        val samples =
            listOf(
                ElevationSample(0.0, 100.0, 0.0, 0.0, null),
                ElevationSample(100.0, 200.0, 0.0, 0.0, null),
            )

        assertEquals(150.0, elevationAtDistance(samples, distance = 50.0)!!, 0.0)
    }

    private fun profile(): TrackProfile =
        buildProfile(
            sig = FileSig(lastModified = 0L, length = 3L),
            pts =
                listOf(
                    TrackPoint(latLong = LatLong(48.0, 2.0), elevation = 100.0),
                    TrackPoint(latLong = LatLong(48.0, 2.01), elevation = 150.0),
                ),
        )
}
