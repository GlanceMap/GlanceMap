package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.testsupport.englishCompanionText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class PhoneOfflineStorageCancellationTest {
    @Test
    fun cancellationInsideALargeCopyKeepsBothStorageTreesAndCanResume() =
        runBlocking {
            withStorageRoots { source, target, journal ->
                val sourceFile = writeFile(source, "maps/large.map", ByteArray(3 * 1024 * 1024) { 42 })
                val targetFile = writeFile(target, "maps/target-only.map", byteArrayOf(9))
                val migration = PhoneOfflineStorageMigration(source, target, journal, ::englishCompanionText)
                var interruptedBytes = 0L
                val job =
                    launch {
                        migration.move(PhoneOfflineStorageLocation.EXTERNAL) { progress ->
                            if (progress.copiedBytes > 0L && progress.copiedBytes < sourceFile.length()) {
                                interruptedBytes = progress.copiedBytes
                                currentCoroutineContext().cancel()
                            }
                        }
                    }
                job.join()

                assertTrue(job.isCancelled)
                assertEquals(1024L * 1024L, interruptedBytes)
                assertEquals(3L * 1024L * 1024L, sourceFile.length())
                assertEquals(listOf(9.toByte()), targetFile.readBytes().toList())
                assertFalse(File(target, "maps/large.map").exists())
                assertEquals(PhoneOfflineStorageMigrationPhase.COPYING, migration.pending()!!.phase)

                val result = migration.move(PhoneOfflineStorageLocation.EXTERNAL)

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals(3L * 1024L * 1024L, File(target, "maps/large.map").length())
                assertEquals(listOf(9.toByte()), targetFile.readBytes().toList())
                assertFalse(sourceFile.exists())
                assertFalse(journal.exists())
            }
        }

    @Test
    fun cancellationDuringVerificationPreservesCompletedCopiesForResume() =
        runBlocking {
            withStorageRoots { source, target, journal ->
                val original = writeFile(source, "maps/area.map", byteArrayOf(1, 2, 3))
                val migration = PhoneOfflineStorageMigration(source, target, journal, ::englishCompanionText)
                val cancelled =
                    runCatching {
                        migration.move(PhoneOfflineStorageLocation.EXTERNAL) { progress ->
                            if (progress.phase == PhoneOfflineStorageMigrationPhase.VERIFYING) {
                                throw CancellationException("cancel move")
                            }
                        }
                    }

                assertTrue(cancelled.exceptionOrNull() is CancellationException)
                assertTrue(original.isFile)
                assertFalse(File(target, "maps/area.map").exists())
                val pending = migration.pending()!!
                assertEquals(PhoneOfflineStorageMigrationPhase.VERIFYING, pending.phase)
                val stagedFile = File(pending.stagingRootPath, "maps/area.map")
                assertEquals(original.readBytes().toList(), stagedFile.readBytes().toList())
                val copiedAt = stagedFile.lastModified()
                var resumedCopies = -1

                val result =
                    migration.move(PhoneOfflineStorageLocation.EXTERNAL) { progress ->
                        if (resumedCopies < 0) resumedCopies = progress.copiedFiles
                    }

                assertTrue(result is PhoneOfflineStorageMigrationResult.Success)
                assertEquals(1, resumedCopies)
                assertEquals(copiedAt, File(target, "maps/area.map").lastModified())
                assertFalse(journal.exists())
            }
        }

    @Test
    fun finalIntegrityCheckStillRejectsAStagedFileChangedAfterItsCopy() =
        runBlocking {
            withStorageRoots { source, target, journal ->
                val original = writeFile(source, "maps/area.map", byteArrayOf(1, 2, 3))
                writeFile(source, "maps/other.map", byteArrayOf(4, 5, 6))
                val migration = PhoneOfflineStorageMigration(source, target, journal, ::englishCompanionText)
                var tampered = false
                val result =
                    migration.move(PhoneOfflineStorageLocation.EXTERNAL) { progress ->
                        if (progress.phase == PhoneOfflineStorageMigrationPhase.COPYING &&
                            progress.copiedFiles == 1 &&
                            !tampered
                        ) {
                            tampered = true
                            val staging = File(migration.pending()!!.stagingRootPath)
                            File(staging, "maps/area.map").writeBytes(byteArrayOf(9, 9, 9))
                        }
                    }

                assertTrue(tampered)
                assertEquals(
                    PhoneOfflineStorageMigrationError.VERIFY_FAILED,
                    (result as PhoneOfflineStorageMigrationResult.Failure).error,
                )
                assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), original.readBytes().toList())
                assertFalse(File(target, "maps/area.map").exists())
                assertTrue(journal.exists())
            }
        }

    @Test
    fun checksumChecksCancellationBetweenLargeReadBuffers() {
        val root = createTempDirectory(prefix = "storage-checksum-").toFile()
        try {
            val file = writeFile(root, "large.map", ByteArray(3 * 1024 * 1024))
            var checks = 0
            val result =
                runCatching {
                    hashPhoneOfflineStorageFile(file) {
                        checks += 1
                        if (checks == 2) throw CancellationException("cancel checksum")
                    }
                }

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(2, checks)
            assertEquals(3L * 1024L * 1024L, file.length())
            val expected =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { byte -> "%02x".format(byte) }
            assertEquals(expected, hashPhoneOfflineStorageFile(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun byteProgressAdvancesBeforeALargeFileIsCompleted() {
        val state =
            PhoneOfflineStorageMigrationState(
                copiedFiles = 0,
                totalFiles = 2,
                copiedBytes = 50L,
                totalBytes = 100L,
            )

        assertEquals(50, state.percent)
        assertEquals(0, state.copy(copiedBytes = -1L).percent)
        assertEquals(100, state.copy(copiedBytes = 101L).percent)
    }

    private suspend fun withStorageRoots(block: suspend (File, File, File) -> Unit) {
        val root = createTempDirectory(prefix = "storage-cancellation-").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val target = File(root, "target/GlanceMap").apply { mkdirs() }
            block(source, target, File(root, "migration.properties"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeFile(
        root: File,
        path: String,
        bytes: ByteArray,
    ): File =
        File(root, path).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
}
