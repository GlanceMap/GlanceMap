package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMapUserPoiStoreTest {
    @Test
    fun blankNameFallsBackToPoint() {
        assertEquals("Point", normalizePhoneUserPoiName("   "))
    }

    @Test
    fun nameIsTrimmedAndBounded() {
        val normalized = normalizePhoneUserPoiName("  ${"x".repeat(100)}  ")

        assertEquals(80, normalized.length)
        assertEquals("x".repeat(80), normalized)
    }
}
