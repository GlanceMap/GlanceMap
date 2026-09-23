package com.glancemap.glancemapwearos.core.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemSignatureStoreTest {
    @Test
    fun freshSignatureIsRecomputedWhenCacheIsMissing() {
        val decision =
            classifyDemSignatureCache(
                cachedSignaturePresent = false,
                lastScanMs = 0L,
                nowMs = 10_000L,
                maxAgeMs = 300_000L,
            )

        assertFalse(decision.cacheHit)
        assertNull(decision.cacheAgeMs)
    }

    @Test
    fun freshCachedSignatureIsReportedAsCacheHit() {
        val decision =
            classifyDemSignatureCache(
                cachedSignaturePresent = true,
                lastScanMs = 10_000L,
                nowMs = 20_000L,
                maxAgeMs = 300_000L,
            )

        assertTrue(decision.cacheHit)
        assertEquals(10_000L, decision.cacheAgeMs)
    }

    @Test
    fun agedCachedSignatureIsRecomputed() {
        val decision =
            classifyDemSignatureCache(
                cachedSignaturePresent = true,
                lastScanMs = 10_000L,
                nowMs = 310_001L,
                maxAgeMs = 300_000L,
            )

        assertFalse(decision.cacheHit)
        assertEquals(300_001L, decision.cacheAgeMs)
    }
}
