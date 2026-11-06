package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import java.util.concurrent.atomic.AtomicLong

internal enum class WorldWindHoldCancelReason { MOVEMENT, RELEASE, MULTITOUCH, INTERRUPTED }

internal class WorldWindHoldMetrics {
    val starts = AtomicLong()
    val activations = AtomicLong()
    val movementCancellations = AtomicLong()
    val releaseCancellations = AtomicLong()
    val multitouchCancellations = AtomicLong()
    val interruptedCancellations = AtomicLong()
    val activationDelayTotalMs = AtomicLong()
    val activationDelayMaxMs = AtomicLong()

    fun logFields(): String =
        "hold_starts=${starts.get()} hold_activations=${activations.get()} " +
            "hold_cancel_movement=${movementCancellations.get()} hold_cancel_release=${releaseCancellations.get()} " +
            "hold_cancel_multitouch=${multitouchCancellations.get()} " +
            "hold_cancel_interrupted=${interruptedCancellations.get()} " +
            "hold_activation_ms_total=${activationDelayTotalMs.get()} " +
            "hold_activation_ms_max=${activationDelayMaxMs.get()}"
}

internal class WorldWindHoldGesture(
    private val tolerance: Float,
) {
    val metrics = WorldWindHoldMetrics()
    private var pending = false
    private var downX = 0f
    private var downY = 0f
    var lastX = 0f
        private set
    var lastY = 0f
        private set

    fun begin(
        x: Float,
        y: Float,
    ) {
        cancel(WorldWindHoldCancelReason.INTERRUPTED)
        downX = x
        downY = y
        lastX = x
        lastY = y
        pending = true
        metrics.starts.incrementAndGet()
    }

    fun move(
        x: Float,
        y: Float,
    ): Boolean {
        lastX = x
        lastY = y
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy > tolerance * tolerance) cancel(WorldWindHoldCancelReason.MOVEMENT)
        return pending
    }

    fun cancel(reason: WorldWindHoldCancelReason) {
        if (!pending) return
        pending = false
        when (reason) {
            WorldWindHoldCancelReason.MOVEMENT -> metrics.movementCancellations
            WorldWindHoldCancelReason.RELEASE -> metrics.releaseCancellations
            WorldWindHoldCancelReason.MULTITOUCH -> metrics.multitouchCancellations
            WorldWindHoldCancelReason.INTERRUPTED -> metrics.interruptedCancellations
        }.incrementAndGet()
    }

    fun activate(delayMs: Long): Boolean {
        if (!pending) return false
        pending = false
        val delay = delayMs.coerceAtLeast(0L)
        metrics.activations.incrementAndGet()
        metrics.activationDelayTotalMs.addAndGet(delay)
        metrics.activationDelayMaxMs.updateAndGet { maxOf(it, delay) }
        return true
    }
}
