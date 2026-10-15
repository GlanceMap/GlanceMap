package com.glancemap.glancemapcompanionapp.library

import androidx.compose.runtime.saveable.SaverScope
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneInstalledBundle
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleHealth
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleStatus
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorageLocation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.model.BoundingBox
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class PhoneLibraryOfflineDataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun tileCoverageHandlesBothHemispheresAndRejectsInvalidTileOrigins() {
        assertEquals(
            BoundingBox(-10.0, -123.0, -9.0, -122.0),
            phoneLibraryOfflineCoverage("S10W123.hgt.gz", PhoneLibraryOfflineSection.ELEVATION),
        )
        assertEquals(
            BoundingBox(-10.0, -125.0, -5.0, -120.0),
            phoneLibraryOfflineCoverage("W125_S10.rd5", PhoneLibraryOfflineSection.ROUTING),
        )
        listOf("N90E010.hgt", "N45E180.hgt", "arbitrary.hgt").forEach {
            assertNull(phoneLibraryOfflineCoverage(it, PhoneLibraryOfflineSection.ELEVATION))
        }
        assertNull(phoneLibraryOfflineCoverage("E6_N45.rd5", PhoneLibraryOfflineSection.ROUTING))
    }

    @Test fun inventoryIncludesStandaloneAndSharedFilesAndIgnoresTransferArtifacts() {
        val elevation = temporary.newFolder("elevation")
        val routing = temporary.newFolder("routing")
        val dem = File(elevation, "N45E010.hgt")
        RandomAccessFile(dem, "rw").use { it.setLength(1201L * 1201L * 2L) }
        File(elevation, ".N46E010.hgt").writeText("hidden")
        File(elevation, "N47E010.hgt.part").writeText("partial")
        val rd5 = File(routing, "E10_N45.rd5")
        rd5.writeBytes(
            ByteBuffer
                .allocate(200)
                .putShort(11.toShort())
                .putLong(192, 200L)
                .array(),
        )
        File(routing, "broken.rd5").writeText("invalid")
        val bundles = listOf(bundle("first"), bundle("second"))
        val items = discoverPhoneLibraryOfflineData(elevation, routing, bundles, PhoneOfflineStorageLocation.INTERNAL)
        assertEquals(3, items.size)
        assertTrue(items.first { it.file == dem }.isUsable)
        assertTrue(items.first { it.file == rd5 }.isUsable)
        assertFalse(items.first { it.title == "broken.rd5" }.isUsable)
        assertEquals(listOf("first", "second"), items.first { it.file == rd5 }.areaNames)
        assertTrue(dem.exists() && rd5.exists())
    }

    @Test fun movingAnItemChangesOnlyOrganizationAndItsIdentitySurvivesStorageMigration() {
        val item = asset("N45E010.hgt", PhoneLibraryOfflineSection.ELEVATION)
        val organization = PhoneLibraryOrganization().createFolder("trip", "Trip", null).move(item, "trip")
        assertEquals("trip", organization.entry(item).folderId)
        assertEquals(item.key, item.copy(file = File("sd/elevation/N45E010.hgt")).key)
        val browse = PhoneLibraryBrowseState("trip", offlineSection = PhoneLibraryOfflineSection.ELEVATION)
        assertEquals(
            listOf(item),
            browsePhoneLibraryItems(listOf(item), PhoneLibraryFilter.BUNDLES, browse, organization),
        )
        assertTrue(
            browsePhoneLibraryItems(
                listOf(item),
                PhoneLibraryFilter.BUNDLES,
                browse.copy(offlineSection = PhoneLibraryOfflineSection.ROUTING),
                organization,
            ).isEmpty(),
        )
    }

    @Test fun capabilityStatusDistinguishesNotIncludedMissingInvalidAndUnavailable() {
        val bundle = bundle("Alps").copy(demTileIds = emptyList())
        val health =
            PhoneOfflineBundleHealth(
                PhoneOfflineBundleStatus.PARTIAL,
                availableFileNames = listOf("map"),
                invalidFileNames = listOf("poi"),
                unavailableFileNames = listOf("E10_N45.rd5"),
            )
        val status = phoneLibraryBundleCapabilities(bundle, health).associate { it.label to it.status }
        assertEquals(R.string.library_offline_ready, status[R.string.map_tool_maps_title])
        assertEquals(R.string.library_offline_invalid, status[R.string.library_filter_pois])
        assertEquals(R.string.library_offline_unavailable, status[R.string.library_offline_routing])
        assertEquals(R.string.library_offline_not_included, status[R.string.library_offline_elevation])
        val missing = phoneLibraryBundleCapabilities(bundle, health.copy(unavailableFileNames = emptyList()))
        assertEquals(
            R.string.library_offline_missing,
            missing.first { it.label == R.string.library_offline_routing }.status,
        )
    }

    @Test fun standaloneDataProvidesTheSameOfflineCapabilitiesAsAreaDownloads() {
        val items =
            listOf(
                asset("N45E010.hgt", PhoneLibraryOfflineSection.ELEVATION),
                asset("E10_N45.rd5", PhoneLibraryOfflineSection.ROUTING),
            )
        val health =
            PhoneOfflineBundleHealth(PhoneOfflineBundleStatus.PARTIAL, availableFileNames = listOf("map", "poi"))
        assertTrue(
            phoneLibraryBundleCapabilities(bundle("Alps"), health, items)
                .all { it.status == R.string.library_offline_ready },
        )
        val capabilities = phoneLibraryBundleCapabilities(bundle("Alps"), health, items)
        val area = PhoneLibraryItem.Bundle(bundle("Alps"), health, null, capabilities)
        assertTrue(area.isOfflineReady())
        assertFalse(area.copy(health = health.copy(hasRecovery = true)).isOfflineReady())
        val invalid = phoneLibraryBundleCapabilities(bundle("Alps"), health, items.map { it.copy(isUsable = false) })
        assertEquals(
            R.string.library_offline_missing,
            invalid.first { it.label == R.string.library_offline_routing }.status,
        )
    }

    @Test fun offlineSectionRestoresAndOldSavedBrowseStateStillWorks() {
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any) = value is String
            }
        val browse = PhoneLibraryBrowseState(offlineSection = PhoneLibraryOfflineSection.ROUTING)
        val saved = with(phoneLibraryBrowseSaver) { scope.save(browse) }
        assertEquals(browse, phoneLibraryBrowseSaver.restore(requireNotNull(saved)))
        assertEquals(
            PhoneLibraryOfflineSection.AREAS,
            phoneLibraryBrowseSaver.restore(listOf("", "", "", ""))?.offlineSection,
        )
    }

    private fun bundle(name: String) =
        PhoneInstalledBundle(
            name,
            name,
            "Alps.map",
            "Alps.poi",
            routingFileNames = listOf("E10_N45.rd5"),
            demTileIds = listOf("N45E010"),
            installedAtMillis = 1L,
        )

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
