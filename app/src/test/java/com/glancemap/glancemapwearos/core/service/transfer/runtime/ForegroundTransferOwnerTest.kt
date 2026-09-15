package com.glancemap.glancemapwearos.core.service.transfer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTransferOwnerTest {
    @Test
    fun `queued request does not replace active owner until it starts`() {
        val owner = ForegroundTransferOwner()

        assertTrue(owner.reserveIfAvailable(startId = 1))
        assertFalse(owner.reserveIfAvailable(startId = 2))
        assertTrue(owner.isOwner(startId = 1))

        assertEquals(1, owner.replace(startId = 2))
        assertTrue(owner.isOwner(startId = 2))
    }

    @Test
    fun `finishing active request keeps foreground available for queued request`() {
        val owner = ForegroundTransferOwner()
        owner.reserveIfAvailable(startId = 1)

        assertFalse(owner.release(startId = 1, remainingTransfers = 1))
        assertFalse(owner.isOwner(startId = 1))

        assertEquals(-1, owner.replace(startId = 2))
        assertTrue(owner.release(startId = 2, remainingTransfers = 0))
        assertFalse(owner.release(startId = 2, remainingTransfers = 0))
    }

    @Test
    fun `queued cancellation releases the final foreground owner once`() {
        val owner = ForegroundTransferOwner()
        owner.reserveIfAvailable(startId = 4)

        assertTrue(owner.clearIfOwner(startId = 4))
        assertFalse(owner.clearIfOwner(startId = 4))
    }
}
