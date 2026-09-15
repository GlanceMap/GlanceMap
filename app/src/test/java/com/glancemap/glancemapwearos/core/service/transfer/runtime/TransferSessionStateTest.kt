package com.glancemap.glancemapwearos.core.service.transfer.runtime

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSessionStateTest {
    @Test
    fun `cancelling active transfer cancels its job and clears lookup state`() {
        val state = TransferSessionState()
        val job = Job()
        state.registerActiveTransfer(
            transferId = "transfer-timeout",
            job = job,
            fileName = "map.map",
            sourceNodeId = "watch",
        )

        assertTrue(state.cancelTransferById("transfer-timeout", "FGS_DATA_SYNC_TIMEOUT"))
        assertTrue(job.isCancelled)
        assertEquals("transfer-timeout", state.activeTransferId())

        state.clearActiveTransfer("transfer-timeout")

        assertEquals(null, state.activeTransferId())
        assertEquals(null, state.activeTransferJob())
    }
}
