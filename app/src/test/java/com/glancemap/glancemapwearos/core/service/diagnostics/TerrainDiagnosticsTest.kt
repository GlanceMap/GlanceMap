package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainDiagnosticsTest {
    @Test
    fun correlationIdsRemainMonotonicAfterClear() {
        TerrainDiagnostics.clear()
        val first = TerrainDiagnostics.nextCorrelationId()

        TerrainDiagnostics.clear()
        val second = TerrainDiagnostics.nextCorrelationId()

        assertTrue(second > first)
        TerrainDiagnostics.clear()
    }
}
