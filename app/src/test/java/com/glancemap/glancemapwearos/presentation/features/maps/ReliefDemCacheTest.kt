package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ReliefDemCacheTest {
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
