package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class PhoneOfflineMapFolderSyncTest {
    @Test
    fun sameSizeAndTimestampChangeImportsANewCopyThenReusesThatCopy() {
        withMapStore { directory, store ->
            val sourceDirectory = Files.createTempDirectory("phone-map-folder-source-").toFile()
            try {
                val source = File(sourceDirectory, "alps.map").apply { writeText("old map") }
                val modified = source.lastModified()
                assertEquals(
                    PhoneOfflineMapFolderSyncResult(importedCount = 1),
                    store.synchronizeFolderMap(source.name, source::inputStream),
                )
                val previous = store.discover().single()

                source.writeText("new map")
                assertTrue(source.setLastModified(modified))
                assertEquals(previous.file.length(), source.length())
                assertEquals(modified, source.lastModified())

                val changed = store.synchronizeFolderMap(source.name, source::inputStream)
                val unchanged = store.synchronizeFolderMap(source.name, source::inputStream)

                assertEquals(PhoneOfflineMapFolderSyncResult(importedCount = 1), changed)
                assertEquals(PhoneOfflineMapFolderSyncResult(skippedCount = 1), unchanged)
                assertEquals("old map", previous.file.readText())
                assertEquals(previous, store.discover().first { it.file == previous.file })
                assertEquals("new map", File(directory, "alps (1).map").readText())
                assertEquals(2, store.discover().size)
            } finally {
                sourceDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun newMapOpensItsSourceOnceAndIdenticalMapDoesNotCreateAnotherFile() {
        withMapStore { directory, store ->
            var opens = 0
            val source = {
                opens += 1
                "map data".byteInputStream()
            }

            assertEquals(
                PhoneOfflineMapFolderSyncResult(importedCount = 1),
                store.synchronizeFolderMap("alps.map", source),
            )
            assertEquals(1, opens)
            assertEquals(
                PhoneOfflineMapFolderSyncResult(skippedCount = 1),
                store.synchronizeFolderMap("alps.map", source),
            )
            assertEquals(2, opens)
            assertEquals(listOf("alps.map"), directory.listFiles().orEmpty().map(File::getName))
        }
    }

    @Test
    fun shortReadsAndChangesBeyondTheFirstBufferAreComparedInFull() {
        withMapStore { directory, store ->
            val old = ByteArray(DEFAULT_BUFFER_SIZE * 2 + 17) { 1 }
            File(directory, "alps.map").writeBytes(old)
            val changed = old.copyOf().apply { this[lastIndex] = 2 }
            val source = {
                object : ByteArrayInputStream(changed) {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = super.read(buffer, offset, minOf(length, 7))
                }
            }

            assertEquals(
                PhoneOfflineMapFolderSyncResult(importedCount = 1),
                store.synchronizeFolderMap("alps.map", source),
            )
            assertEquals(
                PhoneOfflineMapFolderSyncResult(skippedCount = 1),
                store.synchronizeFolderMap("alps.map", source),
            )
            assertTrue(File(directory, "alps (1).map").readBytes().contentEquals(changed))
            assertTrue(File(directory, "alps.map").readBytes().contentEquals(old))
        }
    }

    @Test
    fun missingOrInterruptedSourceCannotSkipOrDamageTheInstalledMap() {
        withMapStore { directory, store ->
            val installed = File(directory, "alps.map").apply { writeText("old map") }
            val missing = store.synchronizeFolderMap("alps.map") { null }
            var closed = false
            val interrupted =
                store.synchronizeFolderMap("alps.map") {
                    object : InputStream() {
                        override fun read(): Int = throw IOException("Interrupted source read")

                        override fun close() {
                            closed = true
                        }
                    }
                }

            assertEquals(PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FILE_NOT_READABLE), missing)
            assertEquals(PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.COPY_FAILED), interrupted)
            assertTrue(closed)
            assertEquals("old map", installed.readText())
            assertEquals(listOf("alps.map"), directory.listFiles().orEmpty().map(File::getName))
        }
    }

    @Test
    fun invalidSameSizeSourcePreservesTheExistingMapAndCleansItsPartialCopy() {
        withMapStore(
            inspector = { candidate ->
                PhoneOfflineMapValidation(
                    error = PhoneOfflineMapError.INVALID.takeIf { candidate.readText() == "bad map" },
                )
            },
        ) { directory, store ->
            val installed = File(directory, "alps.map").apply { writeText("old map") }

            val result = store.synchronizeFolderMap("alps.map") { "bad map".byteInputStream() }

            assertEquals(PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.INVALID), result)
            assertEquals("old map", installed.readText())
            assertFalse(File(directory, "alps (1).map.part").exists())
            assertEquals(listOf("alps.map"), directory.listFiles().orEmpty().map(File::getName))
        }
    }

    @Test
    fun sourcePermissionLossPropagatesToTheFolderPermissionHandler() {
        withMapStore { directory, store ->
            File(directory, "alps.map").writeText("old map")

            assertThrows(SecurityException::class.java) {
                store.synchronizeFolderMap("alps.map") { throw SecurityException("Permission lost") }
            }

            assertEquals("old map", File(directory, "alps.map").readText())
        }
    }

    @Test
    fun identicalContentWithADifferentNameIsReusedAndNonMapNamesAreRejected() {
        withMapStore { directory, store ->
            File(directory, "other.map").writeText("map data")

            assertEquals(
                PhoneOfflineMapFolderSyncResult(skippedCount = 1),
                store.synchronizeFolderMap("alps.map") { "map data".byteInputStream() },
            )
            assertEquals(
                PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FILE_NOT_MAP),
                store.synchronizeFolderMap("alps.txt") { error("Non-map source must not be opened") },
            )
            assertEquals(listOf("other.map"), store.discover().map { it.displayName })
        }
    }

    private fun withMapStore(
        inspector: (File) -> PhoneOfflineMapValidation = { PhoneOfflineMapValidation() },
        test: (File, PhoneOfflineMapStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("phone-map-folder-sync-").toFile()
        try {
            test(directory, PhoneOfflineMapStore(directory, inspector))
        } finally {
            PhoneOfflineMapImportDiagnostics.clear()
            directory.deleteRecursively()
        }
    }
}
