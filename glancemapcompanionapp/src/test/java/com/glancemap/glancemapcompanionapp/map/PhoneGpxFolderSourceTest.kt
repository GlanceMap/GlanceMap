package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PhoneGpxFolderSourceTest {
    @Test
    fun directGpxCandidatesAcceptGpxSuffixOnly() {
        assertTrue(isPhoneGpxFolderFileName("route.gpx"))
        assertTrue(isPhoneGpxFolderFileName("route.GPX"))
        assertFalse(isPhoneGpxFolderFileName("route.gpx.zip"))
        assertFalse(isPhoneGpxFolderFileName("~route.gpx"))
        assertFalse(isPhoneGpxFolderFileName("route.part.gpx"))
        assertFalse(isPhoneGpxFolderFileName("route.tmp.gpx"))
        assertFalse(isPhoneGpxFolderFileName("route.gpx~"))
        assertFalse(isPhoneGpxFolderFileName("route.kml"))
        assertNull(phoneGpxFolderFile("route.gpx", isFile = false, documentUri = "content://nested"))
    }

    @Test
    fun gpxRenameNameKeepsExtensionAndRejectsFolderPaths() {
        assertEquals("Alps.gpx", phoneGpxFolderFileName("Alps"))
        assertEquals("Alps.gpx", phoneGpxFolderFileName("Alps.GPX"))
        assertFalse(runCatching { phoneGpxFolderFileName("folder/Alps") }.isSuccess)
    }

    @Test
    fun documentIdentityKeepsSameNamedFilesDistinctAndCannotCollideWithRouteLibrary() {
        val first = folderFile("content://provider/document/one", "route.gpx")
        val second = folderFile("content://provider/document/two", "route.gpx")
        val routeLibrary = PhoneMapGpxSource(id = "route-1", displayName = "Route Library route")

        assertNotEquals(first.id, second.id)
        assertNotEquals(routeLibrary.id, first.id)
        assertEquals(listOf(first, second), listOf(first, second, first).normalizedPhoneGpxFolderFiles())
    }

    @Test
    fun rescanPreservesKnownItemsAndRemovingOrClearingFolderItemsLeavesRouteLibraryItems() {
        val route = item(id = "route-1", enabled = true)
        val firstFolder = item(id = phoneGpxFolderSourceId("content://provider/document/one"), enabled = true)
        val secondFolder = item(id = phoneGpxFolderSourceId("content://provider/document/two"), enabled = false)

        val rescan = mergePhoneMapGpxItems(listOf(route, firstFolder, secondFolder), listOf(route, firstFolder), null)
        val afterClear = mergePhoneMapGpxItems(rescan, listOf(route), null)

        assertEquals(listOf(route.id, firstFolder.id), rescan.map(PhoneMapGpxItem::id))
        assertTrue(rescan.single { it.id == firstFolder.id }.enabled)
        assertEquals(listOf(route.id), afterClear.map(PhoneMapGpxItem::id))
        assertTrue(afterClear.single().enabled)
    }

    @Test
    fun malformedOrUnreadableFolderDocumentDoesNotPreventOtherTracksLoading() {
        val good = folderFile("content://provider/document/good", "good.gpx")
        val malformed = folderFile("content://provider/document/bad", "bad.gpx")
        val unreadable = folderFile("content://provider/document/unreadable", "unreadable.gpx")

        val loaded =
            listOf(good, malformed, unreadable).mapNotNull { source ->
                phoneGpxFolderTrackItem(source) { file ->
                    when (file) {
                        good -> ByteArrayInputStream(validGpx.toByteArray())
                        malformed -> ByteArrayInputStream("not a GPX".toByteArray())
                        unreadable -> null
                        else -> null
                    }
                }
            }

        assertEquals(listOf(good.id), loaded.map(PhoneMapGpxItem::id))
        assertEquals("GPX title", loaded.single().displayName)
        assertFalse(loaded.single().isEditable)
    }

    private fun folderFile(
        uri: String,
        name: String,
    ): PhoneGpxFolderFile = requireNotNull(phoneGpxFolderFile(name = name, isFile = true, documentUri = uri))

    private fun item(
        id: String,
        enabled: Boolean,
    ): PhoneMapGpxItem =
        PhoneMapGpxItem(
            id = id,
            displayName = id,
            track = PhoneMapGpxTrack(id = id, points = emptyList()),
            enabled = enabled,
        )

    private companion object {
        val validGpx =
            """
            <gpx version="1.1">
              <trk><name>GPX title</name><trkseg>
                <trkpt lat="46.0" lon="11.0" />
                <trkpt lat="46.1" lon="11.1" />
              </trkseg></trk>
            </gpx>
            """.trimIndent()
    }
}
