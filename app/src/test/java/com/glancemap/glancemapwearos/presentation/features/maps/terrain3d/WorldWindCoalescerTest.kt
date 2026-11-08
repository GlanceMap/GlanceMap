package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWindCoalescerTest {
    @Test
    fun `duplicate requests share work and completed requests leave no entry`() =
        runBlocking {
            val coalescer = WorldWindCoalescer<String, Int>(this)
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val first =
                async {
                    coalescer.get("tile") {
                        calls++
                        release.await()
                        7
                    }
                }
            yield()
            val second =
                async {
                    coalescer.get("tile") {
                        calls++
                        8
                    }
                }
            yield()
            release.complete(Unit)
            assertEquals(7, first.await())
            assertEquals(7, second.await())
            assertEquals(1, calls)
            assertEquals(0, coalescer.pendingCount())
        }

    @Test
    fun `failed tile leaves no entry and can be retried`() =
        runBlocking {
            supervisorScope {
                val coalescer = WorldWindCoalescer<String, Int>(this)
                val failure = runCatching { coalescer.get("tile") { error("failed") } }
                assertTrue(failure.isFailure)
                assertEquals(0, coalescer.pendingCount())
                assertEquals(9, coalescer.get("tile") { 9 })
            }
        }
}
