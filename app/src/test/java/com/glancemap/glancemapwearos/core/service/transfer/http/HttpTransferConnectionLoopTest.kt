package com.glancemap.glancemapwearos.core.service.transfer.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTransferConnectionLoopTest {
    @Test
    fun `fresh http transfer computes checksum inline`() {
        assertTrue(shouldComputeInlineChecksumForHttp(resumeOffset = 0L))
    }

    @Test
    fun `resumed http transfer defers checksum to final verification`() {
        assertFalse(shouldComputeInlineChecksumForHttp(resumeOffset = 1L))
    }

    @Test
    fun `wifi acquired after 22 seconds still fits startup budget`() {
        val deadline = HTTP_STARTUP_BUDGET_MS

        assertTrue(remainingHttpStartupBudget(deadline, nowElapsedMs = 22_000L) >= 18_000L)
    }

    @Test
    fun `startup budget expires before an additional retry window`() {
        assertEquals(0L, remainingHttpStartupBudget(40_000L, nowElapsedMs = 40_001L))
    }

    @Test
    fun `connection timeout is capped by remaining startup budget`() {
        assertEquals(4_000, cappedHttpTimeoutMs(normalTimeoutMs = 4_000L, remainingBudgetMs = 20_000L))
        assertEquals(2_000, cappedHttpTimeoutMs(normalTimeoutMs = 4_000L, remainingBudgetMs = 2_000L))
        assertEquals(null, cappedHttpTimeoutMs(normalTimeoutMs = 4_000L, remainingBudgetMs = 0L))
    }

    @Test
    fun `retry delay is capped by remaining startup budget`() {
        assertEquals(300L, cappedHttpRetryDelayMs(normalDelayMs = 3_000L, remainingBudgetMs = 300L))
        assertEquals(0L, cappedHttpRetryDelayMs(normalDelayMs = 3_000L, remainingBudgetMs = 0L))
    }

    @Test
    fun `fresh transfer counts all cumulative bytes for this attempt`() {
        assertEquals(
            1_000L,
            calculateBytesTransferredThisAttempt(
                resumeOffsetBytes = 0L,
                finalFileSizeBytes = 1_000L,
            ),
        )
    }

    @Test
    fun `resumed transfer counts only bytes after resume offset`() {
        assertEquals(
            250L,
            calculateBytesTransferredThisAttempt(
                resumeOffsetBytes = 750L,
                finalFileSizeBytes = 1_000L,
            ),
        )
    }

    @Test
    fun `failed partial transfer counts bytes written since its offset`() {
        assertEquals(
            100L,
            calculateBytesTransferredThisAttempt(
                resumeOffsetBytes = 750L,
                finalFileSizeBytes = 850L,
            ),
        )
    }

    @Test
    fun `attempt throughput uses transferred bytes in MiB`() {
        assertEquals(
            2.5,
            calculateAttemptThroughputMiBps(
                bytesTransferredThisAttempt = 250L * 1024L * 1024L,
                dataTransferDurationMs = 100_000L,
            ),
            0.0001,
        )
    }
}
