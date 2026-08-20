package com.glancemap.glancemapwearos.data.repository.internal

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class AtomicStreamWriterTest {
    @Test
    fun freshWriteComputesSha256() =
        runTest {
            val dir = createTempDir()
            val payload = "fresh-payload".toByteArray()

            val result =
                AtomicStreamWriter.writeAtomic(
                    dir = dir,
                    fileName = "sample.map",
                    inputStream = payload.inputStream(),
                    onProgress = {},
                    options =
                        AtomicStreamWriter.Options(
                            fsync = false,
                            computeSha256 = true,
                        ),
                )

            assertEquals(payload.size.toLong(), result.bytesCopied)
            assertEquals(sha256(payload), result.sha256)
            assertArrayEquals(payload, File(dir, "sample.map").readBytes())
            assertFalse(File(dir, ".sample.map.part").exists())
        }

    @Test
    fun resumedWritePromotesFileAndDefersSha256ToFinalVerification() =
        runTest {
            val dir = createTempDir()
            val existing = "already-downloaded-".toByteArray()
            val appended = "new-bytes".toByteArray()
            val partFile = File(dir, ".sample.map.part")
            partFile.writeBytes(existing)

            val result =
                AtomicStreamWriter.writeAtomic(
                    dir = dir,
                    fileName = "sample.map",
                    inputStream = appended.inputStream(),
                    onProgress = {},
                    options =
                        AtomicStreamWriter.Options(
                            fsync = false,
                            resumeOffset = existing.size.toLong(),
                            computeSha256 = true,
                        ),
                )

            val combined = existing + appended
            assertEquals(combined.size.toLong(), result.bytesCopied)
            assertNull(result.sha256)
            assertArrayEquals(combined, File(dir, "sample.map").readBytes())
            assertFalse(partFile.exists())
        }

    @Test
    fun resumedWriteTruncatesPartialBackToRequestedOffsetBeforeAppend() =
        runTest {
            val dir = createTempDir()
            val existing = "already-downloaded-extra".toByteArray()
            val appended = "new-bytes".toByteArray()
            val partFile = File(dir, ".sample.map.part")
            partFile.writeBytes(existing)

            val result =
                AtomicStreamWriter.writeAtomic(
                    dir = dir,
                    fileName = "sample.map",
                    inputStream = appended.inputStream(),
                    onProgress = {},
                    options =
                        AtomicStreamWriter.Options(
                            fsync = false,
                            resumeOffset = "already-downloaded-".toByteArray().size.toLong(),
                            computeSha256 = true,
                        ),
                )

            val combined = "already-downloaded-".toByteArray() + appended
            assertEquals(combined.size.toLong(), result.bytesCopied)
            assertNull(result.sha256)
            assertArrayEquals(combined, File(dir, "sample.map").readBytes())
            assertFalse(partFile.exists())
        }

    @Test
    fun restartFromZeroDropsStalePartialAndComputesSha256() =
        runTest {
            val dir = createTempDir()
            File(dir, ".sample.map.part").writeBytes("stale".toByteArray())
            val payload = "replacement".toByteArray()

            val result =
                AtomicStreamWriter.writeAtomic(
                    dir = dir,
                    fileName = "sample.map",
                    inputStream = payload.inputStream(),
                    onProgress = {},
                    options =
                        AtomicStreamWriter.Options(
                            fsync = false,
                            resumeOffset = 0L,
                            computeSha256 = true,
                        ),
                )

            assertEquals(payload.size.toLong(), result.bytesCopied)
            assertEquals(sha256(payload), result.sha256)
            assertArrayEquals(payload, File(dir, "sample.map").readBytes())
            assertFalse(File(dir, ".sample.map.part").exists())
        }

    @Test
    fun incompleteRequiredWriteNeverPromotesAndCleansUpPartial() =
        runTest {
            val dir = createTempDir()
            val error =
                runCatching {
                    AtomicStreamWriter.writeAtomic(
                        dir = dir,
                        fileName = "sample.map",
                        inputStream = ByteArrayInputStream("short".toByteArray()),
                        onProgress = {},
                        options =
                            AtomicStreamWriter.Options(
                                fsync = false,
                                expectedSize = 10L,
                                requireExactSize = true,
                                keepPartialOnFailure = false,
                            ),
                    )
                }.exceptionOrNull()

            assertTrue(error is IOException)
            assertFalse(File(dir, "sample.map").exists())
            assertFalse(File(dir, ".sample.map.part").exists())
        }

    private fun createTempDir(): File =
        Files.createTempDirectory("atomic-stream-writer-test").toFile().apply {
            deleteOnExit()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
