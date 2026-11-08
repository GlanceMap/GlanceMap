package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWindViewPriorityGateTest {
    @Test
    fun `newer views run first and requests within a view keep their order`() =
        runBlocking {
            val gate = WorldWindViewPriorityGate()
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val holder = launch { gate.withPermit(1) { release.await() } }
            yield()
            val requests = listOf(1L to "old-coarse", 1L to "old-detail", 2L to "new-coarse", 2L to "new-detail")
            val waiters =
                requests.map { (view, name) ->
                    launch { gate.withPermit(view) { order += name } }.also { yield() }
                }
            assertEquals(4, gate.waitingCount())

            release.complete(Unit)
            holder.join()
            waiters.forEach { it.join() }

            assertEquals(listOf("new-coarse", "new-detail", "old-coarse", "old-detail"), order)
            assertEquals(0, gate.waitingCount())
        }

    @Test
    fun `a cancelled waiter does not hold the permit`() =
        runBlocking {
            val gate = WorldWindViewPriorityGate()
            val release = CompletableDeferred<Unit>()
            val holder = launch { gate.withPermit(1) { release.await() } }
            yield()
            val cancelled = launch { gate.withPermit(5) { error("must not run") } }
            yield()
            var ran = false
            val next = launch { gate.withPermit(1) { ran = true } }
            yield()

            cancelled.cancel()
            release.complete(Unit)
            holder.join()
            next.join()

            assertTrue(ran)
            assertEquals(0, gate.waitingCount())
        }

    @Test
    fun `a tile is drawn only while the latest frame still requests it`() {
        val key = WorldWindTileKey("map", "theme", 1f, 14, 8_700, 5_700, 256)
        val moved = key.copy(x = 8_701)
        assertTrue(worldWindTileWanted(null, key))
        assertTrue(worldWindTileWanted(setOf(key, moved), key))
        assertFalse(worldWindTileWanted(setOf(moved), key))
    }
}
