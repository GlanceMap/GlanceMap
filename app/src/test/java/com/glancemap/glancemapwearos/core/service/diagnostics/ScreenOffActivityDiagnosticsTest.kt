package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        ScreenOffActivityDiagnostics.dataLayer.recordMessage()
        ScreenOffActivityDiagnostics.dataLayer.recordChannelOpened()
        ScreenOffActivityDiagnostics.dataLayer.recordPeerConnected()
        ScreenOffActivityDiagnostics.dataLayer.recordPeerDisconnected()
        ScreenOffActivityDiagnostics.dataLayer.recordLastEvent(
            DataLayerEventContext(
                type = "Message",
                path = "/glancemap/list_maps",
                displayInteractive = false,
                transferActive = false,
                activeTransferId = null,
            ),
        )

        val snapshot = ScreenOffActivityDiagnostics.snapshotAndReset()

        assertEquals(2L, snapshot.orientationFrameCount)
        assertEquals(1L, snapshot.orientationFrameNonInteractiveCount)
        assertEquals(1L, snapshot.liveHudTickCount)
        assertEquals(1L, snapshot.debugOverlayTickCount)
        assertEquals(1L, snapshot.mapRedrawRequestCount)
        assertEquals(1L, snapshot.mapViewportCallbackCount)
        assertEquals(1L, snapshot.locationCallbackCount)
        assertEquals(1L, snapshot.compassCallbackCount)
        assertEquals(4L, snapshot.dataLayerCallbackCount)
        assertEquals(1L, snapshot.dataLayerMessageCount)
        assertEquals(1L, snapshot.dataLayerChannelOpenedCount)
        assertEquals(1L, snapshot.dataLayerPeerConnectedCount)
        assertEquals(1L, snapshot.dataLayerPeerDisconnectedCount)
        assertEquals("/glancemap/list_maps", snapshot.lastDataLayerEvent?.path)

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
        assertEquals(0L, nextWindow.dataLayerMessageCount)
        assertEquals(0L, nextWindow.dataLayerChannelOpenedCount)
        assertEquals(0L, nextWindow.dataLayerPeerConnectedCount)
        assertEquals(0L, nextWindow.dataLayerPeerDisconnectedCount)
        assertNull(nextWindow.lastDataLayerEvent)
    }

    @Test
    fun ignoresEventsWhenCaptureIsDisabled() {
        ScreenOffActivityDiagnostics.configure(enabled = false)
        ScreenOffActivityDiagnostics.recordCompassCallback()

        assertEquals(0L, ScreenOffActivityDiagnostics.snapshotAndReset().compassCallbackCount)
    }

    @Test
    fun screenStateTransitionStartsANewCounterWindow() {
        ScreenOffActivityDiagnostics.recordLocationCallback()

        ScreenOffActivityDiagnostics.snapshotAndReset()
        ScreenOffActivityDiagnostics.recordLocationCallback()

        assertEquals(1L, ScreenOffActivityDiagnostics.snapshotAndReset().locationCallbackCount)
    }
}
