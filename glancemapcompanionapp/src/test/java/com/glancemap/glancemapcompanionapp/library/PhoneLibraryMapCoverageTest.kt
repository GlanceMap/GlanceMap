package com.glancemap.glancemapcompanionapp.library

import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageLocation
import org.junit.Assert.*
import org.junit.Test
import org.mapsforge.core.model.BoundingBox
import java.io.File

class PhoneLibraryMapCoverageTest {
    @Test fun separatelyImportedTilesProvideCoverageOnlyForTheirMapArea() {
        val data =
            listOf(
                asset("N45E010.hgt", PhoneLibraryOfflineSection.ELEVATION),
                asset("E10_N45.rd5", PhoneLibraryOfflineSection.ROUTING),
            )
        val covered = phoneLibraryMapCoverage(BoundingBox(45.0, 10.0, 46.0, 11.0), data)
        assertEquals(1, covered.requiredElevationTiles)
        assertEquals(1, covered.availableElevationTiles)
        assertEquals(1, covered.requiredRoutingFiles)
        assertEquals(1, covered.availableRoutingFiles)
        val elsewhere = phoneLibraryMapCoverage(BoundingBox(55.0, 10.0, 56.0, 11.0), data)
        assertEquals(0, elsewhere.availableElevationTiles)
        assertEquals(0, elsewhere.availableRoutingFiles)
    }

    @Test fun partialCoverageDoesNotCountDuplicatesOrInvalidTilesAsReady() {
        val dem = asset("N45E010.hgt", PhoneLibraryOfflineSection.ELEVATION)
        val data =
            listOf(
                dem,
                dem.copy(relativePath = "detailed/N45E010.hgt"),
                asset("N46E010.hgt", PhoneLibraryOfflineSection.ELEVATION),
                asset("N45E011.hgt", PhoneLibraryOfflineSection.ELEVATION).copy(isUsable = false),
            )
        val coverage = phoneLibraryMapCoverage(BoundingBox(45.0, 10.0, 47.0, 12.0), data)
        assertEquals(4, coverage.requiredElevationTiles)
        assertEquals(2, coverage.availableElevationTiles)
        assertEquals(0, coverage.availableRoutingFiles)
    }

    @Test fun unknownOrUnreadableMapBoundsDoNotClaimAvailableCoverage() {
        assertNull(readLibraryMapBounds(PhoneOfflineMap(File("missing.map"))))
        val coverage =
            phoneLibraryMapCoverage(
                null,
                listOf(asset("N45E010.hgt", PhoneLibraryOfflineSection.ELEVATION)),
            )
        assertNull(coverage.requiredElevationTiles)
        assertNull(coverage.requiredRoutingFiles)
    }

    @Test fun replacingAMapDoesNotReuseTheOldCoverage() {
        val before = PhoneOfflineMap(File("local.map"), "old")
        val coverage = PhoneLibraryMapCoverage(BoundingBox(45.0, 10.0, 46.0, 11.0), 1, 1, 1, 1)
        val stored = listOf(PhoneLibraryItem.OfflineMap(before, 100L, coverage))
        assertEquals(coverage, libraryOfflineMapItems(listOf(before), stored).single().coverage)
        assertNull(libraryOfflineMapItems(listOf(before.copy(contentRevision = "new")), stored).single().coverage)
    }

    private fun asset(
        name: String,
        section: PhoneLibraryOfflineSection,
    ) = PhoneLibraryItem.OfflineData(
        File(name),
        name,
        section,
        1L,
        1L,
        true,
        phoneLibraryOfflineCoverage(name, section),
        emptyList(),
        PhoneOfflineStorageLocation.INTERNAL,
    )
}
