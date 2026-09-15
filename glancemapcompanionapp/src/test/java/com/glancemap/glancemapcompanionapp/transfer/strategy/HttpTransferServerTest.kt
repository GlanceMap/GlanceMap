package com.glancemap.glancemapcompanionapp.transfer.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HttpTransferServerTest {
    private val server = HttpTransferServer()
    private val mib = 1024L * 1024L
    private val totalFileBytes = (931.53 * mib).toLong()

    @Test
    fun `first request timeout allows watch wifi acquisition`() {
        assertEquals(45_000L, HttpTransferServer.FIRST_REQUEST_TIMEOUT_MS)
    }

    @Test
    fun `small fresh transfer keeps default stall timeout`() {
        assertEquals(
            30_000L,
            server.computeHttpStallTimeoutMs(
                totalSize = 32L * 1024L * 1024L,
                resumeOffset = 0L,
                isMapFile = false,
            ),
        )
    }

    @Test
    fun `large resumed map transfer gets longer stall timeout`() {
        assertEquals(
            90_000L,
            server.computeHttpStallTimeoutMs(
                totalSize = 1_032_008_362L,
                resumeOffset = 631_968_016L,
                isMapFile = true,
            ),
        )
    }

    @Test
    fun `near-complete resumed transfer gets tail stall timeout`() {
        assertEquals(
            120_000L,
            server.computeHttpStallTimeoutMs(
                totalSize = 1_032_008_362L,
                resumeOffset = 977_750_480L,
                isMapFile = true,
            ),
        )
    }

    @Test
    fun `fresh transfer displays current attempt bytes as cumulative progress`() {
        val attemptBytes = 124L * mib

        assertEquals(
            attemptBytes,
            calculateCumulativeHttpProgressBytes(
                resumeOffsetBytes = 0L,
                bytesTransferredThisAttempt = attemptBytes,
                fullFileSizeBytes = totalFileBytes,
            ),
        )
        assertEquals(
            124.0 / 931.53,
            calculateHttpProgressFraction(
                resumeOffsetBytes = 0L,
                bytesTransferredThisAttempt = attemptBytes,
                fullFileSizeBytes = totalFileBytes,
            ).toDouble(),
            0.0001,
        )
    }

    @Test
    fun `resumed transfer adds offset for cumulative progress display`() {
        val resumeOffsetBytes = 90L * mib
        val attemptBytes = 124L * mib
        val cumulativeBytes = 214L * mib

        assertEquals(
            cumulativeBytes,
            calculateCumulativeHttpProgressBytes(
                resumeOffsetBytes = resumeOffsetBytes,
                bytesTransferredThisAttempt = attemptBytes,
                fullFileSizeBytes = totalFileBytes,
            ),
        )
        assertEquals(
            214.0 / 931.53,
            calculateHttpProgressFraction(
                resumeOffsetBytes = resumeOffsetBytes,
                bytesTransferredThisAttempt = attemptBytes,
                fullFileSizeBytes = totalFileBytes,
            ).toDouble(),
            0.0001,
        )
    }

    @Test
    fun `cumulative progress is capped at one hundred percent`() {
        assertEquals(
            totalFileBytes,
            calculateCumulativeHttpProgressBytes(
                resumeOffsetBytes = 900L * mib,
                bytesTransferredThisAttempt = 100L * mib,
                fullFileSizeBytes = totalFileBytes,
            ),
        )
        assertEquals(
            1f,
            calculateHttpProgressFraction(
                resumeOffsetBytes = 900L * mib,
                bytesTransferredThisAttempt = 100L * mib,
                fullFileSizeBytes = totalFileBytes,
            ),
        )
    }

    @Test
    fun `resumed payload progress replaces temporary resume text`() {
        val text =
            server.formatActiveTransferText(
                completedBytes = 214L * mib,
                totalSize = totalFileBytes,
                speedMiBps = 2.96,
            )

        assertEquals(
            "HTTP: 214.00 MiB / 931.53 MiB (2.96 MiB/s)",
            text,
        )
        assertFalse(text.contains("resuming", ignoreCase = true))
    }

    @Test
    fun `fresh payload progress never shows resume text`() {
        val text =
            server.formatActiveTransferText(
                completedBytes = 124L * mib,
                totalSize = totalFileBytes,
                speedMiBps = 2.96,
            )

        assertFalse(text.contains("resuming", ignoreCase = true))
    }
}
