package com.glancemap.trailcore.oam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OamDownloadCatalogTest {
    @Test
    fun chinaAreasRetainTheirDedicatedMapAndPoiDirectories() {
        val chinaAreas = OamDownloadCatalog.areas.filter { it.continent == "China" }

        assertTrue(chinaAreas.isNotEmpty())
        assertTrue(chinaAreas.all { "/mapsV5/china/Ch-" in it.mapZipUrl })
        assertTrue(chinaAreas.all { "/pois/mapsforge/china/Ch-" in it.poiZipUrl })
        assertEquals(chinaAreas.map { it.id }.toSet().size, chinaAreas.size)
    }
}
