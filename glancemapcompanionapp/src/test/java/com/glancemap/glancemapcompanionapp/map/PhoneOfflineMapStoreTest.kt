package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneOfflineMapStoreTest {
    @Test
    fun discoveryOnlyReturnsReadableNonEmptyMapsforgeMapFiles() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            File(directory, "alps.map").writeText("map")
            File(directory, "empty.map").createNewFile()
            File(directory, "notes.txt").writeText("not a map")
            File(directory, "nested.map").mkdir()

            val maps = PhoneOfflineMapStore(directory).discover()

            assertEquals(listOf("alps.map"), maps.map(PhoneOfflineMap::displayName))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingMapIsRejectedBeforeMapsforgeIsOpened() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            val error = PhoneOfflineMapStore(directory).validate(PhoneOfflineMap(File(directory, "gone.map")))

            assertEquals(PhoneOfflineMapError.MISSING, error)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptMapIsReportedWithoutCrashingDiscovery() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            val corrupt = File(directory, "corrupt.map").apply { writeText("not a Mapsforge map") }

            val error = PhoneOfflineMapStore(directory).validate(PhoneOfflineMap(corrupt))

            assertEquals(PhoneOfflineMapError.INVALID, error)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sourceStateKeepsOnlineAndOfflineSelectionsDistinct() {
        val map = PhoneOfflineMap(File("/maps/alps.map"))

        assertEquals(MapMode.ONLINE, PhoneMapSource.Online.mode)
        assertEquals(MapMode.OFFLINE, PhoneMapSource.Offline(map).mode)
        assertEquals(map, PhoneMapSource.Offline(map).map)
    }

    @Test
    fun cameraSnapshotAcceptsPortableCameraValues() {
        val camera = PhoneMapCameraSnapshot(latitude = 45.5, longitude = 6.2, zoom = 12.0)

        assertEquals(45.5, camera.latitude, 0.0)
        assertEquals(6.2, camera.longitude, 0.0)
        assertEquals(12.0, camera.zoom, 0.0)
        assertFalse(isPhoneOfflineMapCandidate(File("/does/not/exist.map")))
        assertTrue(PhoneMapRendererCatalog.offline.isAvailable)
    }
}
