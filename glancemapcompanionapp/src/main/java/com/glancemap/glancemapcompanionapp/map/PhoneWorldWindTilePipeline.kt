package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PHONE_WORLDWIND_MIB = 1024 * 1024
internal const val PHONE_WORLDWIND_TILE_RETRY_MS = 30_000L

/** WorldWind never retries a failed factory image itself, so each failed source is released after a delay. */
internal class PhoneWorldWindTileRetry(
    private val scope: CoroutineScope,
    private val delayMillis: Long = PHONE_WORLDWIND_TILE_RETRY_MS,
    private val onScheduled: () -> Unit = {},
    private val onRetry: (Int) -> Unit,
) {
    private val pending = mutableSetOf<Int>()

    fun schedule(resourceId: Int) {
        if (!synchronized(pending) { pending.add(resourceId) }) return
        onScheduled()
        scope.launch {
            delay(delayMillis)
            synchronized(pending) { pending.remove(resourceId) }
            onRetry(resourceId)
        }
    }
}

/** Budget decoded-equivalent pixels so 512px tiles cannot silently consume four times the cache budget. */
internal fun phoneWorldWindCacheCapacity(maxHeapBytes: Long): Int = (maxHeapBytes / 8).coerceIn(4L * PHONE_WORLDWIND_MIB, 64L * PHONE_WORLDWIND_MIB).toInt()

/** WorldWind's tile level is a stable proxy for visible texture detail; the same tile never changes bands. */
internal fun phoneWorldWindTileResolution(
    zoom: Int,
    density: Float,
    maxHeapBytes: Long,
): Int = if (zoom >= 16 && density >= 2f && maxHeapBytes >= 256L * PHONE_WORLDWIND_MIB) 512 else 256

/** Independent shared jobs let a cancelled waiter leave without cancelling other consumers. */
internal class PhoneWorldWindTileCoalescer<K, V>(
    private val scope: CoroutineScope,
    private val onCoalesced: () -> Unit = {},
    private val onCancelled: () -> Unit = {},
) {
    private class Entry<V>(
        val deferred: Deferred<V?>,
        var waiters: Int,
    )

    private val lock = Any()
    private val inFlight = mutableMapOf<K, Entry<V>>()

    suspend fun get(
        key: K,
        render: suspend () -> V?,
    ): V? {
        val entry =
            synchronized(lock) {
                val existing = inFlight[key]
                if (existing != null) {
                    existing.waiters++
                    onCoalesced()
                    existing
                } else {
                    val created = Entry(scope.async(start = CoroutineStart.LAZY) { render() }, 1)
                    inFlight[key] = created
                    created.deferred.invokeOnCompletion {
                        synchronized(lock) { if (inFlight[key] === created) inFlight.remove(key) }
                    }
                    created
                }
            }
        entry.deferred.start()
        try {
            return entry.deferred.await()
        } finally {
            synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.deferred.isCompleted) {
                    if (inFlight[key] === entry) inFlight.remove(key)
                    entry.deferred.cancel()
                    onCancelled()
                }
            }
        }
    }

    fun pendingCount(): Int = synchronized(lock) { inFlight.size }
}
