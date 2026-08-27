package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownZoomCoalescerTest {
    @Test
    fun `one crown step produces one zoom target`() {
        val coalescer = CrownZoomCoalescer()

        assertTrue(coalescer.enqueue(currentZoom = 16, step = 1, minZoom = 6, maxZoom = 20))
        assertTrue(coalescer.shouldScheduleFrame())

        coalescer.markFrameScheduled()
        assertEquals(17, coalescer.consumeFrameTarget())
        assertFalse(coalescer.shouldScheduleFrame())
    }

    @Test
    fun `rapid crown steps keep only the final renderer zoom`() {
        val coalescer = CrownZoomCoalescer()

        repeat(4) {
            assertTrue(coalescer.enqueue(currentZoom = 16, step = 1, minZoom = 6, maxZoom = 20))
        }

        coalescer.markFrameScheduled()
        val rendererZooms = mutableListOf<Int>()
        coalescer.consumeFrameTarget()?.let(rendererZooms::add)

        assertEquals(listOf(20), rendererZooms)
    }

    @Test
    fun `crown direction reversal keeps the correct final target`() {
        val coalescer = CrownZoomCoalescer()

        listOf(1, 1, -1, -1, -1).forEach { step ->
            assertTrue(coalescer.enqueue(currentZoom = 16, step = step, minZoom = 6, maxZoom = 20))
        }

        coalescer.markFrameScheduled()

        assertEquals(15, coalescer.consumeFrameTarget())
    }

    @Test
    fun `crown targets remain clamped to map bounds`() {
        val maxCoalescer = CrownZoomCoalescer()
        assertTrue(maxCoalescer.enqueue(currentZoom = 19, step = 1, minZoom = 6, maxZoom = 20))
        assertFalse(maxCoalescer.enqueue(currentZoom = 19, step = 1, minZoom = 6, maxZoom = 20))
        maxCoalescer.markFrameScheduled()
        assertEquals(20, maxCoalescer.consumeFrameTarget())

        val minCoalescer = CrownZoomCoalescer()
        assertTrue(minCoalescer.enqueue(currentZoom = 7, step = -1, minZoom = 6, maxZoom = 20))
        assertFalse(minCoalescer.enqueue(currentZoom = 7, step = -1, minZoom = 6, maxZoom = 20))
        minCoalescer.markFrameScheduled()
        assertEquals(6, minCoalescer.consumeFrameTarget())
    }
}
