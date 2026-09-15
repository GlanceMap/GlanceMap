package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ScreenStateDiagnosticsTest {
    @Before
    fun setUp() {
        ScreenStateDiagnostics.clear(nowElapsedMs = 0L)
        ScreenStateDiagnostics.configure(captureActive = false, nowElapsedMs = 0L)
    }

    @After
    fun tearDown() {
        ScreenStateDiagnostics.clear(nowElapsedMs = 0L)
        ScreenStateDiagnostics.configure(captureActive = false, nowElapsedMs = 0L)
    }

    @Test
    fun summarySeparatesInteractiveAmbientAndScreenOffDurations() {
        ScreenStateDiagnostics.configure(
            captureActive = true,
            initialDisplayState = ScreenStateDiagnostics.DisplayState.INTERACTIVE,
            initialAppForeground = true,
            nowElapsedMs = 1_000L,
        )
        ScreenStateDiagnostics.updateDisplayState(
            displayState = ScreenStateDiagnostics.DisplayState.AMBIENT,
            nowElapsedMs = 1_100L,
        )
        ScreenStateDiagnostics.updateDisplayState(
            displayState = ScreenStateDiagnostics.DisplayState.OFF,
            nowElapsedMs = 1_400L,
        )
        ScreenStateDiagnostics.updateAppForeground(isForeground = false, nowElapsedMs = 1_450L)
        ScreenStateDiagnostics.configure(captureActive = false, nowElapsedMs = 2_000L)

        val summary = ScreenStateDiagnostics.summary(nowElapsedMs = 2_000L)

        assertEquals(1_000L, summary.captureDurationMs)
        assertEquals(100L, summary.interactiveDurationMs)
        assertEquals(300L, summary.ambientDurationMs)
        assertEquals(600L, summary.offDurationMs)
        assertEquals(450L, summary.appForegroundDurationMs)
        assertEquals(2, summary.displayTransitionCount)
        assertEquals(1, summary.appForegroundTransitionCount)
        assertEquals(ScreenStateDiagnostics.DisplayState.OFF, summary.currentDisplayState)
        assertFalse(summary.currentAppForeground ?: true)
    }

    @Test
    fun reconciliationTracksMismatchSamplesAndDuration() {
        ScreenStateDiagnostics.configure(
            captureActive = true,
            nowElapsedMs = 1_000L,
        )
        ScreenStateDiagnostics.reconcileScreenState(
            reportedIsInteractive = true,
            actualIsInteractive = false,
            nowElapsedMs = 1_100L,
        )
        ScreenStateDiagnostics.reconcileScreenState(
            reportedIsInteractive = true,
            actualIsInteractive = false,
            nowElapsedMs = 1_500L,
        )
        ScreenStateDiagnostics.reconcileScreenState(
            reportedIsInteractive = true,
            actualIsInteractive = true,
            nowElapsedMs = 1_700L,
        )
        ScreenStateDiagnostics.configure(captureActive = false, nowElapsedMs = 2_000L)

        val summary = ScreenStateDiagnostics.summary(nowElapsedMs = 2_000L)

        assertEquals(3L, summary.screenStateReconciliationSampleCount)
        assertEquals(2L, summary.screenStateMismatchSampleCount)
        assertEquals(2L, summary.screenStateInteractiveReportedWhileDeviceOffSampleCount)
        assertEquals(0L, summary.screenStateNonInteractiveReportedWhileDeviceOnSampleCount)
        assertEquals(600L, summary.screenStateObservedMismatchDurationMs)
        assertEquals(600L, summary.screenStateMaxObservedMismatchDurationMs)
        assertEquals("interactive_reported_while_device_off", summary.screenStateLastObservedMismatchType)
    }
}
