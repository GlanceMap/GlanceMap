package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDashboardVisibilityTest {
    @Test
    fun screenOffStopsPresentationWorkEvenWhenDashboardIsOpen() {
        assertFalse(
            shouldUpdateRecordingDashboardPresentation(
                isScreenInteractive = false,
                suppressed = false,
                expandedTransitionCurrent = true,
                expandedTransitionTarget = true,
                stopPromptVisible = false,
            ),
        )
    }

    @Test
    fun suppressedOverlayStopsPresentationWorkWithoutChangingItsPopupResetPolicy() {
        assertFalse(
            shouldUpdateRecordingDashboardPresentation(
                isScreenInteractive = true,
                suppressed = true,
                expandedTransitionCurrent = true,
                expandedTransitionTarget = true,
                stopPromptVisible = true,
            ),
        )
    }

    @Test
    fun wakingAnOpenDashboardReenablesImmediatePresentationWork() {
        assertTrue(
            shouldUpdateRecordingDashboardPresentation(
                isScreenInteractive = true,
                suppressed = false,
                expandedTransitionCurrent = true,
                expandedTransitionTarget = true,
                stopPromptVisible = false,
            ),
        )
    }

    @Test
    fun dashboardOpenAndCloseTransitionsKeepSnapshotAvailableDuringAnimation() {
        assertTrue(
            shouldUpdateRecordingDashboardPresentation(
                isScreenInteractive = true,
                suppressed = false,
                expandedTransitionCurrent = false,
                expandedTransitionTarget = true,
                stopPromptVisible = false,
            ),
        )
        assertTrue(
            shouldUpdateRecordingDashboardPresentation(
                isScreenInteractive = true,
                suppressed = false,
                expandedTransitionCurrent = true,
                expandedTransitionTarget = false,
                stopPromptVisible = false,
            ),
        )
    }

    @Test
    fun stopPromptKeepsPresentationWorkAvailableWhenDashboardIsClosed() {
        assertTrue(
            shouldUpdateRecordingDashboardPresentation(
                isScreenInteractive = true,
                suppressed = false,
                expandedTransitionCurrent = false,
                expandedTransitionTarget = false,
                stopPromptVisible = true,
            ),
        )
    }

    @Test
    fun compactCombinedPopupDoesNotNeedRecordingPresentationWork() {
        assertFalse(
            shouldUpdateRecordingDashboardPresentation(
                isScreenInteractive = true,
                suppressed = false,
                expandedTransitionCurrent = false,
                expandedTransitionTarget = false,
                stopPromptVisible = false,
            ),
        )
    }
}
