package com.glancemap.glancemapcompanionapp.routes

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
}
