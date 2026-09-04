package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PhoneOfflineMapAvailabilityTest {
    @Test
    fun bundleMapRetainsItsAreaForAnElevationDownloadShortcut() {
        val map = PhoneOfflineMap(File("Bayern.map"))
        val availability =
            phoneOfflineMapAvailability(
                map = map,
                fallbackHasElevationData = false,
                bundles =
                    listOf(
                        PhoneInstalledBundle(
                            areaId = "germany-bayern",
                            areaLabel = "Bayern",
                            mapFileName = map.displayName,
                            poiFileName = "Bayern.poi",
                            installedAtMillis = 1L,
                        ),
                    ),
                healthByAreaId = emptyMap(),
            )

        assertEquals("germany-bayern", availability.bundleAreaId)
        assertFalse(availability.hasElevationData)
    }

    @Test
    fun importedMapWithoutBundleMetadataHasNoElevationDownloadShortcut() {
        val availability =
            phoneOfflineMapAvailability(
                map = PhoneOfflineMap(File("local.map")),
                fallbackHasElevationData = true,
                bundles = emptyList(),
                healthByAreaId = emptyMap(),
            )

        assertNull(availability.bundleAreaId)
    }
}
