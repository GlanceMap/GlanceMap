package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.refuges.PoiSqlitePoint
import com.glancemap.trailcore.poi.PoiType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PhoneMapPoiTest {
    @Test
    fun managedPoiSourceCanBeRenamedAndDeleted() {
        val directory = Files.createTempDirectory("glancemap-phone-pois").toFile()
        try {
            val original = directory.resolve("alps.poi").apply { writeText("poi") }
            val repository = PhoneMapPoiRepository(directory)

            val renamed = runBlocking { repository.renameSource(original.name, "Alps 2026") }
            runBlocking { repository.deleteSource(renamed) }

            assertEquals("Alps 2026.poi", renamed)
            assertTrue(!original.exists())
            assertTrue(!directory.resolve(renamed).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun poiRenameRejectsFolderPaths() {
        phoneMapPoiFileName("folder/alps")
    }

    @Test
    fun storedPointBecomesStableSemanticMapPoi() {
        val poi =
            PoiSqlitePoint(
                sourceId = 42L,
                lat = 45.123,
                lon = 6.456,
                categoryName = "Water",
                tags = mapOf("name" to "Spring", "amenity" to "drinking_water"),
                rawData = "name=Spring\namenity=drinking_water",
            ).toPhoneMapPoi(sourceKey = "alps.poi")

        requireNotNull(poi)
        assertEquals("alps.poi#42", poi.id)
        assertEquals("alps.poi", poi.sourceId)
        assertEquals("Spring", poi.label)
        assertEquals(PoiType.WATER, poi.type)
        assertEquals(45.123, poi.location.latitude, 0.0)
        assertEquals(6.456, poi.location.longitude, 0.0)
    }

    @Test
    fun invalidStoredCoordinatesAreNotRendered() {
        val poi =
            PoiSqlitePoint(
                sourceId = 1L,
                lat = 91.0,
                lon = 6.456,
                categoryName = "Water",
                tags = emptyMap(),
            ).toPhoneMapPoi(sourceKey = "alps.poi")

        assertNull(poi)
    }

    @Test(expected = IllegalArgumentException::class)
    fun viewportRejectsInvertedBounds() {
        PhoneMapViewport(
            minLat = 46.0,
            maxLat = 45.0,
            minLon = 6.0,
            maxLon = 7.0,
            zoom = 14.0,
        )
    }

    @Test
    fun emptyPhonePoiDirectoryProducesNoViewportPois() {
        val directory = Files.createTempDirectory("glancemap-phone-pois").toFile()
        try {
            val pois =
                runBlocking {
                    PhoneMapPoiRepository(directory).queryViewport(
                        viewport =
                            PhoneMapViewport(
                                minLat = 45.0,
                                maxLat = 46.0,
                                minLon = 6.0,
                                maxLon = 7.0,
                                zoom = 14.0,
                            ),
                        limit = 180,
                    )
                }

            assertTrue(pois.isEmpty())
        } finally {
            directory.delete()
        }
    }

    @Test
    fun onlyEnabledReadablePoiFoldersAreIncludedInViewportQueries() {
        val enabled =
            listOf(
                PhoneMapPoiSource(fileName = "alps.poi", isReadable = true, isEnabled = true),
                PhoneMapPoiSource(fileName = "coast.poi", isReadable = true, isEnabled = false),
                PhoneMapPoiSource(fileName = "broken.poi", isReadable = false, isEnabled = true),
            ).enabledFileNames()

        assertEquals(setOf("alps.poi"), enabled)
    }
}
