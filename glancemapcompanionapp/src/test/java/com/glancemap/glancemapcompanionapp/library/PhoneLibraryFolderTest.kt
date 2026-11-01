package com.glancemap.glancemapcompanionapp.library

import androidx.compose.ui.state.ToggleableState
import com.glancemap.glancemapcompanionapp.map.PhoneInstalledBundle
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxItem
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxTrack
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleHealth
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineBundleStatus
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

class PhoneLibraryFolderTest {
    private val track = PhoneLibraryItem.FolderGpx(gpx("route"))
    private val poi = PhoneLibraryItem.Pois(PhoneMapPoiSource("spring.poi", true))
    private val map = PhoneLibraryItem.OfflineMap(PhoneOfflineMap(File("alps.map"), contentRevision = "test"), 1L)

    @Test
    fun eachTabKeepsItsOwnFoldersAndNames() {
        val organization =
            PhoneLibraryOrganization()
                .createFolder("routes", "Trip", null, PhoneLibraryFilter.GPX)
                .createFolder("pois", "Trip", null, PhoneLibraryFilter.POIS)
        assertEquals(PhoneLibraryFilter.POIS, organization.folder("pois")?.category)
        assertThrows(IllegalArgumentException::class.java) {
            organization.createFolder("mixed", "Child", "routes", PhoneLibraryFilter.POIS)
        }
    }

    @Test
    fun renameKeepsPlacementAndRejectsASiblingsName() {
        val organization =
            PhoneLibraryOrganization()
                .createFolder("one", "One", null)
                .createFolder("two", "Two", null)
                .move(track, "one")
        val renamed = organization.renameFolder("one", " Trip ")
        assertEquals("Trip", renamed.folder("one")?.name)
        assertEquals("one", renamed.entry(track).folderId)
        assertEquals("one", organization.renameFolder("one", "ONE").entry(track).folderId)
        assertThrows(IllegalArgumentException::class.java) { organization.renameFolder("one", "two") }
        assertThrows(IllegalArgumentException::class.java) { organization.renameFolder("one", " ") }
    }

    @Test
    fun deleteRemovesSubfoldersAndLeavesOrganizationValid() {
        val other = PhoneLibraryItem.FolderGpx(gpx("other"))
        val organization =
            PhoneLibraryOrganization()
                .createFolder("trip", "Trip", null)
                .createFolder("day", "Day", "trip")
                .createFolder("kept", "Kept", null)
                .move(track, "day")
                .move(other, "kept")
                .classify(track, PhoneLibraryActivity.BIKE)
        val deleted = organization.deleteFolder("trip")
        assertEquals(listOf("kept"), deleted.folders.map(PhoneLibraryFolder::id))
        assertNull(deleted.entry(track).folderId)
        assertEquals(PhoneLibraryActivity.BIKE, deleted.entry(track).activity)
        assertEquals("kept", deleted.entry(other).folderId)
        assertEquals(deleted, validatePhoneLibraryOrganization(deleted))
    }

    @Test
    fun folderItemsIncludeSubfoldersAndOnlyTheFoldersOwnTab() {
        val organization =
            PhoneLibraryOrganization()
                .createFolder("trip", "Trip", null)
                .createFolder("day", "Day", "trip")
                .move(track, "day")
                .move(poi, "trip")
        assertEquals(listOf(track), organization.folderItems("trip", listOf(track, poi)))
    }

    @Test
    fun itemsLeftInLegacyFoldersStayVisibleOnTabsWithoutFolders() {
        val organization = PhoneLibraryOrganization().createFolder("trip", "Trip", null).move(map, "trip")
        assertEquals(
            listOf(map),
            browsePhoneLibraryItems(listOf(map), PhoneLibraryFilter.MAPS, PhoneLibraryBrowseState(), organization),
        )
    }

    @Test
    fun legacyFoldersMoveToTheTabOfWhatTheyHold() {
        val migrated =
            validatePhoneLibraryOrganization(
                PhoneLibraryOrganization(
                    folders =
                        listOf(
                            PhoneLibraryFolder("routes", "Routes", category = null),
                            PhoneLibraryFolder("places", "Places", category = null),
                            PhoneLibraryFolder("empty", "Empty", category = null),
                        ),
                    entries =
                        mapOf(
                            track.key to PhoneLibraryEntryOrganization("routes"),
                            poi.key to PhoneLibraryEntryOrganization("places"),
                        ),
                ),
            )
        assertEquals(PhoneLibraryFilter.GPX, migrated.folder("routes")?.category)
        assertEquals(PhoneLibraryFilter.POIS, migrated.folder("places")?.category)
        assertEquals(PhoneLibraryFilter.GPX, migrated.folder("empty")?.category)
        assertEquals("places", migrated.entry(poi).folderId)
    }

    @Test
    fun mixedLegacyFoldersSplitWithoutLosingPlacementAndMapsReturnToTheirTab() {
        val migrated =
            validatePhoneLibraryOrganization(
                PhoneLibraryOrganization(
                    folders =
                        listOf(
                            PhoneLibraryFolder("trip", "Trip", category = null),
                            PhoneLibraryFolder("day", "Day", "trip", category = null),
                        ),
                    entries =
                        mapOf(
                            track.key to PhoneLibraryEntryOrganization("trip"),
                            poi.key to PhoneLibraryEntryOrganization("day"),
                            map.key to PhoneLibraryEntryOrganization("day"),
                        ),
                ),
            )
        assertEquals("trip", migrated.entry(track).folderId)
        assertEquals("day-pois", migrated.entry(poi).folderId)
        assertEquals("trip-pois", migrated.folder("day-pois")?.parentId)
        assertEquals(PhoneLibraryFilter.POIS, migrated.folder("trip-pois")?.category)
        assertNull(migrated.entry(map).folderId)
        val reloaded = validatePhoneLibraryOrganization(migrated)
        assertEquals(migrated.folders.toSet(), reloaded.folders.toSet())
        assertEquals(migrated.entries, reloaded.entries)
    }

    @Test
    fun folderDeletionKeepsRegionPoisAndCountsDeviceFiles() {
        val bundle =
            PhoneLibraryItem.Bundle(
                PhoneInstalledBundle("alps", "Alps", "alps.map", "spring.poi", installedAtMillis = 1L),
                PhoneOfflineBundleHealth(PhoneOfflineBundleStatus.COMPLETE),
                null,
            )
        val deletion = phoneLibraryFolderDeletion(listOf(track, poi), phoneLibraryRegionPoiNames(listOf(bundle)))
        assertEquals(listOf<PhoneLibraryItem>(track), deletion.deleted)
        assertEquals(1, deletion.keptRegionPois)
        assertEquals(1, deletion.deviceFiles)
    }

    @Test
    fun failedDeletesAreReportedAndDoNotStopTheRest() {
        val attempted = mutableListOf<PhoneLibraryItem>()
        val failed =
            runBlocking {
                deletePhoneLibraryItems(listOf(track, poi)) { item ->
                    attempted += item
                    if (item == track) throw IOException("locked")
                }
            }
        assertEquals(listOf(track, poi), attempted)
        assertEquals(listOf<PhoneLibraryItem>(track), failed)
    }

    @Test
    fun folderToggleShowsAllSomeOrNoneAndHidesWhenEmpty() {
        assertNull(phoneLibraryFolderToggleState(emptyList()))
        assertEquals(ToggleableState.On, phoneLibraryFolderToggleState(listOf(true, true)))
        assertEquals(ToggleableState.Off, phoneLibraryFolderToggleState(listOf(false, false)))
        assertEquals(ToggleableState.Indeterminate, phoneLibraryFolderToggleState(listOf(true, false)))
    }

    private fun gpx(id: String) = PhoneMapGpxItem(id, id, PhoneMapGpxTrack(id, emptyList()), false)
}
