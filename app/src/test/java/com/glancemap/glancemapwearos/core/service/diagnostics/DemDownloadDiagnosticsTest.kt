package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DemDownloadDiagnosticsTest {
    @Before
    fun setUp() {
        DemDownloadDiagnostics.clear()
    }

    @After
    fun tearDown() {
        DemDownloadDiagnostics.clear()
    }

    @Test
    fun summarySeparatesReadyUnavailableAndPartialTerminalResults() {
        DemDownloadDiagnostics.record(
            event = "complete",
            detail = "source=mapsforge_dem3 status=complete_with_unavailable",
        )
        DemDownloadDiagnostics.record(
            event = "complete",
            detail = "source=mapzen_skadi_1s status=partial",
        )

        val summary = DemDownloadDiagnostics.summary()

        assertEquals(2, summary.terminalCount)
        assertEquals(0, summary.readyCount)
        assertEquals(1, summary.completeWithUnavailableCount)
        assertEquals(1, summary.partialCount)
        assertEquals("events_with_failures", summary.activityState)
        assertEquals(
            listOf(
                DemDownloadSourceTerminalSummary(
                    sourceId = "mapsforge_dem3",
                    terminalCount = 1,
                    readyCount = 0,
                    completeWithUnavailableCount = 1,
                    partialCount = 0,
                    terminalFailureCount = 0,
                ),
                DemDownloadSourceTerminalSummary(
                    sourceId = "mapzen_skadi_1s",
                    terminalCount = 1,
                    readyCount = 0,
                    completeWithUnavailableCount = 0,
                    partialCount = 1,
                    terminalFailureCount = 0,
                ),
            ),
            summary.terminalSummariesBySource,
        )
    }
}
