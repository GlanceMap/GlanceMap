package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ReliefDemCacheTest {
    @Test
    fun `retained byte telemetry tracks eviction and clear but preserves session peak`() {
        val stats = ReliefDemCacheStats()
        val cache = ReliefDemTileCache(2)
        cache["first"] = DemTileData(1, 2, ShortArray(4))
        stats.recordRetained(cache.retainedBytes())
        assertEquals(8L, stats.retainedBytes.get())
        cache["second"] = DemTileData(1, 2, ShortArray(8))
        stats.recordRetained(cache.retainedBytes())
        assertEquals(24L, stats.peakRetainedBytes.get())
        cache.trimToBytes(8)
        stats.recordRetained(cache.retainedBytes())
        assertEquals(16L, stats.retainedBytes.get())
        cache.clear()
        stats.recordRetained(cache.retainedBytes())
        assertEquals(0L, stats.retainedBytes.get())
        assertEquals(24L, stats.peakRetainedBytes.get())
    }

    @Test
    fun `byte limit retains recently used tiles and permits one oversized tile`() {
        val cache = ReliefDemTileCache(4)
        val small = DemTileData(1, 2, ShortArray(4))
        val large = DemTileData(1, 2, ShortArray(16))
        cache["first"] = small
        cache["second"] = small
        cache["third"] = small
        assertSame(small, cache["first"])
        cache.trimToBytes(16)
        assertEquals(setOf("first", "third"), cache.keys)

        cache["large"] = large
        cache.trimToBytes(16)
        assertEquals(setOf("large"), cache.keys)

        cache["missing"] = null
        cache.trimToBytes(16)
        assertEquals(setOf("large", "missing"), cache.keys)
    }

    @Test
    fun `cache evicts least recently used DEM tile at configured limit`() {
        val cache = ReliefDemTileCache(2)
        val first = DemTileData(1, 2, shortArrayOf(1, 2, 3, 4))
        val second = DemTileData(1, 2, shortArrayOf(5, 6, 7, 8))
        cache["first"] = first
        cache["second"] = second
        assertSame(first, cache["first"])
        cache["third"] = null
        assertFalse(cache.containsKey("second"))
        assertSame(first, cache["first"])
        assertEquals(2, cache.size)
    }
}
