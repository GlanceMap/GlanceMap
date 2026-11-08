package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Runs one block at a time. Waiters requested for a newer view (higher [withPermit] `view`) go
 * first; within one view, requests keep their order, so WorldWind's coarse fallback tiles, which it
 * requests before their detailed descendants, still fill the screen first.
 */
internal class WorldWindViewPriorityGate {
    private class Waiter(
        val view: Long,
        val order: Long,
        val ticket: CompletableDeferred<Unit>,
    )

    private val waiting = mutableListOf<Waiter>()
    private var busy = false
    private var nextOrder = 0L

    suspend fun <T> withPermit(
        view: Long,
        block: suspend () -> T,
    ): T {
        val waiter =
            synchronized(this) {
                if (busy) {
                    Waiter(view, nextOrder++, CompletableDeferred()).also(waiting::add)
                } else {
                    busy = true
                    null
                }
            }
        if (waiter != null) {
            try {
                waiter.ticket.await()
            } catch (cancelled: CancellationException) {
                // A ticket granted while it was being cancelled still holds the permit; pass it on.
                synchronized(this) { if (!waiting.remove(waiter)) releaseLocked() }
                throw cancelled
            }
        }
        try {
            return block()
        } finally {
            synchronized(this) { releaseLocked() }
        }
    }

    fun waitingCount(): Int = synchronized(this) { waiting.size }

    private fun releaseLocked() {
        val next = waiting.minWithOrNull(compareByDescending<Waiter> { it.view }.thenBy { it.order })
        if (next == null) {
            busy = false
        } else {
            waiting.remove(next)
            next.ticket.complete(Unit)
        }
    }
}
