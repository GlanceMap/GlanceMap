package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.oam.OamDownloadArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOfflineBundleRefreshTest {
    @Test
    fun remoteMetadataComparisonPrefersStableContentLength() {
        val previous = metadata(entityTag = "old", modified = 1L, size = 100L)
        val current = metadata(entityTag = "new", modified = 2L, size = 100L)

        assertEquals(PhoneOfflineRemoteMetadataComparison.SAME, previous.compareWith(current))
    }

    @Test
    fun changedBundleFilesMapToRefreshForces() {
        val bundle =
            PhoneInstalledBundle(
                areaId = "area",
                areaLabel = "Area",
                mapFileName = "Area.map",
                poiFileName = "Area.poi",
                routingFileNames = listOf("E5_N45.rd5"),
                demTileIds = listOf("N45E006", "N45E007"),
                installedAtMillis = 1L,
            )
        val check =
            PhoneOfflineBundleUpdateCheck(
                bundle = bundle,
                status = PhoneOfflineBundleUpdateStatus.UPDATE_AVAILABLE,
                checkedFileCount = 4,
                changedFileNames = listOf("Area.Poi.zip", "E5_N45.rd5", "N45E007.hgt.zip"),
            )

        val forces = check.refreshForces(area())

        assertEquals(false, forces.forceMap)
        assertEquals(true, forces.forcePoi)
        assertEquals(true, forces.forceRouting)
        assertEquals(setOf("N45E007"), forces.forceDemTileIds)
    }

    @Test
    fun unknownMetadataRefreshesAllInstalledFamilies() {
        val bundle =
            PhoneInstalledBundle(
                areaId = "area",
                areaLabel = "Area",
                mapFileName = "Area.map",
                poiFileName = "Area.poi",
                routingFileNames = listOf("E5_N45.rd5"),
                demTileIds = listOf("N45E006"),
                installedAtMillis = 1L,
            )
        val forces =
            PhoneOfflineBundleUpdateCheck(
                bundle = bundle,
                status = PhoneOfflineBundleUpdateStatus.UNKNOWN,
                checkedFileCount = 0,
            ).refreshForces(area())

        assertTrue(forces.forceMap)
        assertTrue(forces.forcePoi)
        assertTrue(forces.forceRouting)
        assertEquals(setOf("N45E006"), forces.forceDemTileIds)
    }

    @Test
    fun updateRequestsCoverEveryInstalledRemoteFileFamily() {
        val bundle =
            PhoneInstalledBundle(
                areaId = "area",
                areaLabel = "Area",
                mapFileName = "Area.map",
                poiFileName = "Area.poi",
                routingFileNames = listOf("E5_N45.rd5"),
                demTileIds = listOf("N45E006"),
                installedAtMillis = 1L,
            )

        val requests = buildPhoneOfflineRemoteFileRequestsForBundle(area(), bundle)

        assertEquals(
            setOf("Area.zip", "Area.Poi.zip", "E5_N45.rd5", "N45E006.hgt.zip"),
            requests.map { it.fileName }.toSet(),
        )
    }

    private fun metadata(
        entityTag: String?,
        modified: Long?,
        size: Long?,
    ): PhoneOfflineRemoteFileMetadata =
        PhoneOfflineRemoteFileMetadata(
            url = "https://example.test/file",
            fileName = "file",
            entityTag = entityTag,
            lastModifiedMillis = modified,
            contentLengthBytes = size,
        )

    private fun area(): OamDownloadArea =
        OamDownloadArea(
            id = "area",
            continent = "Europe",
            region = "Area",
            mapSizeLabel = "1 MB",
            mapSizeBytes = 1L,
            poiSizeLabel = "1 MB",
            poiSizeBytes = 1L,
            notes = "",
            contourLabel = "",
            mapZipUrl = "https://example.test/Area.zip",
            poiZipUrl = "https://example.test/Area.Poi.zip",
        )
}
