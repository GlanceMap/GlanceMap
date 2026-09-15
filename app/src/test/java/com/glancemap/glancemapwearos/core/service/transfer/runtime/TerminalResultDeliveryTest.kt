package com.glancemap.glancemapwearos.core.service.transfer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalResultDeliveryTest {
    @Test
    fun `terminal result is delivered only after cleanup`() {
        val events = mutableListOf<String>()
        val delivery = TerminalResultDelivery<String>()

        assertTrue(delivery.record("ERROR"))
        delivery.deliverAfterCleanup(
            cleanup = { events += "cleanup" },
            deliver = { events += "ack:$it" },
        )

        assertEquals(listOf("cleanup", "ack:ERROR"), events)
    }

    @Test
    fun `delivery failure does not undo completed cleanup or redeliver`() {
        val events = mutableListOf<String>()
        val delivery = TerminalResultDelivery<String>()
        delivery.record("ERROR")

        assertThrows(IllegalStateException::class.java) {
            delivery.deliverAfterCleanup(
                cleanup = { events += "cleanup" },
                deliver = {
                    events += "ack"
                    error("ack failed")
                },
            )
        }
        delivery.deliverAfterCleanup(
            cleanup = { events += "cleanup-again" },
            deliver = { events += "ack-again" },
        )

        assertEquals(listOf("cleanup", "ack", "cleanup-again"), events)
    }
}
