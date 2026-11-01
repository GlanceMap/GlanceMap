package com.glancemap.glancemapcompanionapp.library

import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PhoneLibraryRowStatusTest {
    private val poi = PhoneLibraryItem.Pois(PhoneMapPoiSource("spring.poi", true))

    @Test
    fun visibleItemsShowNoStatusWhetherOnOrOff() {
        listOf(true, false).forEach { checked ->
            val visibility = PhoneLibraryVisibility(checked, available = true, hiddenByLayers = false)
            assertNull(libraryItemStatus(poi, visibility))
        }
    }

    @Test
    fun itemsExplainWhyTheyCannotShowOnTheMap() {
        assertEquals(
            R.string.library_hidden_by_layers,
            libraryItemStatus(poi, PhoneLibraryVisibility(true, available = true, hiddenByLayers = true)),
        )
        assertEquals(
            R.string.library_unavailable_on_map,
            libraryItemStatus(poi, PhoneLibraryVisibility(false, available = false, hiddenByLayers = false)),
        )
    }

    @Test
    fun receivedGpxStaysQuietWhileOfflineMapsKeepTheirStatus() {
        assertNull(libraryItemStatus(PhoneLibraryItem.ReceivedGpx(File("watch.gpx"), 1L, 1L), null))
        val map = PhoneOfflineMap(File("alps.map"), contentRevision = "test")
        assertEquals(R.string.library_on_phone, libraryItemStatus(PhoneLibraryItem.OfflineMap(map, 1L), null))
    }
}
