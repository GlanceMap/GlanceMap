package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ScreenOffActivityDiagnosticsTest {
    @Before
    fun enableCounters() {
        ScreenOffActivityDiagnostics.configure(enabled = true)
    }

    @After
    fun resetCounters() {
        ScreenOffActivityDiagnostics.configure(enabled = false)
    }

    @Test
    fun snapshotReportsCountsAndResetsTheNextWindow() {
        ScreenOffActivityDiagnostics.recordOrientationFrame(isInteractive = true)
        ScreenOffActivityDiagnostics.recordOrientationFrame(isInteractive = false)
        ScreenOffActivityDiagnostics.recordLiveHudTick()
        ScreenOffActivityDiagnostics.recordDebugOverlayTick()
        ScreenOffActivityDiagnostics.recordMapRedrawRequest()
        ScreenOffActivityDiagnostics.recordMapViewportCallback()
        ScreenOffActivityDiagnostics.recordLocationCallback()
        ScreenOffActivityDiagnostics.recordCompassCallback()
        ScreenOffActivityDiagnostics.recordDataLayerCallback()

        val snapshot = ScreenOffActivityDiagnostics.snapshotAndReset()

        assertEquals(2L, snapshot.orientationFrameCount)
        assertEquals(1L, snapshot.orientationFrameNonInteractiveCount)
        assertEquals(1L, snapshot.liveHudTickCount)
        assertEquals(1L, snapshot.debugOverlayTickCount)
        assertEquals(1L, snapshot.mapRedrawRequestCount)
        assertEquals(1L, snapshot.mapViewportCallbackCount)
        assertEquals(1L, snapshot.locationCallbackCount)
        assertEquals(1L, snapshot.compassCallbackCount)
        assertEquals(1L, snapshot.dataLayerCallbackCount)

        val nextWindow = ScreenOffActivityDiagnostics.snapshotAndReset()
        assertEquals(0L, nextWindow.orientationFrameCount)
        assertEquals(0L, nextWindow.orientationFrameNonInteractiveCount)
        assertEquals(0L, nextWindow.liveHudTickCount)
        assertEquals(0L, nextWindow.debugOverlayTickCount)
        assertEquals(0L, nextWindow.mapRedrawRequestCount)
        assertEquals(0L, nextWindow.mapViewportCallbackCount)
        assertEquals(0L, nextWindow.locationCallbackCount)
        assertEquals(0L, nextWindow.compassCallbackCount)
        assertEquals(0L, nextWindow.dataLayerCallbackCount)
    }

    @Test
    fun ignoresEventsWhenCaptureIsDisabled() {
        ScreenOffActivityDiagnostics.configure(enabled = false)
        ScreenOffActivityDiagnostics.recordCompassCallback()

        assertEquals(0L, ScreenOffActivityDiagnostics.snapshotAndReset().compassCallbackCount)
    }
}
