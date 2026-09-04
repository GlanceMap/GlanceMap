package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.oam.OamDownloadArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneOfflineBundleLocalAssetsTest {
    @Test
    fun `recovery map name is reused when completed metadata is absent`() {
        val resolved =
            resolvePhoneOfflineBundleLocalAsset(
                candidateFileNames =
                    phoneOfflineBundleMapCandidateFileNames(
                        area = area(),
                        completedFileName = null,
                        recoveryFileName = "Bayern_oam.osm.map",
                    ),
                findValidAsset = { fileName -> fileName.takeIf { it == "Bayern_oam.osm.map" } },
            )

        assertEquals("Bayern_oam.osm.map", resolved)
    }

    @Test
    fun `invalid recovery map falls through to the expected OAM map name`() {
        val resolved =
            resolvePhoneOfflineBundleLocalAsset(
                candidateFileNames =
                    phoneOfflineBundleMapCandidateFileNames(
                        area = area(),
                        completedFileName = null,
                        recoveryFileName = "corrupt.map",
                    ),
                findValidAsset = { fileName -> fileName.takeIf { it == "Bayern_oam.osm.map" } },
            )

        assertEquals("Bayern_oam.osm.map", resolved)
    }

    @Test
    fun `expected POI name is discovered without completed or recovery metadata`() {
        val resolved =
            resolvePhoneOfflineBundleLocalAsset(
                candidateFileNames =
                    phoneOfflineBundlePoiCandidateFileNames(
                        area = area(),
                        completedFileName = null,
                        recoveryFileName = null,
                    ),
                findValidAsset = { fileName -> fileName.takeIf { it == "Bayern.poi" } },
            )

        assertEquals("Bayern.poi", resolved)
    }

    @Test
    fun `no validated candidate is never reused`() {
        val resolved =
            resolvePhoneOfflineBundleLocalAsset(
                candidateFileNames = listOf("../corrupt.map", "Bayern_oam.osm.map"),
                findValidAsset = { null },
            )

        assertNull(resolved)
    }

    private fun area(): OamDownloadArea =
        OamDownloadArea(
            id = "germany-bayern",
            continent = "Germany",
            region = "Bayern",
            mapSizeLabel = "1 MB",
            mapSizeBytes = 1L,
            poiSizeLabel = "1 MB",
            poiSizeBytes = 1L,
            notes = "",
            contourLabel = "",
            mapZipUrl = "https://example.test/Bayern.zip",
            poiZipUrl = "https://example.test/Bayern.Poi.zip",
        )
}
