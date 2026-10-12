package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhoneOfflineMapImportDeduplicationTest {
    @Test
    fun repeatedAndRenamedImportsReuseTheInstalledContent() {
        withDirectory { directory ->
            val store = validMapStore(directory)
            val first = store.import("alps.map", "map data".byteInputStream()) as PhoneOfflineMapImportResult.Success
            val revision = first.map.rendererIdentity
            val second = store.import("alps.map", "map data".byteInputStream()) as PhoneOfflineMapImportResult.Success
            val renamed =
                store.import("renamed.map", "map data".byteInputStream()) as PhoneOfflineMapImportResult.Success

            assertFalse(first.reusedExisting)
            assertTrue(second.reusedExisting)
            assertTrue(renamed.reusedExisting)
            assertEquals(first.map, second.map)
            assertEquals(first.map, renamed.map)
            assertEquals(revision, second.map.rendererIdentity)
            assertEquals(listOf("alps.map"), directory.listFiles().orEmpty().map(File::getName))
        }
    }

    @Test
    fun folderThenFileAndFileThenFolderBothReuseTheSameMap() {
        withDirectory { directory ->
            val store = validMapStore(directory)
            assertEquals(
                PhoneOfflineMapFolderSyncResult(importedCount = 1),
                store.synchronizeFolderMap("alps.map") { "map data".byteInputStream() },
            )
            val manual = store.import("alps.map", "map data".byteInputStream()) as PhoneOfflineMapImportResult.Success
            assertTrue(manual.reusedExisting)
            assertEquals(
                PhoneOfflineMapFolderSyncResult(skippedCount = 1),
                store.synchronizeFolderMap("another-name.map") { "map data".byteInputStream() },
            )
            assertEquals(listOf("alps.map"), directory.listFiles().orEmpty().map(File::getName))
        }
    }

    @Test
    fun sameSizeChangesBeyondTheFirstBufferRemainSeparateMaps() {
        withDirectory { directory ->
            val store = validMapStore(directory)
            val old = ByteArray(DEFAULT_BUFFER_SIZE * 2 + 17) { 1 }
            val changed = old.copyOf().apply { this[lastIndex] = 2 }
            val first = store.import("alps.map", old.inputStream()) as PhoneOfflineMapImportResult.Success
            val second = store.import("alps.map", changed.inputStream()) as PhoneOfflineMapImportResult.Success

            assertFalse(second.reusedExisting)
            assertEquals("alps (1).map", second.map.displayName)
            assertTrue(
                first.map.file
                    .readBytes()
                    .contentEquals(old),
            )
            assertTrue(
                second.map.file
                    .readBytes()
                    .contentEquals(changed),
            )
            assertEquals(2, store.discover().size)
        }
    }

    @Test
    fun overlappingFolderAndFileImportsFromDifferentStoreInstancesCannotCollide() {
        withDirectory { directory ->
            val bothValidated = CountDownLatch(2)
            val inspector: (File) -> PhoneOfflineMapValidation = {
                bothValidated.countDown()
                check(bothValidated.await(5, TimeUnit.SECONDS))
                PhoneOfflineMapValidation()
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val manual =
                    executor.submit<PhoneOfflineMapImportResult> {
                        PhoneOfflineMapStore(directory, inspector).import("alps.map", "map data".byteInputStream())
                    }
                val folder =
                    executor.submit<PhoneOfflineMapFolderSyncResult> {
                        PhoneOfflineMapStore(directory, inspector).synchronizeFolderMap("alps.map") {
                            "map data".byteInputStream()
                        }
                    }
                val manualResult = manual.get(10, TimeUnit.SECONDS) as PhoneOfflineMapImportResult.Success
                val folderResult = folder.get(10, TimeUnit.SECONDS)

                assertEquals(
                    if (manualResult.reusedExisting) {
                        PhoneOfflineMapFolderSyncResult(importedCount = 1)
                    } else {
                        PhoneOfflineMapFolderSyncResult(skippedCount = 1)
                    },
                    folderResult,
                )
                assertEquals(listOf("alps.map"), directory.listFiles().orEmpty().map(File::getName))
                assertEquals("map data", File(directory, "alps.map").readText())
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun failedValidationCannotReuseOrDamageAnExistingFile() {
        withDirectory { directory ->
            val existing = File(directory, "alps.map").apply { writeText("invalid map") }
            val store =
                PhoneOfflineMapStore(directory) { PhoneOfflineMapValidation(error = PhoneOfflineMapError.INVALID) }

            val result = store.import("alps.map", "invalid map".byteInputStream())

            assertEquals(PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.INVALID), result)
            assertEquals("invalid map", existing.readText())
            assertEquals(listOf("alps.map"), directory.listFiles().orEmpty().map(File::getName))
        }
    }

    private fun validMapStore(
        directory: File,
    ): PhoneOfflineMapStore = PhoneOfflineMapStore(directory) { PhoneOfflineMapValidation() }

    private fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("phone-map-import-dedup-").toFile()
        try {
            test(directory)
        } finally {
            PhoneOfflineMapImportDiagnostics.clear()
            directory.deleteRecursively()
        }
    }
}
