package com.glancemap.glancemapwearos.core.service.transfer.runtime

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTransferSessionOwnerTest {
    @Test
    fun `stale timeout start id cannot terminate a newer transfer`() {
        val owner = ForegroundTransferSessionOwner()
        owner.begin(10, "A", "a.map", "phone", 1, Job())
        assertTrue(owner.end(10, "A"))
        owner.begin(11, "B", "b.map", "phone", 2, Job())

        assertNull(owner.claim(10, TransferTerminalOutcome.TIMEOUT))
        assertNull(owner.outcomeForTransferId("B"))
    }

    @Test
    fun `matching duplicate timeout has one terminal winner and one cleanup`() {
        val owner = ForegroundTransferSessionOwner()
        owner.begin(11, "B", "b.map", "phone", 2, Job())

        assertNotNull(owner.claim(11, TransferTerminalOutcome.TIMEOUT))
        assertNull(owner.claim(11, TransferTerminalOutcome.TIMEOUT))
        assertEquals(TransferTerminalOutcome.TIMEOUT, owner.outcomeForTransferId("B"))
        assertTrue(owner.end(11, "B"))
        assertFalse(owner.end(11, "B"))
    }

    @Test
    fun `completion race prevents timeout from claiming the same session`() {
        val owner = ForegroundTransferSessionOwner()
        val session = owner.begin(11, "B", "b.map", "phone", 2, Job())

        assertTrue(session.terminalState.tryClaim(TransferTerminalOutcome.DONE))
        assertNull(owner.claim(11, TransferTerminalOutcome.TIMEOUT))
        assertEquals(TransferTerminalOutcome.DONE, owner.outcomeForTransferId("B"))
    }
}
