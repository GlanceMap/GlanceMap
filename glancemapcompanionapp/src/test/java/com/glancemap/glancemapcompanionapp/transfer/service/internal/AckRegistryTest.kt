package com.glancemap.glancemapcompanionapp.transfer.service.internal

import com.glancemap.glancemapcompanionapp.transfer.strategy.channelAckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AckRegistryTest {
    @Test
    fun `done ack maps to channel transfer success`() {
        val result = requireNotNull(transferResultForAck("DONE", ""))

        assertTrue(result.success)
    }

    @Test
    fun `error ack maps to channel transfer failure`() {
        val result = requireNotNull(transferResultForAck("ERROR", "CHECKSUM_MISMATCH"))

        assertFalse(result.success)
        assertEquals("CHECKSUM_MISMATCH", result.message)
    }

    @Test
    fun `ack timeout is not reported as success`() {
        val result = channelAckResult(null)

        assertFalse(result.success)
        assertEquals("File sent, but watch did not confirm that it was saved.", result.message)
    }
}
