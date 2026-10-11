package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.queue.Job
import java.io.OutputStream

class PhoneMapsforgeTileCacheConfigTest {
    @Test
    fun denseTilesStayWithinTheRegularMemoryBudget() {
        val config = phoneMapsforgeTileCacheConfig(768, 4, 512, 512L * MEBIBYTE)

        assertFalse(config.constrainedMemory)
        assertEquals(32L * MEBIBYTE, config.memoryBudgetBytes)
        assertEquals(14, config.firstLevelTiles)
        assertTrue(config.firstLevelTiles * 768L * 768L * 4L <= config.memoryBudgetBytes)
    }

    @Test
    fun aConstrainedMemoryClassUsesTheSmallerBudget() {
        val config = phoneMapsforgeTileCacheConfig(768, 4, 128, 512L * MEBIBYTE)

        assertTrue(config.constrainedMemory)
        assertEquals(8L * MEBIBYTE, config.memoryBudgetBytes)
        assertEquals(3, config.firstLevelTiles)
    }

    @Test
    fun aSmallHeapUsesTheConstrainedBudgetEvenWithALargeMemoryClass() {
        val config = phoneMapsforgeTileCacheConfig(768, 4, 512, 160L * MEBIBYTE)

        assertTrue(config.constrainedMemory)
        assertEquals(8L * MEBIBYTE, config.memoryBudgetBytes)
        assertEquals(3, config.firstLevelTiles)
    }

    @Test
    fun tileCapacityAccountsForTheBitmapPixelFormat() {
        val rgb565 = phoneMapsforgeTileCacheConfig(768, 2, 512, 512L * MEBIBYTE)
        val argb8888 = phoneMapsforgeTileCacheConfig(768, 4, 512, 512L * MEBIBYTE)

        assertEquals(28, rgb565.firstLevelTiles)
        assertEquals(14, argb8888.firstLevelTiles)
    }

    @Test
    fun smallTilesRetainTheExistingMaximumCapacities() {
        val regular = phoneMapsforgeTileCacheConfig(128, 2, 512, 512L * MEBIBYTE)
        val constrained = phoneMapsforgeTileCacheConfig(128, 2, 128, 512L * MEBIBYTE)

        assertEquals(256, regular.firstLevelTiles)
        assertEquals(80, constrained.firstLevelTiles)
    }

    @Test
    fun retainedTilePixelsFitTheBudgetAcrossDensitiesAndHeapSizes() {
        listOf(256, 512, 768, 1_024).forEach { tileSize ->
            listOf(2, 4).forEach { bytesPerPixel ->
                listOf(128, 256, 512).forEach { heapMb ->
                    val config =
                        phoneMapsforgeTileCacheConfig(
                            tileSize,
                            bytesPerPixel,
                            memoryClassMb = heapMb,
                            maxHeapBytes = heapMb * MEBIBYTE,
                        )

                    assertTrue(config.firstLevelTiles > 0)
                    assertTrue(
                        config.firstLevelTiles * tileSize.toLong() * tileSize * bytesPerPixel <=
                            config.memoryBudgetBytes,
                    )
                }
            }
        }
    }

    @Test
    fun invalidTileDimensionsAndPixelFormatsAreRejected() {
        listOf(0 to 4, -256 to 4, 256 to 0, 256 to -2).forEach { (tileSize, bytesPerPixel) ->
            assertThrows(IllegalArgumentException::class.java) {
                phoneMapsforgeTileCacheConfig(tileSize, bytesPerPixel, 512, 512L * MEBIBYTE)
            }
        }
    }

    @Test
    fun aTileLargerThanTheBudgetIsRejectedInsteadOfExceedingIt() {
        assertThrows(IllegalArgumentException::class.java) {
            phoneMapsforgeTileCacheConfig(4_096, 4, 128, 128L * MEBIBYTE)
        }
    }

    @Test
    fun visibleTilesRemainReadableAfterEvictionFromTheFirstLevel() {
        val memory = InMemoryTileCache(1)
        val backing = InMemoryTileCache(3)
        val cache = PhoneMapsforgeTileCache(memory, backing)
        val tiles = (0..2).associate { Job(Tile(it, 0, 12, 256), false) to TestTileBitmap() }
        try {
            cache.setWorkingSet(tiles.keys)
            tiles.forEach { (job, bitmap) -> cache.put(job, bitmap) }
            assertFalse(memory.containsKey(tiles.keys.first()))
            cache.setWorkingSet(tiles.keys)
            tiles.forEach { (job, bitmap) ->
                val retrieved = cache.getImmediately(job)
                try {
                    assertSame(bitmap, retrieved)
                    assertEquals(1, cache.capacityFirstLevel)
                } finally {
                    retrieved?.decrementRefCount()
                }
            }
            assertNull(cache.getImmediately(Job(Tile(3, 0, 12, 256), false)))
        } finally {
            cache.destroy()
        }
    }

    private class TestTileBitmap : TileBitmap {
        private var references = 1
        private var timestamp = 0L

        override fun getMutex(): Any = this

        override fun incrementRefCount() {
            references++
        }

        override fun decrementRefCount() {
            references--
        }

        override fun isDestroyed(): Boolean = references == 0

        override fun getWidth(): Int = 256

        override fun getHeight(): Int = 256

        override fun getTimestamp(): Long = timestamp

        override fun setTimestamp(timestamp: Long) {
            this.timestamp = timestamp
        }

        override fun isExpired(): Boolean = false

        override fun setExpiration(expiration: Long) = Unit

        override fun compress(outputStream: OutputStream): Unit = error("Rendering is outside this cache test.")

        override fun scaleTo(
            width: Int,
            height: Int,
        ): Unit = error("Rendering is outside this cache test.")

        override fun setBackgroundColor(color: Int): Unit = error("Rendering is outside this cache test.")
    }

    private companion object {
        const val MEBIBYTE = 1024L * 1024L
    }
}
