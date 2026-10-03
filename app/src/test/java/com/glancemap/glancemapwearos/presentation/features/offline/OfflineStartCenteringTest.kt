package com.glancemap.glancemapwearos.presentation.features.offline

import com.glancemap.glancemapwearos.core.maps.GeoBounds
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mapsforge.core.model.LatLong

class OfflineStartCenteringTest {
    @Test
    fun `online fallback waits for map metadata instead of consuming empty center`() {
        assertEquals(true, shouldDeferInitialCentering(deferWhenNoCenter = true, targetCenter = null))
        assertEquals(false, shouldDeferInitialCentering(deferWhenNoCenter = true, targetCenter = LatLong(45.0, 6.0)))
        assertEquals(false, shouldDeferInitialCentering(deferWhenNoCenter = false, targetCenter = null))
    }

    @Test
    fun `map only centers on selected map`() {
        val center =
            resolveOfflineStartCenter(
                selectedMapArea = alpsMapArea(),
                activeGpxDetails = emptyList(),
            )

        assertLatLongEquals(LatLong(45.0, 6.0), center)
    }

    @Test
    fun `map and active gpx on map centers on gpx`() {
        val center =
            resolveOfflineStartCenter(
                selectedMapArea = alpsMapArea(),
                activeGpxDetails =
                    listOf(
                        track(
                            id = "alps.gpx",
                            points = listOf(LatLong(45.1, 6.1), LatLong(45.3, 6.3)),
                        ),
                    ),
            )

        assertLatLongEquals(LatLong(45.2, 6.2), center)
    }

    @Test
    fun `map and active gpx outside map centers on map`() {
        val center =
            resolveOfflineStartCenter(
                selectedMapArea = alpsMapArea(),
                activeGpxDetails =
                    listOf(
                        track(
                            id = "pyrenees.gpx",
                            points = listOf(LatLong(42.7, 1.4), LatLong(42.9, 1.6)),
                        ),
                    ),
            )

        assertLatLongEquals(LatLong(45.0, 6.0), center)
    }

    @Test
    fun `no selected map centers on active gpx`() {
        val center =
            resolveOfflineStartCenter(
                selectedMapArea = null,
                activeGpxDetails =
                    listOf(
                        track(
                            id = "route.gpx",
                            points = listOf(LatLong(44.0, 5.0), LatLong(46.0, 7.0)),
                        ),
                    ),
            )

        assertLatLongEquals(LatLong(45.0, 6.0), center)
    }

    private fun alpsMapArea(): SelectedOfflineMapArea =
        SelectedOfflineMapArea(
            bounds =
                GeoBounds(
                    minLat = 44.0,
                    maxLat = 46.0,
                    minLon = 5.0,
                    maxLon = 7.0,
                ),
            center = LatLong(45.0, 6.0),
        )

    private fun track(
        id: String,
        points: List<LatLong>,
    ): GpxTrackDetails =
        GpxTrackDetails(
            id = id,
            points = points,
            title = id,
            distance = 0.0,
            elevationGain = 0.0,
            startPoint = points.first(),
            endPoint = points.last(),
        )

    private fun assertLatLongEquals(
        expected: LatLong,
        actual: LatLong?,
    ) {
        requireNotNull(actual)
        assertEquals(expected.latitude, actual.latitude, 0.000001)
        assertEquals(expected.longitude, actual.longitude, 0.000001)
    }
}
