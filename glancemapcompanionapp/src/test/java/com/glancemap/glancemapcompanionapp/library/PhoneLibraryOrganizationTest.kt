package com.glancemap.glancemapcompanionapp.library

import androidx.compose.runtime.saveable.SaverScope
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRoute
import com.glancemap.glancemapcompanionapp.routes.RouteLibrarySummary
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneLibraryOrganizationTest {
    @Test
    fun foldersAndSubfoldersKeepUniqueNamesWithinTheirParent() {
        val organization =
            PhoneLibraryOrganization()
                .createFolder("alps", " Alps ", null)
                .createFolder("day", "Day 1", "alps")
                .createFolder("root-day", "Day 1", null)
        assertEquals("Alps / Day 1", organization.folderPath("day"))
        assertEquals("Day 1", organization.folderPath("root-day"))
        assertThrows(IllegalArgumentException::class.java) { organization.createFolder("bad", "ALPS", null) }
        assertThrows(IllegalArgumentException::class.java) { organization.createFolder("bad", " ", null) }
        assertThrows(IllegalArgumentException::class.java) { organization.createFolder("bad", "A", "missing") }
    }

    @Test
    fun movingEntriesAndClassifyingActivitiesOnlyChangeLibraryOrganization() {
        val item = route("one", "Forest route")
        val folder = PhoneLibraryOrganization().createFolder("folder", "Alps", null)
        val grouped = folder.move(item, "folder").classify(item, PhoneLibraryActivity.HIKE)
        assertEquals("folder", grouped.entry(item).folderId)
        assertEquals(PhoneLibraryActivity.HIKE, grouped.entry(item).activity)
        assertEquals(item, route("one", "Forest route"))
        assertFalse(item.route.isRecording)
        assertEquals(null, grouped.move(item, null).entry(item).folderId)
        assertEquals(PhoneLibraryActivity.HIKE, grouped.move(item, null).entry(item).activity)
        assertThrows(IllegalArgumentException::class.java) { grouped.move(item, "missing") }
    }

    @Test
    fun typeActivityAndFolderFiltersCombineAndSearchIncludesSubfolders() {
        val hike = route("hike", "Forest route")
        val bike = route("bike", "Forest recording", recorded = true)
        val organization =
            PhoneLibraryOrganization()
                .createFolder("trip", "Trip", null)
                .createFolder("day", "Day", "trip")
                .move(hike, "trip")
                .classify(hike, PhoneLibraryActivity.HIKE)
                .move(bike, "day")
                .classify(bike, PhoneLibraryActivity.BIKE)
        val items = listOf(hike, bike)
        val direct = PhoneLibraryBrowseState(folderId = "trip")
        assertEquals(listOf(hike), browsePhoneLibraryItems(items, PhoneLibraryFilter.GPX, direct, organization))
        assertEquals(
            items,
            browsePhoneLibraryItems(items, PhoneLibraryFilter.GPX, direct.copy(query = "forest"), organization),
        )
        val recordings =
            direct.copy(query = "forest", kind = PhoneLibraryGpxKind.RECORDING, activity = PhoneLibraryActivity.BIKE)
        assertEquals(listOf(bike), browsePhoneLibraryItems(items, PhoneLibraryFilter.GPX, recordings, organization))
        assertTrue(
            browsePhoneLibraryItems(
                items,
                PhoneLibraryFilter.GPX,
                recordings.copy(activity = PhoneLibraryActivity.HIKE),
                organization,
            ).isEmpty(),
        )
        assertTrue(organization.containsFolder("trip", "day"))
        assertFalse(organization.containsFolder("day", "trip"))
    }

    @Test
    fun receivedGpxKeepsItsFolderAndActivityAfterImport() {
        val received = PhoneLibraryItem.ReceivedGpx(File("watch.gpx"), 100L, 1L)
        val imported = route("imported", "Recorded hike", recorded = true)
        val managed = imported.copy(route = imported.route.copy(sourceExportId = received.exportId))
        val organization =
            PhoneLibraryOrganization()
                .createFolder("trip", "Trip", null)
                .move(received, "trip")
                .classify(received, PhoneLibraryActivity.BIKE)
        assertEquals(received.organizationKey(), managed.organizationKey())
        assertEquals(organization.entry(received), organization.entry(managed))
        assertEquals(PhoneLibraryGpxKind.RECORDING, managed.gpxKind())
    }

    @Test
    fun serializedOrganizationRestoresAndInvalidHierarchyIsRejected() {
        val organization =
            PhoneLibraryOrganization()
                .createFolder("trip", "Trip", null)
                .createFolder("day", "Day", "trip")
                .move(route("one", "Route"), "day")
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(organization), PhoneLibraryOrganization::class.java)
        assertEquals(organization, validatePhoneLibraryOrganization(restored))
        val cycle =
            PhoneLibraryOrganization(
                listOf(PhoneLibraryFolder("one", "One", "two"), PhoneLibraryFolder("two", "Two", "one")),
            )
        assertThrows(IllegalArgumentException::class.java) { validatePhoneLibraryOrganization(cycle) }
        val empty = gson.fromJson("{}", PhoneLibraryOrganization::class.java)
        assertEquals(PhoneLibraryOrganization(), validatePhoneLibraryOrganization(empty))
    }

    @Test
    fun folderSearchAndTypeActivityFiltersSurviveRecreation() {
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any): Boolean = value is String
            }
        val browse = PhoneLibraryBrowseState("day", "ridge", PhoneLibraryGpxKind.RECORDING, PhoneLibraryActivity.BIKE)
        val saved = with(phoneLibraryBrowseSaver) { scope.save(browse) }
        assertEquals(browse, phoneLibraryBrowseSaver.restore(requireNotNull(saved)))
    }

    @Test
    fun damagedRequiredFieldsAreRejectedWithoutDiscardingFolderAssignments() {
        val gson = Gson()
        listOf(
            """{"folders":[{"name":"Missing ID"}]}""",
            """{"folders":[null]}""",
            """{"entries":{"gpx/one":null}}""",
        ).forEach { json ->
            val decoded = gson.fromJson(json, PhoneLibraryOrganization::class.java)
            assertThrows(IllegalArgumentException::class.java) { validatePhoneLibraryOrganization(decoded) }
        }
    }

    private fun route(
        id: String,
        title: String,
        recorded: Boolean = false,
    ): PhoneLibraryItem.Gpx =
        PhoneLibraryItem.Gpx(
            RouteLibraryRoute(
                id,
                title,
                "$id.gpx",
                1L,
                RouteLibrarySummary(1000.0, 100.0, 80.0, 1000.0, 0, 1000.0, 100.0),
                isRecording = recorded,
            ),
        )
}
