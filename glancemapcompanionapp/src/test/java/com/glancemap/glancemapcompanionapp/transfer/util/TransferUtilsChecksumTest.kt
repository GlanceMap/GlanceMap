package com.glancemap.glancemapcompanionapp.transfer.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class TransferUtilsChecksumTest {
    @Test
    fun knownPositiveSizeRejectsImmediateEof() =
        runBlocking {
            val checksum =
                Sha256TransferUtils.computeSha256FromStream(
                    input = ByteArrayInputStream(byteArrayOf()),
                    options = Sha256Options(knownSize = 4_567_781L, elapsedRealtimeMs = { 0L }),
                )

            assertNull(checksum)
        }

    @Test
    fun knownSizeRejectsTruncatedStream() =
        runBlocking {
            val checksum =
                Sha256TransferUtils.computeSha256FromStream(
                    input = ByteArrayInputStream("short".toByteArray()),
                    options = Sha256Options(knownSize = 10L, elapsedRealtimeMs = { 0L }),
                )

            assertNull(checksum)
        }

    @Test
    fun knownSizeRejectsExtraData() =
        runBlocking {
            val checksum =
                Sha256TransferUtils.computeSha256FromStream(
                    input = ByteArrayInputStream("too-long".toByteArray()),
                    options = Sha256Options(knownSize = 4L, elapsedRealtimeMs = { 0L }),
                )

            assertNull(checksum)
        }

    @Test
    fun knownExactSizeReturnsContentChecksum() =
        runBlocking {
            val bytes = "complete-file".toByteArray()
            val checksum =
                Sha256TransferUtils.computeSha256FromStream(
                    input = ByteArrayInputStream(bytes),
                    options = Sha256Options(knownSize = bytes.size.toLong(), elapsedRealtimeMs = { 0L }),
                )

            assertEquals(sha256(bytes), checksum)
        }

    @Test
    fun knownZeroSizeStillAllowsLegitimateEmptyFile() =
        runBlocking {
            val checksum =
                Sha256TransferUtils.computeSha256FromStream(
                    input = ByteArrayInputStream(byteArrayOf()),
                    options = Sha256Options(knownSize = 0L, elapsedRealtimeMs = { 0L }),
                )

            assertEquals(sha256(byteArrayOf()), checksum)
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
