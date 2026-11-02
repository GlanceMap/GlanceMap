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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class PhoneWorldWindTilePipelineTest {
    @get:Rule val folder = TemporaryFolder()

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
    fun diskCacheSurvivesANewViewButNeverReusesATileForChangedInputs() {
        val directory = folder.newFolder()
        val key = PhoneWorldWindTileKey("map-a", "theme-a", 1f, PhoneWorldWindTile(16, 1, 2))
        PhoneWorldWindDiskTileCache(directory, "1").put(key, byteArrayOf(1, 2, 3))
        val reopened = PhoneWorldWindDiskTileCache(directory, "1")
        assertEquals(listOf<Byte>(1, 2, 3), reopened.get(key)?.toList())
        assertEquals(3L, reopened.usedBytes())
        for (changed in listOf(
            key.copy(resolution = 512),
            key.copy(mapIdentity = "map-a:replaced"),
            key.copy(themeIdentity = "theme-b"),
            key.copy(scale = 2f),
            key.copy(tile = PhoneWorldWindTile(16, 2, 1)),
        )) {
            assertNull(reopened.get(changed))
        }
        assertNull(PhoneWorldWindDiskTileCache(directory, "2").get(key))
        reopened.remove(key)
        assertNull(reopened.get(key))
        assertEquals(0L, reopened.usedBytes())
    }

    @Test
    fun diskCacheRemovesLeastRecentlyUsedTilesBelowItsLimit() {
        val directory = folder.newFolder()
        var evicted = 0
        val cache = PhoneWorldWindDiskTileCache(directory, "1", maximumBytes = 25, onEviction = { evicted++ })
        val keys = (0 until 3).map { PhoneWorldWindTileKey("map", "theme", 1f, PhoneWorldWindTile(10, it, 0)) }
        keys.take(2).forEachIndexed { index, key ->
            cache.put(key, ByteArray(10))
            val written = directory.listFiles()!!.single { it.name.endsWith("_10_${index}_0_256.png") }
            written.setLastModified(1_000_000L + index)
        }
        cache.get(keys[0])
        cache.put(keys[2], ByteArray(10))
        assertEquals(1, evicted)
        assertNull(cache.get(keys[1]))
        assertEquals(10, cache.get(keys[0])?.size)
        assertEquals(20L, cache.usedBytes())
        assertEquals(emptyList<String>(), directory.list()!!.filter { it.endsWith(".tmp") })
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

    @Test
    fun failedTileIsReleasedOncePerPendingRetryAndCanFailAgain() {
        val released = mutableListOf<Int>()
        var scheduled = 0
        runBlocking {
            val retry = PhoneWorldWindTileRetry(this, 1L, onScheduled = { scheduled++ }) { released += it }
            retry.schedule(7)
            retry.schedule(7)
            retry.schedule(8)
        }
        assertEquals(listOf(7, 8), released.sorted())
        assertEquals(2, scheduled)
        runBlocking {
            val retry = PhoneWorldWindTileRetry(this, 1L) { released += it }
            retry.schedule(7)
        }
        assertEquals(3, released.size)
    }

    @Test
    fun frameStatsReportCountSlowFramesAndWorstCase() {
        val stats = PhoneWorldWindFrameStats(slowNanos = 10L)
        listOf(5L, 11L, 2_000_000L).forEach(stats::record)
        assertEquals("gl_frames=3 gl_slow=2 gl_max_ms=2", stats.summary("gl"))
    }

    @Test
    fun closedViewDropsPendingRetries() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val released = AtomicInteger()
            PhoneWorldWindTileRetry(scope, 60_000L, onRetry = { released.incrementAndGet() }).schedule(7)
            scope.cancel()
            assertEquals(0, released.get())
        }
}
