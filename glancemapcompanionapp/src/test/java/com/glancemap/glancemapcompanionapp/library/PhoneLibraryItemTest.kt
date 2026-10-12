package com.glancemap.glancemapcompanionapp.library

import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibrarySummary
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhoneLibraryItemTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun categoryAndSearchFiltersKeepRoutesRecordingsAndCollectionsSeparate() {
        val items =
            listOf(
                PhoneLibraryItem.Gpx(route("route", "Alpine loop")),
                PhoneLibraryItem.Gpx(route("recording", "ALPINE day", recorded = true)),
                PhoneLibraryItem.Pois(PhoneMapPoiSource("alpine.poi", isReadable = true)),
            )
        val recordings = filterPhoneLibraryItems(items, PhoneLibraryFilter.RECORDED, " alpine ")
        assertEquals(listOf("recording"), recordings.map { (it as PhoneLibraryItem.Gpx).route.id })
        assertEquals(3, filterPhoneLibraryItems(items, PhoneLibraryFilter.ALL, "ALPINE").size)
        assertTrue(filterPhoneLibraryItems(items, PhoneLibraryFilter.OFFLINE, "").isEmpty())
        assertTrue(filterPhoneLibraryItems(items, PhoneLibraryFilter.ROUTES, "missing").isEmpty())
    }

    @Test
    fun receivedGpxAppearsOnceAfterItIsImportedIntoTheManagedLibrary() {
        val received = PhoneLibraryItem.ReceivedGpx(File("watch.gpx"), 100L, 1L)
        val imported = route("recording", "My hike", recorded = true).copy(sourceExportId = received.exportId)
        val result = phoneLibraryItems(listOf(imported), listOf(received), emptyList())
        assertEquals(listOf(PhoneLibraryItem.Gpx(imported)), result)
        assertEquals(PhoneLibraryFilter.RECORDED, result.single().filter)
    }

    @Test
    fun newReceivedCopyWithTheSameNameIsNotHiddenByAnOlderImport() {
        val oldCopy = PhoneLibraryItem.ReceivedGpx(File("watch.gpx"), 100L, 1L)
        val newCopy = PhoneLibraryItem.ReceivedGpx(File("watch.gpx"), 100L, 2L)
        val imported = route("recording", "Old hike", recorded = true).copy(sourceExportId = oldCopy.exportId)
        val result = phoneLibraryItems(listOf(imported), listOf(newCopy), emptyList())
        assertTrue(result.contains(newCopy))
    }

    @Test
    fun receivedDiscoveryIgnoresPartialHiddenAndNonGpxFiles() {
        val directory = temporaryFolder.newFolder()
        File(directory, "complete.GPX").writeText("fixture")
        File(directory, ".transfer.gpx").writeText("partial")
        File(directory, "transfer.part").writeText("partial")
        File(directory, "notes.txt").writeText("not GPX")
        File(directory, "directory.gpx").mkdir()
        assertEquals(listOf("complete.GPX"), receivedPhoneLibraryFiles(directory).map { it.title })
        assertTrue(receivedPhoneLibraryFiles(File(directory, "missing")).isEmpty())
    }

    @Test
    fun legacyRouteIndexesRemainRoutesAndRecordingMetadataSurvivesRename() {
        val gson = Gson()
        val oldJson =
            """
            {"id":"old","displayName":"Legacy","storedFileName":"old.gpx","importedAtMillis":1,"summary":{}}
            """.trimIndent()
        val legacy = gson.fromJson(oldJson, RouteLibraryRoute::class.java)
        assertFalse(legacy.isRecording)
        assertNull(legacy.sourceExportId)
        val recorded = route("new", "Recording", recorded = true).copy(sourceExportId = "watch.gpx")
        val restored = gson.fromJson(gson.toJson(recorded.copy(displayName = "Renamed")), RouteLibraryRoute::class.java)
        assertTrue(restored.isRecording)
        assertEquals("watch.gpx", restored.sourceExportId)
        assertEquals("Renamed", restored.displayName)
    }

    private fun route(
        id: String,
        title: String,
        recorded: Boolean = false,
    ) = RouteLibraryRoute(
        id = id,
        displayName = title,
        storedFileName = "$id.gpx",
        importedAtMillis = 1L,
        summary = RouteLibrarySummary(1000.0, 100.0, 80.0, 1000.0, 0, 1000.0, 100.0),
        isRecording = recorded,
    )
}
