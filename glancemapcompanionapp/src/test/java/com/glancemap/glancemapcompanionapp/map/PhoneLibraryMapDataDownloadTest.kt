package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.oam.OamDownloadCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneLibraryMapDataDownloadTest {
    @Test
    fun importedMapCanRequestOnlyElevationAndKeepsItsLocalFilename() {
        val map = PhoneOfflineMap(File("/maps/Custom Alps.map"))
        val selection = phoneMapDataSelection(map, null, includeElevation = true, includeRouting = false, PhoneOfflineDemSource.DEFAULT)
        assertEquals("Custom Alps.map", selection.localMapFileName)
        assertFalse(selection.includeMap)
        assertFalse(selection.includePoi)
        assertTrue(selection.includeDem)
        assertFalse(selection.includeRouting)
        assertTrue(selection.area.id.startsWith("local-map-"))
        assertEquals(selection.area, phoneLocalMapArea(selection.area.id, selection.localMapFileName!!))
    }

    @Test
    fun aKnownBundleKeepsItsExistingAreaWhenDownloadingRouting() {
        val area = OamDownloadCatalog.areas.first()
        val map = PhoneOfflineMap(File("/maps/renamed.map"))
        val bundle = PhoneInstalledBundle(area.id, area.region, "renamed.map", "old.poi", installedAtMillis = 1)
        val selection = phoneMapDataSelection(map, bundle, includeElevation = false, includeRouting = true, PhoneOfflineDemSource.DEFAULT)
        assertEquals(area, selection.area)
        assertEquals("renamed.map", selection.localMapFileName)
        assertFalse(selection.includeMap)
        assertFalse(selection.includePoi)
        assertFalse(selection.includeDem)
        assertTrue(selection.includeRouting)
    }

    @Test
    fun localMapAreaHasNoRemoteMapOrPoiRequests() {
        val area = phoneLocalMapArea("local-map-123", "my.map")
        val bundle = PhoneInstalledBundle(area.id, area.region, "my.map", "", installedAtMillis = 1)
        assertTrue(buildPhoneOfflineRemoteFileRequestsForBundle(area, bundle).isEmpty())
    }
}
