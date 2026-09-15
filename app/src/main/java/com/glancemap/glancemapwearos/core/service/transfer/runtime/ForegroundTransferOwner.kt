package com.glancemap.glancemapwearos.core.service.transfer.runtime

import java.util.concurrent.atomic.AtomicInteger

internal class ForegroundTransferOwner {
    private val ownerStartId = AtomicInteger(NO_OWNER)

    fun reserveIfAvailable(startId: Int): Boolean = ownerStartId.compareAndSet(NO_OWNER, startId)

    fun isOwner(startId: Int): Boolean = foregroundTimeoutMatchesStartId(startId, ownerStartId.get())

    fun currentStartId(): Int = ownerStartId.get()

    fun replace(startId: Int): Int = ownerStartId.getAndSet(startId)

    fun restoreIfOwner(
        startId: Int,
        previousOwnerStartId: Int,
    ): Boolean = ownerStartId.compareAndSet(startId, previousOwnerStartId)

    fun release(
        startId: Int,
        remainingTransfers: Int,
    ): Boolean {
        if (remainingTransfers <= 0) {
            return ownerStartId.compareAndSet(startId, NO_OWNER)
        }
        ownerStartId.compareAndSet(startId, NO_OWNER)
        return false
    }

    fun clearIfOwner(startId: Int): Boolean = ownerStartId.compareAndSet(startId, NO_OWNER)

    private companion object {
        const val NO_OWNER = -1
    }
}
