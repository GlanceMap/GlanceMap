package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWindHoldGestureTest {
    @Test
    fun `movement after the hold timeout can activate orbit before a delayed callback`() {
        val hold = WorldWindHoldGesture(20f)
        hold.begin(100f, 200f)
        assertFalse(hold.isReadyAt(399, 400))
        assertTrue(hold.isReadyAt(400, 400))
        assertTrue(hold.activate(400))
        assertFalse(hold.isReadyAt(500, 400))

        hold.begin(100f, 200f)
        hold.move(121f, 200f)
        assertFalse(hold.isReadyAt(500, 400))
    }

    @Test
    fun `small movement keeps hold armed and uses latest position`() {
        val hold = WorldWindHoldGesture(20f)
        hold.begin(100f, 200f)
        hold.move(112f, 210f)

        assertTrue(hold.activate(400))
        assertEquals(112f, hold.lastX, 0f)
        assertEquals(210f, hold.lastY, 0f)
        assertFalse(hold.activate(400))
    }

    @Test
    fun `drag beyond tolerance or cancelled touch cannot start orbit`() {
        val hold = WorldWindHoldGesture(20f)
        hold.begin(100f, 200f)
        hold.move(121f, 200f)
        assertFalse(hold.activate(400))

        hold.begin(100f, 200f)
        hold.cancel(WorldWindHoldCancelReason.INTERRUPTED)
        assertFalse(hold.activate(400))
    }

    @Test
    fun `hold telemetry counts starts cancellations and activation delay once`() {
        val hold = WorldWindHoldGesture(20f)
        hold.begin(0f, 0f)
        hold.move(21f, 0f)
        hold.move(30f, 0f)
        hold.cancel(WorldWindHoldCancelReason.RELEASE)
        hold.begin(0f, 0f)
        hold.cancel(WorldWindHoldCancelReason.MULTITOUCH)
        hold.begin(0f, 0f)
        hold.cancel(WorldWindHoldCancelReason.RELEASE)
        hold.begin(0f, 0f)
        hold.cancel(WorldWindHoldCancelReason.INTERRUPTED)
        hold.begin(0f, 0f)
        assertTrue(hold.activate(480))
        assertFalse(hold.activate(900))
        hold.begin(0f, 0f)
        assertTrue(hold.activate(430))

        val metrics = hold.metrics
        assertEquals(6, metrics.starts.get())
        assertEquals(1, metrics.movementCancellations.get())
        assertEquals(1, metrics.multitouchCancellations.get())
        assertEquals(1, metrics.releaseCancellations.get())
        assertEquals(1, metrics.interruptedCancellations.get())
        assertEquals(2, metrics.activations.get())
        assertEquals(910, metrics.activationDelayTotalMs.get())
        assertEquals(480, metrics.activationDelayMaxMs.get())
        assertTrue(metrics.logFields().contains("hold_cancel_movement=1"))
        assertTrue(metrics.logFields().contains("hold_activation_ms_total=910"))
    }
}
