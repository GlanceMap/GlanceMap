package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

internal data class NavigateExpandedOverlayState(
    val turnByTurnFullScreenExpanded: Boolean,
    val recordingDashboardFullScreenExpanded: Boolean,
    val combinedGuidanceRecordingFullScreenExpanded: Boolean,
    val suppressMapRenderingForGuidance: Boolean,
    val effectiveRecordingActionPromptRequestToken: Long,
    val overlayOwnsRotary: Boolean,
    val onTurnByTurnExpandedChange: (Boolean) -> Unit,
    val onRecordingExpandedChange: (Boolean) -> Unit,
    val onCombinedGuidanceRecordingExpandedChange: (Boolean) -> Unit,
    val requestRecordingActionPrompt: () -> Unit,
)

@Composable
internal fun rememberNavigateExpandedOverlayState(
    focusRequester: FocusRequester,
    backButtonExitsNavigation: Boolean,
    turnByTurnGuidanceActive: Boolean,
    traceRecordingActive: Boolean,
    recordingActionPromptRequestToken: Long,
): NavigateExpandedOverlayState {
    var turnByTurnFullScreenExpanded by remember { mutableStateOf(false) }
    var recordingDashboardFullScreenExpanded by remember { mutableStateOf(false) }
    var combinedGuidanceRecordingFullScreenExpanded by remember { mutableStateOf(false) }
    var localRecordingActionPromptRequestToken by remember { mutableLongStateOf(0L) }
    val effectiveRecordingActionPromptRequestToken =
        maxOf(recordingActionPromptRequestToken, localRecordingActionPromptRequestToken)
    val suppressMapRenderingForGuidance =
        (turnByTurnGuidanceActive && turnByTurnFullScreenExpanded) ||
            (traceRecordingActive && recordingDashboardFullScreenExpanded) ||
            combinedGuidanceRecordingFullScreenExpanded

    LaunchedEffect(turnByTurnGuidanceActive) {
        if (!turnByTurnGuidanceActive) {
            turnByTurnFullScreenExpanded = false
            combinedGuidanceRecordingFullScreenExpanded = false
        }
    }
    LaunchedEffect(traceRecordingActive) {
        if (!traceRecordingActive) {
            recordingDashboardFullScreenExpanded = false
            combinedGuidanceRecordingFullScreenExpanded = false
            localRecordingActionPromptRequestToken = 0L
        }
    }
    BackHandler(
        enabled =
            backButtonExitsNavigation &&
                (
                    turnByTurnFullScreenExpanded ||
                        recordingDashboardFullScreenExpanded ||
                        combinedGuidanceRecordingFullScreenExpanded
                ),
    ) {
        turnByTurnFullScreenExpanded = false
        recordingDashboardFullScreenExpanded = false
        combinedGuidanceRecordingFullScreenExpanded = false
    }
    LaunchedEffect(
        turnByTurnFullScreenExpanded,
        recordingDashboardFullScreenExpanded,
        combinedGuidanceRecordingFullScreenExpanded,
    ) {
        if (
            !turnByTurnFullScreenExpanded &&
            !recordingDashboardFullScreenExpanded &&
            !combinedGuidanceRecordingFullScreenExpanded
        ) {
            focusRequester.requestFocus()
        }
    }
    return NavigateExpandedOverlayState(
        turnByTurnFullScreenExpanded = turnByTurnFullScreenExpanded,
        recordingDashboardFullScreenExpanded = recordingDashboardFullScreenExpanded,
        combinedGuidanceRecordingFullScreenExpanded = combinedGuidanceRecordingFullScreenExpanded,
        suppressMapRenderingForGuidance = suppressMapRenderingForGuidance,
        effectiveRecordingActionPromptRequestToken = effectiveRecordingActionPromptRequestToken,
        overlayOwnsRotary =
            turnByTurnFullScreenExpanded ||
                recordingDashboardFullScreenExpanded ||
                combinedGuidanceRecordingFullScreenExpanded,
        onTurnByTurnExpandedChange = { expanded -> turnByTurnFullScreenExpanded = expanded },
        onRecordingExpandedChange = { expanded -> recordingDashboardFullScreenExpanded = expanded },
        onCombinedGuidanceRecordingExpandedChange = { expanded ->
            combinedGuidanceRecordingFullScreenExpanded = expanded
        },
        requestRecordingActionPrompt = { localRecordingActionPromptRequestToken = System.currentTimeMillis() },
    )
}
