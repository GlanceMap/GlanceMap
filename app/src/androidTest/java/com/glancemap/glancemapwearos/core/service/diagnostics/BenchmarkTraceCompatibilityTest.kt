package com.glancemap.glancemapwearos.core.service.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenchmarkTraceCompatibilityTest {
    @Test
    fun asyncMarkersWorkOnTheMinimumSupportedApi() {
        val first = BenchmarkTrace.beginAsync("compatibility-".repeat(20))
        val second = BenchmarkTrace.beginAsync("compatibility-second")
        try {
            assertNotEquals(first.cookie, second.cookie)
        } finally {
            BenchmarkTrace.endAsync(second)
            BenchmarkTrace.endAsync(first)
        }
    }
}
