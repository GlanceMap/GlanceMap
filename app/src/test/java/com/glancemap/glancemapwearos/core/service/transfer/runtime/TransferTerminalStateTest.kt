package com.glancemap.glancemapwearos.core.service.transfer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTerminalStateTest {
    @Test
    fun `completion wins and later timeout is ignored`() {
        val state = TransferTerminalState()

        assertTrue(state.tryClaim(TransferTerminalOutcome.DONE))
        assertFalse(state.tryClaim(TransferTerminalOutcome.TIMEOUT))
        assertEquals(TransferTerminalOutcome.DONE, state.current())
    }

    @Test
    fun `timeout wins and later completion is ignored`() {
        val state = TransferTerminalState()

        assertTrue(state.tryClaim(TransferTerminalOutcome.TIMEOUT))
        assertFalse(state.tryClaim(TransferTerminalOutcome.DONE))
        assertEquals(TransferTerminalOutcome.TIMEOUT, state.current())
    }

    @Test
    fun `duplicate timeout has one winner`() {
        val state = TransferTerminalState()

        assertTrue(state.tryClaim(TransferTerminalOutcome.TIMEOUT))
        assertFalse(state.tryClaim(TransferTerminalOutcome.TIMEOUT))
    }

    @Test
    fun `stale timeout start id does not match current foreground owner`() {
        assertFalse(foregroundTimeoutMatchesStartId(timeoutStartId = 7, foregroundOwnerStartId = 8))
        assertTrue(foregroundTimeoutMatchesStartId(timeoutStartId = 8, foregroundOwnerStartId = 8))
    }
}
