package com.glancemap.glancemapcompanionapp.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneLibraryRouteSharingTest {
    @Test
    fun exportNamesAlwaysEndInGpxOnce() {
        assertEquals("Lakes loop.gpx", phoneLibraryRouteExportName("Lakes loop"))
        assertEquals("watch-track.GPX", phoneLibraryRouteExportName(" watch-track.GPX "))
        assertEquals("route.gpx", phoneLibraryRouteExportName("  "))
    }
}
