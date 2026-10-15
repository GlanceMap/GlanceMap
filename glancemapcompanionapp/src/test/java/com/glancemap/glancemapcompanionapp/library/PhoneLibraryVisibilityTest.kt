package com.glancemap.glancemapcompanionapp.library

import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxItem
import com.glancemap.glancemapcompanionapp.map.PhoneMapGpxTrack
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibrarySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneLibraryVisibilityTest {
    @Test
    fun managedAndFolderGpxUseTheMapSelectionByIdentity() {
        val first = track("one", enabled = true)
        val second = track("two", enabled = false)
        val items = listOf(first, second)
        val route = PhoneLibraryItem.Gpx(route("one"))
        assertEquals(PhoneLibraryVisibility(true, true, false), phoneLibraryVisibility(route, items, true, true))
        assertEquals(
            PhoneLibraryVisibility(false, true, false),
            phoneLibraryVisibility(PhoneLibraryItem.FolderGpx(second), items, true, true),
        )
        assertEquals(
            PhoneLibraryVisibility(true, true, true),
            phoneLibraryVisibility(route, items, false, true),
        )
        val missing = requireNotNull(phoneLibraryVisibility(route, emptyList(), true, true))
        assertFalse(missing.checked)
        assertFalse(missing.available)
    }

    @Test
    fun poiReadabilityAndLayerVisibilityPreserveTheCollectionSelection() {
        val collection = PhoneLibraryItem.Pois(PhoneMapPoiSource("spring.poi", isReadable = true, isEnabled = true))
        assertEquals(
            PhoneLibraryVisibility(true, true, false),
            phoneLibraryVisibility(collection, emptyList(), true, true),
        )
        assertEquals(
            PhoneLibraryVisibility(true, true, true),
            phoneLibraryVisibility(collection, emptyList(), true, false),
        )
        val missing = collection.copy(source = collection.source.copy(isReadable = false))
        val visibility = requireNotNull(phoneLibraryVisibility(missing, emptyList(), true, true))
        assertTrue(visibility.checked)
        assertFalse(visibility.available)
    }

    @Test
    fun receivedFilesHaveNoMapSwitchBeforeImport() {
        val received = PhoneLibraryItem.ReceivedGpx(File("watch.gpx"), 100L, 1L)
        assertNull(phoneLibraryVisibility(received, emptyList(), true, true))
    }

    @Test
    fun folderTracksJoinTheLibraryWithoutDuplicatingManagedRoutesOrChangingVisibility() {
        val managed = route("one")
        val loaded = listOf(track("one", true), track("folder:two", false))
        val items = phoneLibraryItems(listOf(managed), emptyList(), emptyList(), loaded)
        assertEquals(listOf("gpx/one", "gpx/folder:two"), items.map(PhoneLibraryItem::key))
        assertEquals(PhoneLibraryItem.Gpx(managed), items.first())
        assertEquals(PhoneLibraryItem.FolderGpx(loaded.last()), items.last())
        assertTrue(loaded.first().enabled)
        assertFalse(loaded.last().enabled)
        assertEquals(2, filterPhoneLibraryItems(items, PhoneLibraryFilter.GPX, "same title").size)
    }

    private fun track(
        id: String,
        enabled: Boolean,
    ) = PhoneMapGpxItem(id, "Same title", PhoneMapGpxTrack(id, emptyList()), enabled)

    private fun route(id: String) =
        RouteLibraryRoute(
            id,
            "Same title",
            "$id.gpx",
            1L,
            RouteLibrarySummary(1000.0, 100.0, 80.0, 1000.0, 0, 1000.0, 100.0),
        )
}
