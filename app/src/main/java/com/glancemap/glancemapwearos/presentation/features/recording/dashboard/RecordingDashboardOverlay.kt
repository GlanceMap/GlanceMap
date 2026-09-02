package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.SwipeToDismissBox
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.RecordingScreenOffDiagnostics
import com.glancemap.glancemapwearos.data.repository.RECORDING_DASHBOARD_PAGE_SLOT_COUNT
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.defaultRecordingDashboardMetricSlotsForProfile
import com.glancemap.glancemapwearos.data.repository.newRecordingDashboardPageMetricSlotsForProfile
import com.glancemap.glancemapwearos.data.repository.normalizeRecordingDashboardMetricSlots
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.settings.OptionPickerDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionButtonRole
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialogButton
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// One lifecycle coordinator owns the recording overlay's mutually exclusive dialogs and effects.
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun BoxScope.RecordingDashboardOverlay(
    state: TraceRecordingUiState,
    metricSlots: List<String>,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    showRouteCompletePrompt: Boolean = false,
    onRouteCompletePromptDismiss: () -> Unit = {},
    suppressed: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopConfirmed: (String?) -> Unit,
    onDiscard: () -> Unit,
    onMetricSelected: (slotIndex: Int, metricId: String) -> Unit,
    expandRequestToken: Long,
    actionPromptRequestToken: Long,
    onExpandedChange: (Boolean) -> Unit,
) {
    if (!state.active && !state.saving) return

    var expanded by remember { mutableStateOf(false) }
    var showCompactControls by remember { mutableStateOf(false) }
    var showStopPrompt by remember { mutableStateOf(false) }
    var stopPromptPausedRecording by remember { mutableStateOf(false) }
    var metricPickerSlot by remember { mutableIntStateOf(NO_SELECTED_SLOT) }
    var dashboardPageIndex by remember { mutableIntStateOf(0) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastHandledActionPromptRequestToken by remember {
        mutableLongStateOf(actionPromptRequestToken)
    }
    var lastHandledExpandRequestToken by remember {
        mutableLongStateOf(expandRequestToken)
    }

    LaunchedEffect(state.active, state.paused, state.saving) {
        while (isActive && (state.active || state.saving)) {
            RecordingScreenOffDiagnostics.recordDashboardTick()
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    LaunchedEffect(suppressed) {
        if (suppressed) {
            expanded = false
            showCompactControls = false
            showStopPrompt = false
            stopPromptPausedRecording = false
            metricPickerSlot = NO_SELECTED_SLOT
            onExpandedChange(false)
        }
    }
    LaunchedEffect(expanded) {
        onExpandedChange(expanded)
    }
    LaunchedEffect(actionPromptRequestToken) {
        val shouldHandle =
            actionPromptRequestToken != 0L &&
                actionPromptRequestToken != lastHandledActionPromptRequestToken
        lastHandledActionPromptRequestToken = actionPromptRequestToken
        if (shouldHandle && state.active && !state.saving && !suppressed) {
            expanded = false
            showStopPrompt = false
            metricPickerSlot = NO_SELECTED_SLOT
            showCompactControls = true
        }
    }
    LaunchedEffect(expandRequestToken) {
        val shouldHandle =
            expandRequestToken != 0L &&
                expandRequestToken != lastHandledExpandRequestToken
        lastHandledExpandRequestToken = expandRequestToken
        if (shouldHandle && state.active && !state.saving && !suppressed) {
            DebugTelemetry.log(
                "TraceRecording",
                "event=dashboard_expand_token handled=true suppressed=$suppressed active=${state.active} saving=${state.saving}",
            )
            showCompactControls = false
            showStopPrompt = false
            expanded = true
        } else if (shouldHandle) {
            DebugTelemetry.log(
                "TraceRecording",
                "event=dashboard_expand_token handled=false suppressed=$suppressed active=${state.active} saving=${state.saving}",
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { onExpandedChange(false) }
    }
    if (suppressed) return

    val sessionProfile = state.activityProfile
    val slots = normalizedRecordingDashboardSlots(metricSlots, sessionProfile)
    val pageCount = (slots.size / RECORDING_DASHBOARD_PAGE_SLOT_COUNT).coerceAtLeast(1)
    LaunchedEffect(pageCount) {
        if (dashboardPageIndex >= pageCount) {
            dashboardPageIndex = pageCount - 1
        }
    }
    val snapshot =
        buildRecordingDashboardSnapshot(
            state = state,
            nowMillis = nowMillis,
            userWeightKg = userWeightKg,
            backpackWeightKg = backpackWeightKg,
            bikeWeightKg = bikeWeightKg,
            activityProfile = sessionProfile,
        )

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier =
            Modifier
                .align(Alignment.Center)
                .fillMaxSize(),
    ) {
        SwipeToDismissBox(onDismissed = { expanded = false }) { isBackground ->
            if (!isBackground) {
                ExpandedRecordingDashboard(
                    state = state,
                    slots =
                        slots
                            .drop(dashboardPageIndex * RECORDING_DASHBOARD_PAGE_SLOT_COUNT)
                            .take(RECORDING_DASHBOARD_PAGE_SLOT_COUNT),
                    pageIndex = dashboardPageIndex,
                    pageCount = pageCount,
                    snapshot = snapshot,
                    screenSize = screenSize,
                    isMetric = isMetric,
                    onSlotLongPress = { slotIndex ->
                        metricPickerSlot = dashboardPageIndex * RECORDING_DASHBOARD_PAGE_SLOT_COUNT + slotIndex
                    },
                    onPreviousPage = {
                        val nextPageIndex = (dashboardPageIndex - 1).coerceAtLeast(0)
                        if (nextPageIndex != dashboardPageIndex) {
                            dashboardPageIndex = nextPageIndex
                            logRecordingDashboardPageChange(nextPageIndex, pageCount, "swipe_down")
                        }
                    },
                    onNextPage = {
                        val nextPageIndex = (dashboardPageIndex + 1).coerceAtMost(pageCount - 1)
                        if (nextPageIndex != dashboardPageIndex) {
                            dashboardPageIndex = nextPageIndex
                            logRecordingDashboardPageChange(nextPageIndex, pageCount, "swipe_up")
                        }
                    },
                    onShowActions = {
                        expanded = false
                        showCompactControls = true
                    },
                    onDismiss = { expanded = false },
                )
            }
        }
    }

    if (!expanded) {
        if (showCompactControls) {
            CompactRecordingControls(
                state = state,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = recordingActionPromptTopPadding(screenSize)),
                onPauseResume = {
                    showCompactControls = false
                    if (state.paused) {
                        onResume()
                    } else {
                        onPause()
                    }
                },
                onStop = {
                    stopPromptPausedRecording = state.active && !state.paused && !state.saving
                    if (stopPromptPausedRecording) {
                        onPause()
                    }
                    showStopPrompt = true
                },
                onDismiss = {
                    showCompactControls = false
                },
            )
        }
    }

    if (showRouteCompletePrompt && !showStopPrompt) {
        WearActionDialog(
            visible = true,
            title = "Route complete",
            onDismissRequest = onRouteCompletePromptDismiss,
            buttons =
                listOf(
                    WearActionDialogButton(
                        text = "Finish recording",
                        role = WearActionButtonRole.Primary,
                        onClick = {
                            onRouteCompletePromptDismiss()
                            stopPromptPausedRecording =
                                state.active &&
                                !state.paused &&
                                !state.saving
                            if (stopPromptPausedRecording) {
                                onPause()
                            }
                            showCompactControls = false
                            expanded = false
                            showStopPrompt = true
                        },
                    ),
                    WearActionDialogButton(
                        text = "Continue recording",
                        role = WearActionButtonRole.Secondary,
                        onClick = onRouteCompletePromptDismiss,
                    ),
                ),
        ) {
            androidx.wear.compose.material3.Text(
                text = "Guidance ended. Recording is still active.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = androidx.wear.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (showStopPrompt) {
        RecordingStopPromptCard(
            state = state,
            snapshot = snapshot,
            isMetric = isMetric,
            onDiscard = {
                showStopPrompt = false
                showCompactControls = false
                expanded = false
                stopPromptPausedRecording = false
                onDiscard()
            },
            onSave = { title ->
                showStopPrompt = false
                showCompactControls = false
                expanded = false
                stopPromptPausedRecording = false
                onStopConfirmed(title)
            },
            onCancel = {
                showStopPrompt = false
                if (stopPromptPausedRecording) {
                    onResume()
                }
                stopPromptPausedRecording = false
            },
        )
    }

    if (metricPickerSlot != NO_SELECTED_SLOT) {
        val currentMetric = slots.getOrElse(metricPickerSlot) { SettingsRepository.RECORDING_METRIC_DISTANCE }
        OptionPickerDialog(
            visible = true,
            title = "Choose measure",
            selectedValue = currentMetric,
            options = recordingMetricPickerOptionsForProfile(sessionProfile),
            onDismiss = { metricPickerSlot = NO_SELECTED_SLOT },
            onSelect = { metricId ->
                onMetricSelected(metricPickerSlot, metricId)
                DebugTelemetry.log(
                    "TraceRecording",
                    "event=dashboard_metric_selected slot=$metricPickerSlot metric=$metricId source=fullscreen",
                )
                metricPickerSlot = NO_SELECTED_SLOT
            },
        )
    }
}

private fun recordingActionPromptTopPadding(screenSize: WearScreenSize): Dp =
    when (screenSize) {
        WearScreenSize.LARGE -> 90.dp
        WearScreenSize.MEDIUM -> 86.dp
        WearScreenSize.SMALL -> 82.dp
    }

internal fun normalizedRecordingDashboardSlots(
    metricSlots: List<String>,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
): List<String> =
    normalizeRecordingDashboardMetricSlots(
        metricSlots = metricSlots,
        defaultMetricSlots = defaultRecordingDashboardMetricSlotsForProfile(activityProfile),
        newPageMetricSlots = newRecordingDashboardPageMetricSlotsForProfile(activityProfile),
    )

private const val NO_SELECTED_SLOT = -1
