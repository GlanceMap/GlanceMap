package com.glancemap.glancemapcompanionapp.routes

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedRouteTitleTest {
    @Test
    fun `uses filename when GPX metadata is generated coordinate bounds`() {
        assertEquals(
            "rando rotwans without peak",
            importedRouteTitle(
                parsedTitle = "47.672269,11.886502999999948-47.687337,11.987638999999945",
                fallbackTitle = "rando rotwans without peak",
            ),
        )
    }

    @Test
    fun `keeps meaningful GPX metadata title`() {
        assertEquals(
            "Dolomites Loop",
            importedRouteTitle(
                parsedTitle = "  Dolomites Loop  ",
                fallbackTitle = "imported route",
            ),
        )
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
        assertEquals("Saved route", route.title)
        assertEquals(1_000.0, route.summary.distanceMeters, 0.0)
        assertEquals(120.0, route.summary.elevationGainMeters, 0.0)
    }
}
