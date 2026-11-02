package com.glancemap.glancemapcompanionapp.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRouteImportTest {
    @Test
    fun theSameContentFindsTheSameRouteWhateverTheChecksumCase() {
        val route = route("one", watchRouteExportId("ABC123"))
        assertEquals(route, listOf(route).findWatchRoute("abc123"))
        assertNull(listOf(route).findWatchRoute("def456"))
    }

    @Test
    fun routesWithAnyExportIdCameFromTheWatch() {
        assertTrue(route("synced", watchRouteExportId("abc")).isFromWatch)
        assertTrue(route("manual", "tour.gpx:10:1").isFromWatch)
        assertFalse(route("imported", null).isFromWatch)
    }

    private fun route(
        id: String,
        exportId: String?,
    ) = RouteLibraryRoute(
        id = id,
        displayName = id,
        storedFileName = "$id.gpx",
        importedAtMillis = 1L,
        summary = RouteLibrarySummary(1000.0, 10.0, 10.0, 600.0, 0, 1000.0, 10.0),
        sourceExportId = exportId,
    )
}
