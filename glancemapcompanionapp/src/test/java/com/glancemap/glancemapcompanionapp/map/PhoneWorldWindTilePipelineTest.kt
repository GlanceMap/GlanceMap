package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PhoneWorldWindTilePipelineTest {
    @Test
    fun cacheCapacityIsHeapAwareAndBounded() {
        val mib = 1024L * 1024
        assertEquals(8 * mib, phoneWorldWindCacheCapacity(64 * mib).toLong())
        assertEquals(16 * mib, phoneWorldWindCacheCapacity(128 * mib).toLong())
        assertEquals(32 * mib, phoneWorldWindCacheCapacity(256 * mib).toLong())
        assertEquals(64 * mib, phoneWorldWindCacheCapacity(2_048 * mib).toLong())
    }

    @Test
    fun stableTileLevelBandsUseHighResolutionOnlyOnCapableDevices() {
        val heap = 256L * 1024 * 1024
        assertEquals(256, phoneWorldWindTileResolution(15, 3f, heap))
        assertEquals(512, phoneWorldWindTileResolution(16, 3f, heap))
        assertEquals(512, phoneWorldWindTileResolution(17, 3f, heap))
        assertEquals(256, phoneWorldWindTileResolution(17, 1f, heap))
        assertEquals(256, phoneWorldWindTileResolution(17, 3f, heap / 2))
    }

    @Test
    fun resolutionAndIdentityArePartOfCacheKeyAndMemoryCost() {
        val key = PhoneWorldWindTileKey("map-a", "theme-a", 1f, PhoneWorldWindTile(16, 1, 2))
        assertNotEquals(key, key.copy(resolution = 512))
        assertNotEquals(key, key.copy(mapIdentity = "map-b"))
        assertNotEquals(key, key.copy(themeIdentity = "theme-b"))
        assertNotEquals(key, key.copy(scale = 2f))
        val cache = PhoneWorldWindTileCache(20, cost = { _, bytes -> bytes.size * 2 })
        cache.put(key, ByteArray(6))
        cache.put(key.copy(resolution = 512), ByteArray(6))
        assertNull(cache.get(key))
        assertEquals(12, cache.usedBytes())
        assertEquals(6, cache.storedBytes())
        assertEquals(1, cache.evictionCount())
    }

    @Test
    fun concurrentRequestsShareOneRenderAndFailuresAreRetryable() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val coalesced = CompletableDeferred<Unit>()
            val queue = PhoneWorldWindTileCoalescer<String, Int>(scope, onCoalesced = { coalesced.complete(Unit) })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val first =
                async {
                    queue.get("tile") {
                        calls.incrementAndGet()
                        started.complete(Unit)
                        release.await()
                        42
                    }
                }
            started.await()
            val second =
                async {
                    queue.get("tile") {
                        calls.incrementAndGet()
                        99
                    }
                }
            kotlinx.coroutines.withTimeout(5_000) { coalesced.await() }
            release.complete(Unit)
            assertEquals(42, first.await())
            assertEquals(42, second.await())
            assertEquals(1, calls.get())
            assertNull(queue.get("failed") { null })
            assertEquals(7, queue.get("failed") { 7 })
            scope.cancel()
        }

    @Test
    fun cancelledRequestDoesNotPoisonNextRequest() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val queue = PhoneWorldWindTileCoalescer<String, Int>(scope)
            val started = CompletableDeferred<Unit>()
            val pending =
                async {
                    queue.get("tile") {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        1
                    }
                }
            started.await()
            pending.cancelAndJoin()
            assertEquals(2, queue.get("tile") { 2 })
            scope.cancel()
        }

    @Test
    fun cancellingOneWaiterKeepsSharedRenderAlive() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val coalesced = CompletableDeferred<Unit>()
            val queue = PhoneWorldWindTileCoalescer<String, Int>(scope, onCoalesced = { coalesced.complete(Unit) })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first =
                async {
                    queue.get("tile") {
                        started.complete(Unit)
                        release.await()
                        42
                    }
                }
            started.await()
            val second = async { queue.get("tile") { 99 } }
            kotlinx.coroutines.withTimeout(5_000) { coalesced.await() }
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals(42, second.await())
            scope.cancel()
        }
}
