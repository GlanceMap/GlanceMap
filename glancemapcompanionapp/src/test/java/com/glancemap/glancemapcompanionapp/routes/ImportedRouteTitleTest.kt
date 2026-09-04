package com.glancemap.glancemapcompanionapp.routes

import com.glancemap.glancemapcompanionapp.map.phoneGpxDisplayNameFromFileName
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportedRouteTitleTest {
    @Test
    fun `filename wins over embedded metadata title`() {
        assertEquals(
            "Weekend Hike",
            phoneGpxDisplayNameFromFileName("Weekend Hike.gpx"),
        )
    }

    @Test
    fun `filename wins when only track metadata has a title`() {
        assertEquals(
            "Mountain Day",
            phoneGpxDisplayNameFromFileName("Mountain Day.gpx"),
        )
    }

    @Test
    fun `route library keeps display identity separate from metadata and uuid storage`() {
        val route =
            RouteLibraryRoute(
                id = "route-id",
                displayName = "Alps Weekend",
                storedFileName = "7fa124ab.gpx",
                importedAtMillis = 1234L,
                summary = emptySummary,
                metadataTitle = "Track 001",
            )

        assertEquals("Alps Weekend", route.displayName)
        assertEquals("7fa124ab.gpx", route.storedFileName)
        assertEquals("Track 001", route.metadataTitle)
    }

    @Test
    fun `reads a route saved by the previous minified companion build`() {
        val route =
            Gson().fromJson(
                """
                {
                  "a":"route-id",
                  "b":"Saved route",
                  "c":"route-id.gpx",
                  "d":1234,
                  "e":{"a":1000,"b":120,"c":80,"d":900,"e":4,"f":300,"g":50}
                }
                """.trimIndent(),
                RouteLibraryRoute::class.java,
            )

        assertEquals("route-id", route.id)
        assertEquals("Saved route", route.displayName)
        assertNull(route.metadataTitle)
        assertEquals(1_000.0, route.summary.distanceMeters, 0.0)
        assertEquals(120.0, route.summary.elevationGainMeters, 0.0)
    }

    private companion object {
        val emptySummary =
            RouteLibrarySummary(
                distanceMeters = 0.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
                estimatedDurationSeconds = 0.0,
                waypointCount = 0,
                firstThirtyMinutesDistanceMeters = 0.0,
                firstThirtyMinutesAscentMeters = 0.0,
            )
    }
}
