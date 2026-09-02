package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.map.MapMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class PhoneOfflineMapStoreTest {
    @Test
    fun managedMapCanBeRenamedAndDeletedWithoutLeavingItsPartialFile() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            val original = File(directory, "alps.map").apply { writeText("map") }
            File(directory, ".alps.map.part").writeText("partial")
            val store = PhoneOfflineMapStore(directory)

            val renamed = store.rename(PhoneOfflineMap(original), "Alps 2026")
            store.delete(renamed)

            assertEquals("Alps 2026.map", renamed.displayName)
            assertFalse(original.exists())
            assertFalse(File(directory, "Alps 2026.map").exists())
            assertFalse(File(directory, ".alps.map.part").exists())
            assertFalse(File(directory, ".Alps 2026.map.part").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun mapRenameRejectsFolderPaths() {
        phoneOfflineMapFileName("folder/alps")
    }

    @Test
    fun discoveryOnlyReturnsReadableNonEmptyMapsforgeMapFiles() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            File(directory, "alps.map").writeText("map")
            File(directory, "alps-upper.MAP").writeText("map")
            File(directory, "empty.map").createNewFile()
            File(directory, "temporary.map.part").writeText("partial map")
            File(directory, "notes.txt").writeText("not a map")
            File(directory, "nested.map").mkdir()

            val maps = PhoneOfflineMapStore(directory).discover()

            assertEquals(listOf("alps-upper.MAP", "alps.map"), maps.map(PhoneOfflineMap::displayName))
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
    fun temporaryImportValidationDoesNotRequireAMapFileSuffix() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            var validatedName: String? = null
            val store =
                PhoneOfflineMapStore(directory) { candidate ->
                    validatedName = candidate.name
                    PhoneOfflineMapValidation()
                }

            val result = store.import("example.map", "valid Mapsforge data".byteInputStream())

            assertEquals("example.map.part", validatedName)
            assertEquals(
                "example.map",
                (result as PhoneOfflineMapImportResult.Success).map.displayName,
            )
            assertTrue(File(directory, "example.map").isFile)
            assertFalse(File(directory, "example.map.part").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedImportValidationLeavesNoPartialOrFinalMap() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            val store =
                PhoneOfflineMapStore(directory) {
                    PhoneOfflineMapValidation(error = PhoneOfflineMapError.INVALID)
                }

            val result = store.import("example.map", "corrupt".byteInputStream())

            assertEquals(
                PhoneOfflineMapError.INVALID,
                (result as PhoneOfflineMapImportResult.Failure).error,
            )
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun copyAndMapfileFailuresKeepTheirActualDiagnosticStages() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            val copyFailure =
                PhoneOfflineMapStore(directory).import(
                    "copy.map",
                    object : InputStream() {
                        private var copiedChunk = false

                        override fun read(): Int = -1

                        override fun read(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int =
                            if (!copiedChunk) {
                                buffer[offset] = 1
                                buffer[offset + 1] = 2
                                copiedChunk = true
                                2
                            } else {
                                throw IOException("copy interrupted")
                            }
                    },
                )

            assertEquals(
                PhoneOfflineMapError.COPY_FAILED,
                (copyFailure as PhoneOfflineMapImportResult.Failure).error,
            )
            assertEquals(
                PhoneOfflineMapImportStage.COPY,
                PhoneOfflineMapImportDiagnostics.latestAttempt()?.failureStage,
            )
            assertEquals(2L, PhoneOfflineMapImportDiagnostics.latestAttempt()?.bytesCopied)

            val mapFileFailureStore =
                PhoneOfflineMapStore(directory) {
                    PhoneOfflineMapValidation(
                        error = PhoneOfflineMapError.INVALID,
                        mapFileOpened = false,
                        exception = IllegalArgumentException("invalid header"),
                    )
                }
            val mapFileFailure = mapFileFailureStore.import("invalid.map", "map".byteInputStream())

            assertEquals(
                PhoneOfflineMapError.INVALID,
                (mapFileFailure as PhoneOfflineMapImportResult.Failure).error,
            )
            assertEquals(
                PhoneOfflineMapImportStage.MAPFILE_OPEN,
                PhoneOfflineMapImportDiagnostics.latestAttempt()?.failureStage,
            )
        } finally {
            PhoneOfflineMapImportDiagnostics.clear()
            directory.deleteRecursively()
        }
    }

    @Test
    fun duplicateImportsUseSafeNameCollisionsAndAreFoundByFolderSyncMatching() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            val store = PhoneOfflineMapStore(directory) { PhoneOfflineMapValidation() }

            val first = store.import("example.map", "first".byteInputStream())
            val second = store.import("example.map", "second".byteInputStream())

            assertEquals("example.map", (first as PhoneOfflineMapImportResult.Success).map.displayName)
            assertEquals("example (1).map", (second as PhoneOfflineMapImportResult.Success).map.displayName)
            assertEquals("example.map", store.findSynchronizedMap("example.map", 5L)?.displayName)
            assertEquals("example (1).map", store.findSynchronizedMap("example.map", 6L)?.displayName)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bundleInstallReusesAnExistingValidMapWithoutOverwritingIt() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            val target = File(directory, "alps.map").apply { writeText("valid existing") }
            val store =
                PhoneOfflineMapStore(directory) { candidate ->
                    PhoneOfflineMapValidation(
                        error =
                            if (candidate.readText().startsWith("valid")) {
                                null
                            } else {
                                PhoneOfflineMapError.INVALID
                            },
                    )
                }

            val result =
                runBlocking {
                    store.installBundleMap("alps.map", "valid replacement".byteInputStream()) {}
                }

            assertTrue((result as PhoneOfflineMapBundleInstallResult.Success).reusedExisting)
            assertEquals("valid existing", target.readText())
            assertFalse(File(directory, ".alps.map.bundle.part").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bundleInstallReplacesAnInvalidMapOnlyAfterItsReplacementValidates() {
        val directory = Files.createTempDirectory("glancemap-phone-maps").toFile()
        try {
            val target = File(directory, "alps.map").apply { writeText("invalid") }
            val store =
                PhoneOfflineMapStore(directory) { candidate ->
                    PhoneOfflineMapValidation(
                        error =
                            if (candidate.readText().startsWith("valid")) {
                                null
                            } else {
                                PhoneOfflineMapError.INVALID
                            },
                    )
                }

            val result =
                runBlocking {
                    store.installBundleMap("alps.map", "valid replacement".byteInputStream()) {}
                }

            assertFalse((result as PhoneOfflineMapBundleInstallResult.Success).reusedExisting)
            assertEquals("valid replacement", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun folderDocumentFilterOnlyAcceptsMapFiles() {
        assertTrue(isPhoneOfflineMapDocumentCandidate("alps.map", isFile = true))
        assertTrue(isPhoneOfflineMapDocumentCandidate("alps.MAP", isFile = true))
        assertFalse(isPhoneOfflineMapDocumentCandidate("alps.map.part", isFile = true))
        assertFalse(isPhoneOfflineMapDocumentCandidate("alps.zip", isFile = true))
        assertFalse(isPhoneOfflineMapDocumentCandidate("alps.map", isFile = false))
        assertFalse(isPhoneOfflineMapDocumentCandidate(null, isFile = true))
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
