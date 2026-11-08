package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWindGridCacheTest {
    @Test
    fun reusesExactGridsWithoutSharingMutableArrays() {
        val cache = WorldWindGridCache(16)
        val original = shortArrayOf(1, 2, 3, 4)
        cache.put(1L, original)
        original[0] = 99
        val cached = cache.get(1L)!!
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), cached)
        cached[1] = 99
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), cache.get(1L))
        assertEquals(2L, cache.hits.get())
        assertEquals(8L, cache.retainedBytes.get())
    }

    @Test
    fun evictsLeastRecentlyUsedAndNeverCachesIncompleteGrids() {
        val cache = WorldWindGridCache(16)
        cache.put(1L, shortArrayOf(1, 1, 1, 1))
        cache.put(2L, shortArrayOf(2, 2, 2, 2))
        cache.get(1L)
        cache.put(3L, shortArrayOf(3, 3, 3, 3))
        assertNull(cache.get(2L))
        assertArrayEquals(shortArrayOf(1, 1, 1, 1), cache.get(1L))
        cache.put(4L, shortArrayOf(Short.MIN_VALUE, 1, 2, 3))
        assertNull(cache.get(4L))
        assertEquals(16L, cache.retainedBytes.get())
        cache.clear()
        assertEquals(0L, cache.retainedBytes.get())
        assertNull(cache.get(1L))
    }

    @Test
    fun ignoresAGridLargerThanTheBudget() {
        val cache = WorldWindGridCache(4)
        cache.put(1L, shortArrayOf(1, 2, 3))
        assertNull(cache.get(1L))
        assertEquals(0L, cache.retainedBytes.get())
    }

    @Test
    fun changingDemGenerationInvalidatesCachedGrids() {
        val cache = WorldWindGridCache(16)
        assertFalse(cache.prepareGeneration(1))
        cache.put(1L, shortArrayOf(1, 2))
        assertFalse(cache.prepareGeneration(1))
        assertArrayEquals(shortArrayOf(1, 2), cache.get(1L))
        assertTrue(cache.prepareGeneration(2))
        assertNull(cache.get(1L))
        assertEquals(0L, cache.retainedBytes.get())
    }
}
