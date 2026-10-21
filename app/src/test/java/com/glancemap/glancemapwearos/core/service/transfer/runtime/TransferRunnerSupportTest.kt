package com.glancemap.glancemapwearos.core.service.transfer.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferRunnerSupportTest {
    @Test
    fun `verification percent clamps to valid range`() {
        assertEquals(-1, computeVerificationPercent(bytesRead = 10L, totalBytes = 0L))
        assertEquals(0, computeVerificationPercent(bytesRead = 0L, totalBytes = 100L))
        assertEquals(37, computeVerificationPercent(bytesRead = 37L, totalBytes = 100L))
        assertEquals(100, computeVerificationPercent(bytesRead = 150L, totalBytes = 100L))
    }
}
