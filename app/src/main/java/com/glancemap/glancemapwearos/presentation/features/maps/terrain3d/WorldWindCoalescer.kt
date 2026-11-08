package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

internal class WorldWindCoalescer<K, V>(
    private val scope: CoroutineScope,
    private val onCoalesced: () -> Unit = {},
    private val onCancelled: () -> Unit = {},
) {
    private class Entry<V>(
        val deferred: Deferred<V?>,
        var waiters: Int,
    )

    private val inFlight = mutableMapOf<K, Entry<V>>()

    suspend fun get(
        key: K,
        render: suspend () -> V?,
    ): V? {
        val entry =
            synchronized(inFlight) {
                inFlight[key]?.also {
                    it.waiters++
                    onCoalesced()
                }
                    ?: Entry(scope.async(start = CoroutineStart.LAZY) { render() }, 1).also { created ->
                        inFlight[key] = created
                        created.deferred.invokeOnCompletion {
                            synchronized(inFlight) { if (inFlight[key] === created) inFlight.remove(key) }
                        }
                    }
            }
        entry.deferred.start()
        try {
            return entry.deferred.await()
        } finally {
            synchronized(inFlight) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.deferred.isCompleted) {
                    if (inFlight[key] === entry) inFlight.remove(key)
                    entry.deferred.cancel()
                    onCancelled()
                }
            }
        }
    }

    fun pendingCount(): Int = synchronized(inFlight) { inFlight.size }
}
