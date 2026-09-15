package com.glancemap.glancemapwearos.core.service.transfer.runtime

import java.util.concurrent.atomic.AtomicReference

internal class TerminalResultDelivery<T> {
    private val pending = AtomicReference<T?>(null)

    fun record(result: T): Boolean = pending.compareAndSet(null, result)

    fun deliverAfterCleanup(
        cleanup: () -> Unit,
        deliver: (T) -> Unit,
    ) {
        val result = pending.getAndSet(null)
        cleanup()
        result?.let(deliver)
    }
}
