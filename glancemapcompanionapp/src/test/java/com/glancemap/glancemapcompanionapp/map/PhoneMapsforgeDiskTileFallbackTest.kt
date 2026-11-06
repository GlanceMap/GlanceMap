package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.cache.FileSystemTileCache
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.queue.Job
import java.io.OutputStream

class PhoneMapsforgeDiskTileFallbackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var nowMs = 0L

    @Test
    fun diskTileFileMatchesTheMapsforgeFileSystemLayout() {
        val dir = temporaryFolder.newFolder("bucket")
        val job = Job(Tile(5, 7, 12, 512), false)
        FileSystemTileCache(10, dir, null, true).apply {
            put(job, FakeBitmap())
            destroy()
        }

        assertTrue(phoneMapsforgeDiskTileFile(dir, job).isFile)
    }

    @Test
    fun aTileOnDiskIsNotQueuedAndIsDrawnBeforeTheIndexCatchesUp() {
        val job = Job(Tile(1, 2, 12, 512), false)
        val bitmap = FakeBitmap()
        val reader = FakeReader(tiles = mapOf(job.key to bitmap))
        val firstLevel = InMemoryTileCache(4)
        val cache = cache(firstLevel, reader)

        assertTrue(cache.containsKey(job))
        val retrieved = cache.getImmediately(job)

        assertSame(bitmap, retrieved)
        assertTrue(firstLevel.containsKey(job))
        assertEquals(1, cache.stats.diskFallbackReads)
        assertEquals(0, cache.stats.renderedTilesStored)
        cache.destroy()
    }

    @Test
    fun anUnreadableDiskTileFallsBackToRendering() {
        val job = Job(Tile(1, 2, 12, 512), false)
        val reader = FakeReader(tiles = emptyMap(), present = setOf(job.key))
        val cache = cache(InMemoryTileCache(4), reader)

        assertNull(cache.getImmediately(job))
        assertFalse(cache.containsKey(job))
        assertEquals(1, cache.stats.diskFallbackFailures)
        cache.destroy()
    }

    @Test
    fun theFallbackStopsAfterItsWindowAndOnDestroy() {
        val job = Job(Tile(1, 2, 12, 512), false)
        val reader = FakeReader(tiles = mapOf(job.key to FakeBitmap()))
        val cache = cache(InMemoryTileCache(4), reader)

        nowMs = WINDOW_MS
        assertFalse(cache.containsKey(job))
        assertNull(cache.getImmediately(job))
        assertFalse(cache.stats.diskFallbackActive)
        assertEquals(0, reader.reads)
        cache.destroy()
    }

    @Test
    fun withoutAReaderTheCacheBehavesAsBeforeAndCountsRenderedTiles() {
        val job = Job(Tile(1, 2, 12, 512), false)
        val cache = PhoneMapsforgeTileCache(InMemoryTileCache(4), InMemoryTileCache(4))

        assertFalse(cache.containsKey(job))
        cache.setWorkingSet(setOf(job))
        cache.put(job, FakeBitmap())

        assertTrue(cache.containsKey(job))
        assertEquals(1, cache.stats.renderedTilesStored)
        assertFalse(cache.stats.persistent)
        cache.destroy()
    }

    private fun cache(
        firstLevel: InMemoryTileCache,
        reader: PhoneMapsforgeDiskTileReader,
    ) = PhoneMapsforgeTileCache(
        firstLevel,
        InMemoryTileCache(4),
        bucket =
            PhoneMapsforgePersistentBucket(
                reader = reader,
                onReleased = {},
                fallbackWindowMs = WINDOW_MS,
                clockMs = { nowMs },
            ),
    )

    private class FakeReader(
        private val tiles: Map<String, TileBitmap>,
        private val present: Set<String> = tiles.keys,
    ) : PhoneMapsforgeDiskTileReader {
        var reads = 0

        override fun hasTile(job: Job): Boolean = job.key in present

        override fun read(job: Job): TileBitmap? {
            reads += 1
            return tiles[job.key] ?: error("corrupt tile")
        }
    }

    private class FakeBitmap : TileBitmap {
        private var references = 1

        override fun compress(outputStream: OutputStream) = outputStream.write(ByteArray(32))

        override fun decrementRefCount() {
            references--
        }

        override fun getHeight(): Int = 512

        override fun getWidth(): Int = 512

        override fun incrementRefCount() {
            references++
        }

        override fun isDestroyed(): Boolean = references <= 0

        override fun scaleTo(
            width: Int,
            height: Int,
        ) = Unit

        override fun setBackgroundColor(color: Int) = Unit

        override fun getMutex(): Any = this

        override fun getTimestamp(): Long = 0L

        override fun isExpired(): Boolean = false

        override fun setExpiration(expiration: Long) = Unit

        override fun setTimestamp(timestamp: Long) = Unit
    }

    private companion object {
        const val WINDOW_MS = 10_000L
    }
}
