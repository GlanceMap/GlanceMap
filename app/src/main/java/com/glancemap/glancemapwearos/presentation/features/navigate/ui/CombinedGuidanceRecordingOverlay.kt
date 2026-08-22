@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
)
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.RECORDING_DASHBOARD_PAGE_SLOT_COUNT
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT
import com.glancemap.glancemapwearos.data.repository.normalizeTurnByTurnDashboardMetricSlots
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingDashboardSnapshot
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingFullscreenPageShell
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingStopPromptCard
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.buildRecordingDashboardSnapshot
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.formattedRecordingMetric
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.logRecordingDashboardPageChange
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.normalizedRecordingDashboardSlots
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.recordingMetricPickerOptionsForProfile
import com.glancemap.glancemapwearos.presentation.features.settings.OptionPickerDialog
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun BoxScope.CombinedGuidanceRecordingOverlay(
    guidanceState: TurnByTurnGuidanceState,
    guidancePaused: Boolean,
    voiceGuidanceEnabled: Boolean,
    recordingState: TraceRecordingUiState,
    metricSlots: List<String>,
    guidanceMetricSlots: List<String>,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    compassHeadingDeg: Float,
    guideBackToRouteActive: Boolean,
    showGuideBackPrompt: Boolean,
    expandRequestToken: Long,
    actionPromptRequestToken: Long,
    compactPopupEnabled: Boolean,
    suppressed: Boolean,
    onPauseGuidance: () -> Unit,
    onResumeGuidance: () -> Unit,
    onStopGuidance: () -> Unit,
    onVoiceGuidanceChange: (Boolean) -> Unit,
    onGuideBackToRoute: () -> Unit,
    onDismissGuideBackPrompt: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onFinishRecording: (String?) -> Unit,
    onDiscardRecording: () -> Unit,
    onMetricSelected: (Int, String) -> Unit,
    onGuidanceMetricSelected: (Int, String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    if ((!guidanceState.active && !guidancePaused) || (!recordingState.active && !recordingState.saving)) return

    var expanded by remember { mutableStateOf(false) }
    val expandedVisibility = remember { MutableTransitionState(false) }
    expandedVisibility.targetState = expanded
    val popupOwnsTimeChip =
        fullScreenPopupTransitionOwnsTimeChip(
            currentlyVisible = expandedVisibility.currentState,
            targetVisible = expandedVisibility.targetState,
        )
    fun expandPopup() {
        onExpandedChange(true)
        expanded = true
    }
    var showActions by remember { mutableStateOf(false) }
    var showStopPrompt by remember { mutableStateOf(false) }
    var stopPromptPausedRecording by remember { mutableStateOf(false) }
    var metricPickerSlot by remember { mutableIntStateOf(NO_SELECTED_SLOT) }
    var guidanceMetricPickerSlot by remember { mutableIntStateOf(NO_SELECTED_SLOT) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastHandledActionPromptRequestToken by remember {
        mutableLongStateOf(actionPromptRequestToken)
    }
    var lastHandledExpandRequestToken by remember {
        mutableLongStateOf(expandRequestToken)
    }

    LaunchedEffect(recordingState.active, recordingState.paused, recordingState.saving) {
        while (isActive && (recordingState.active || recordingState.saving)) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    LaunchedEffect(suppressed) {
        if (suppressed) {
            expanded = false
            showActions = false
            showStopPrompt = false
            stopPromptPausedRecording = false
            metricPickerSlot = NO_SELECTED_SLOT
            guidanceMetricPickerSlot = NO_SELECTED_SLOT
            onExpandedChange(false)
        }
    }
    LaunchedEffect(popupOwnsTimeChip) {
        onExpandedChange(popupOwnsTimeChip)
    }
    LaunchedEffect(actionPromptRequestToken) {
        val shouldHandle =
            actionPromptRequestToken != 0L &&
                actionPromptRequestToken != lastHandledActionPromptRequestToken
        lastHandledActionPromptRequestToken = actionPromptRequestToken
        if (shouldHandle && recordingState.active && !recordingState.saving && !suppressed) {
            expanded = false
            showStopPrompt = false
            metricPickerSlot = NO_SELECTED_SLOT
            showActions = true
        }
    }
    LaunchedEffect(expandRequestToken) {
        val shouldHandle =
            expandRequestToken != 0L &&
                expandRequestToken != lastHandledExpandRequestToken
        lastHandledExpandRequestToken = expandRequestToken
        if (shouldHandle && recordingState.active && !recordingState.saving && !suppressed) {
            DebugTelemetry.log(
                "TraceRecording",
                "event=combined_expand_token handled=true suppressed=$suppressed active=${recordingState.active} saving=${recordingState.saving}",
            )
            showActions = false
            showStopPrompt = false
            expandPopup()
        } else if (shouldHandle) {
            DebugTelemetry.log(
                "TraceRecording",
                "event=combined_expand_token handled=false suppressed=$suppressed active=${recordingState.active} saving=${recordingState.saving}",
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { onExpandedChange(false) }
    }
    if (suppressed) return

    val sessionProfile = recordingState.activityProfile
    val slots = normalizedRecordingDashboardSlots(metricSlots, sessionProfile)
    val guidanceSlots = normalizeTurnByTurnDashboardMetricSlots(guidanceMetricSlots)
    val recordingPageCount = (slots.size / RECORDING_DASHBOARD_PAGE_SLOT_COUNT).coerceAtLeast(1)
    val guidanceMetricPageCount =
        (guidanceSlots.size / TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT).coerceAtLeast(1)
    val pageCount = recordingPageCount + guidanceMetricPageCount + 1
    LaunchedEffect(pageCount) {
        if (pageIndex >= pageCount) pageIndex = pageCount - 1
    }
    val recordingPageIndex =
        (pageIndex - guidanceMetricPageCount - 1).coerceIn(0, recordingPageCount - 1)
    val visibleSlots =
        slots
            .drop(recordingPageIndex * RECORDING_DASHBOARD_PAGE_SLOT_COUNT)
            .take(RECORDING_DASHBOARD_PAGE_SLOT_COUNT)
    val snapshot =
        buildRecordingDashboardSnapshot(
            state = recordingState,
            nowMillis = nowMillis,
            userWeightKg = userWeightKg,
            backpackWeightKg = backpackWeightKg,
            bikeWeightKg = bikeWeightKg,
            activityProfile = sessionProfile,
        )

    AnimatedVisibility(
        visibleState = expandedVisibility,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier =
            Modifier
                .align(Alignment.Center)
                .fillMaxSize(),
    ) {
        SwipeToDismissBox(onDismissed = { expanded = false }) { isBackground ->
            if (!isBackground) {
                CombinedFullscreenDashboard(
                    guidanceState = guidanceState,
                    guidancePaused = guidancePaused,
                    voiceGuidanceEnabled = voiceGuidanceEnabled,
                    recordingState = recordingState,
                    slots = visibleSlots,
                    guidanceMetricSlots = guidanceSlots,
                    guidanceMetricPageCount = guidanceMetricPageCount,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    snapshot = snapshot,
                    screenSize = screenSize,
                    isMetric = isMetric,
                    compassHeadingDeg = compassHeadingDeg,
                    guideBackToRouteActive = guideBackToRouteActive,
                    showGuideBackPrompt = showGuideBackPrompt,
                    onSlotLongPress = { slotIndex ->
                        if (pageIndex > guidanceMetricPageCount) {
                            metricPickerSlot = recordingPageIndex * RECORDING_DASHBOARD_PAGE_SLOT_COUNT + slotIndex
                        }
                    },
                    onGuidanceSlotLongPress = { metricPageIndex, slotIndex ->
                        guidanceMetricPickerSlot =
                            metricPageIndex * TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT + slotIndex
                    },
                    onPreviousPage = {
                        val nextPageIndex = (pageIndex - 1).coerceAtLeast(0)
                        if (nextPageIndex != pageIndex) {
                            pageIndex = nextPageIndex
                            logRecordingDashboardPageChange(nextPageIndex, pageCount, "combined_swipe_down")
                        }
                    },
                    onNextPage = {
                        val nextPageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1)
                        if (nextPageIndex != pageIndex) {
                            pageIndex = nextPageIndex
                            logRecordingDashboardPageChange(nextPageIndex, pageCount, "combined_swipe_up")
                        }
                    },
                    onShowActions = { showActions = true },
                    onDismiss = { expanded = false },
                    onVoiceGuidanceChange = onVoiceGuidanceChange,
                    onGuideBackToRoute = onGuideBackToRoute,
                    onDismissGuideBackPrompt = onDismissGuideBackPrompt,
                )
            }
        }
    }

    if (!expanded && compactPopupEnabled) {
        CombinedCompactPopup(
            guidanceState = guidanceState,
            guidancePaused = guidancePaused,
            isMetric = isMetric,
            compassHeadingDeg = compassHeadingDeg,
            guideBackToRouteActive = guideBackToRouteActive,
            screenSize = screenSize,
            modifier = Modifier.align(Alignment.TopCenter),
            onExpand = {
                DebugTelemetry.log("TurnByTurn", "event=compact_popup_tap mode=combined")
                showActions = false
                expandPopup()
            },
            onShowActions = {
                DebugTelemetry.log("TurnByTurn", "event=compact_popup_long_press mode=combined")
                showActions = true
            },
        )
    }

    if (showActions) {
        CombinedActionPrompt(
            guidancePaused = guidancePaused,
            recordingPaused = recordingState.paused,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(top = if (expanded) 0.dp else 28.dp),
            onPauseResumeGuidance = {
                showActions = false
                if (guidancePaused) onResumeGuidance() else onPauseGuidance()
            },
            onStopGuidance = {
                showActions = false
                onStopGuidance()
            },
            onPauseResumeRecording = {
                showActions = false
                if (recordingState.paused) onResumeRecording() else onPauseRecording()
            },
            onStopRecording = {
                stopPromptPausedRecording = recordingState.active && !recordingState.paused && !recordingState.saving
                if (stopPromptPausedRecording) {
                    onPauseRecording()
                }
                showStopPrompt = true
            },
            onCancel = { showActions = false },
        )
    }

    if (showStopPrompt) {
        RecordingStopPromptCard(
            state = recordingState,
            snapshot = snapshot,
            isMetric = isMetric,
            onDiscard = {
                showStopPrompt = false
                showActions = false
                expanded = false
                stopPromptPausedRecording = false
                onDiscardRecording()
            },
            onSave = { title ->
                showStopPrompt = false
                showActions = false
                expanded = false
                stopPromptPausedRecording = false
                onFinishRecording(title)
            },
            onCancel = {
                showStopPrompt = false
                if (stopPromptPausedRecording) {
                    onResumeRecording()
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
                    "event=dashboard_metric_selected slot=$metricPickerSlot metric=$metricId source=combined_fullscreen",
                )
                metricPickerSlot = NO_SELECTED_SLOT
            },
        )
    }
    if (guidanceMetricPickerSlot != NO_SELECTED_SLOT) {
        val slotIndex = guidanceMetricPickerSlot
        OptionPickerDialog(
            visible = true,
            title = "Dashboard measure",
            selectedValue = guidanceSlots[slotIndex],
            options = turnByTurnMetricPickerOptions,
            onDismiss = { guidanceMetricPickerSlot = NO_SELECTED_SLOT },
            onSelect = { metricId ->
                onGuidanceMetricSelected(slotIndex, metricId)
                guidanceMetricPickerSlot = NO_SELECTED_SLOT
            },
        )
    }
}

@Composable
private fun CombinedCompactPopup(
    guidanceState: TurnByTurnGuidanceState,
    guidancePaused: Boolean,
    isMetric: Boolean,
    compassHeadingDeg: Float,
    guideBackToRouteActive: Boolean,
    screenSize: WearScreenSize,
    modifier: Modifier,
    onExpand: () -> Unit,
    onShowActions: () -> Unit,
) {
    val topPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 54.dp
            WearScreenSize.MEDIUM -> 50.dp
            WearScreenSize.SMALL -> 46.dp
        }
    val compactMinWidth =
        when (screenSize) {
            WearScreenSize.LARGE -> 82.dp
            WearScreenSize.MEDIUM -> 82.dp
            WearScreenSize.SMALL -> 78.dp
        }
    val compactMaxWidth =
        when (screenSize) {
            WearScreenSize.LARGE -> 112.dp
            WearScreenSize.MEDIUM -> 112.dp
            WearScreenSize.SMALL -> 108.dp
        }
    val compactIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 17.dp
            WearScreenSize.MEDIUM -> 16.dp
            WearScreenSize.SMALL -> 15.dp
        }
    val titleFont =
        when (screenSize) {
            WearScreenSize.LARGE -> 11.sp
            WearScreenSize.MEDIUM -> 10.sp
            WearScreenSize.SMALL -> 10.sp
        }
    val compactBackground = Color.Black.copy(alpha = 0.9f)
    val compactBorder = Color.Transparent
    Box(
        modifier =
            modifier
                .padding(top = topPadding)
                .width(compactMaxWidth)
                .height(48.dp)
                .combinedClickable(
                    onClick = onExpand,
                    onLongClick = onShowActions,
                ).pointerInput(guidanceState.mode, guidanceState.nextInstruction, guidancePaused) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (totalDrag < -COMBINED_POPUP_DRAG_THRESHOLD_PX) onExpand()
                            totalDrag = 0f
                        },
                        onDragCancel = { totalDrag = 0f },
                    ) { _, dragAmount ->
                        totalDrag += dragAmount
                    }
                },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(min = compactMinWidth, max = compactMaxWidth)
                    .heightIn(min = 30.dp)
                    .background(compactBackground, RoundedCornerShape(8.dp))
                    .border(1.dp, compactBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            cappedFontScale(maxFontScale = 1.2f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CombinedGuidanceIcon(
                        state = guidanceState,
                        compassHeadingDeg = compassHeadingDeg,
                        guideBackToRouteActive = guideBackToRouteActive,
                        modifier = Modifier.size(compactIconSize),
                    )
                    Text(
                        text =
                            if (guidancePaused) {
                                "Paused"
                            } else {
                                combinedGuidanceCompactText(guidanceState, isMetric, guideBackToRouteActive)
                            },
                        color = if (guidanceState.offRoute) COMBINED_OFF_ROUTE_AMBER else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = titleFont,
                        lineHeight = titleFont * 1.1f,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CombinedFullscreenDashboard(
    guidanceState: TurnByTurnGuidanceState,
    guidancePaused: Boolean,
    voiceGuidanceEnabled: Boolean,
    recordingState: TraceRecordingUiState,
    slots: List<String>,
    guidanceMetricSlots: List<String>,
    guidanceMetricPageCount: Int,
    pageIndex: Int,
    pageCount: Int,
    snapshot: RecordingDashboardSnapshot,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    compassHeadingDeg: Float,
    guideBackToRouteActive: Boolean,
    showGuideBackPrompt: Boolean,
    onSlotLongPress: (Int) -> Unit,
    onGuidanceSlotLongPress: (Int, Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onShowActions: () -> Unit,
    onDismiss: () -> Unit,
    onVoiceGuidanceChange: (Boolean) -> Unit,
    onGuideBackToRoute: () -> Unit,
    onDismissGuideBackPrompt: () -> Unit,
) {
    RecordingFullscreenPageShell(
        pageIndex = pageIndex,
        pageCount = pageCount,
        dragKey = recordingState.active to recordingState.paused,
        recordingActive = recordingState.active,
        recordingPaused = recordingState.paused,
        recordingSaving = recordingState.saving,
        onPreviousPage = onPreviousPage,
        onNextPage = onNextPage,
        onShowActions = onShowActions,
        onDismiss = onDismiss,
        telemetryTag = "TurnByTurn",
    ) {
        GuidanceRouteProgressChrome(
            state = guidanceState,
            isMetric = isMetric,
            showDetails = !guidanceState.offRoute || guideBackToRouteActive,
            modifier = Modifier.fillMaxSize(),
        )
        if (pageIndex == 0) {
            CombinedGuidancePage(
                state = guidanceState,
                paused = guidancePaused,
                screenSize = screenSize,
                isMetric = isMetric,
                compassHeadingDeg = compassHeadingDeg,
                guideBackToRouteActive = guideBackToRouteActive,
                showGuideBackPrompt = showGuideBackPrompt,
                voiceGuidanceEnabled = voiceGuidanceEnabled,
                onVoiceGuidanceChange = onVoiceGuidanceChange,
                onGuideBackToRoute = onGuideBackToRoute,
                onDismissGuideBackPrompt = onDismissGuideBackPrompt,
            )
        } else if (pageIndex <= guidanceMetricPageCount) {
            val metricPageIndex = pageIndex - 1
            TurnByTurnMetricDashboardPage(
                state = guidanceState,
                slots =
                    guidanceMetricSlots
                        .drop(metricPageIndex * TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT)
                        .take(TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT),
                screenSize = screenSize,
                isMetric = isMetric,
                onSlotLongPress = { slotIndex -> onGuidanceSlotLongPress(metricPageIndex, slotIndex) },
            )
        } else {
            CombinedRecordingPage(
                slots = slots,
                snapshot = snapshot,
                screenSize = screenSize,
                isMetric = isMetric,
                onSlotLongPress = onSlotLongPress,
            )
        }
    }
}

@Composable
private fun CombinedGuidancePage(
    state: TurnByTurnGuidanceState,
    paused: Boolean,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    compassHeadingDeg: Float,
    guideBackToRouteActive: Boolean,
    showGuideBackPrompt: Boolean,
    voiceGuidanceEnabled: Boolean,
    onVoiceGuidanceChange: (Boolean) -> Unit,
    onGuideBackToRoute: () -> Unit,
    onDismissGuideBackPrompt: () -> Unit,
) {
    val contentWidthFraction =
        when (screenSize) {
            WearScreenSize.LARGE -> 0.78f
            WearScreenSize.MEDIUM -> 0.76f
            WearScreenSize.SMALL -> 0.74f
        }
    val arrowContainerSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 68.dp
            WearScreenSize.MEDIUM -> 64.dp
            WearScreenSize.SMALL -> 60.dp
        }
    val arrowIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 40.dp
            WearScreenSize.MEDIUM -> 38.dp
            WearScreenSize.SMALL -> 36.dp
        }
    val showRouteProgressDetails = !state.offRoute || guideBackToRouteActive
    val combinedManeuverText =
        if (!paused && !state.offRoute && !guideBackToRouteActive) {
            guidanceInstructionDistanceText(state, isMetric)?.let { distance ->
                "$distance ${guidanceInstructionPrimaryText(state)}"
            }
        } else {
            null
        }
    val showGuideBackShortcut = state.active && state.offRoute && !guideBackToRouteActive && !showGuideBackPrompt
    var showGuideBackShortcutConfirm by remember(state.trackTitle) { mutableStateOf(false) }
    LaunchedEffect(state.offRoute, guideBackToRouteActive, showGuideBackPrompt) {
        if (!state.offRoute || guideBackToRouteActive || showGuideBackPrompt) {
            showGuideBackShortcutConfirm = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CombinedVoiceToggle(
            enabled = voiceGuidanceEnabled,
            onClick = {
                DebugTelemetry.log("TurnByTurn", "event=voice_toggle enabled=${!voiceGuidanceEnabled}")
                onVoiceGuidanceChange(!voiceGuidanceEnabled)
            },
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
        )
        if (showGuideBackShortcut) {
            CombinedGuideBackShortcut(
                onClick = {
                    DebugTelemetry.log("TurnByTurn", "event=guide_back_dashboard_shortcut_click mode=combined")
                    showGuideBackShortcutConfirm = true
                },
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
            )
        }
        cappedFontScale(maxFontScale = 1.15f) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = 7.dp)
                        .fillMaxWidth(contentWidthFraction),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(arrowContainerSize)
                            .background(
                                if (state.offRoute) {
                                    COMBINED_OFF_ROUTE_AMBER
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                CircleShape,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    CombinedGuidanceIcon(
                        state = state,
                        compassHeadingDeg = compassHeadingDeg,
                        guideBackToRouteActive = guideBackToRouteActive,
                        modifier = Modifier.size(arrowIconSize),
                        tint = if (state.offRoute) Color.Black else MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text =
                        if (paused) {
                            "Paused"
                        } else {
                            combinedManeuverText ?: combinedGuidancePrimaryText(state, guideBackToRouteActive)
                        },
                    color = if (state.offRoute) COMBINED_OFF_ROUTE_AMBER else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (paused || combinedManeuverText == null) {
                    Text(
                        text = combinedGuidanceSecondaryText(state, isMetric, guideBackToRouteActive),
                        color = Color.White.copy(alpha = 0.82f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showRouteProgressDetails) {
                    guidanceTerrainPopupPresentation(state, isMetric)?.let { terrain ->
                        Spacer(modifier = Modifier.size(2.dp))
                        Text(
                            text = terrain.expandedText,
                            color = guidanceTerrainColor(terrain.direction),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            lineHeight = 15.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showRouteProgressDetails) {
                    guidanceFollowingText(state, isMetric)?.let { followingText ->
                        Spacer(modifier = Modifier.size(2.dp))
                        Text(
                            text = followingText,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (showGuideBackPrompt || showGuideBackShortcutConfirm) {
            GuidanceDecisionPromptCard(
                title = "Route back?",
                detail =
                    state.distanceToRouteMeters?.let {
                        "${formatLiveDistanceLabel(it, isMetric)} from GPX"
                    } ?: "Create route to GPX",
                acceptText = "Guide",
                dismissText = if (showGuideBackPrompt) "Ignore" else "Cancel",
                onAccept = {
                    showGuideBackShortcutConfirm = false
                    DebugTelemetry.log(
                        "TurnByTurn",
                        "event=guide_back_dashboard_confirm mode=combined prompt=$showGuideBackPrompt",
                    )
                    onGuideBackToRoute()
                },
                onDismiss = {
                    showGuideBackShortcutConfirm = false
                    DebugTelemetry.log(
                        "TurnByTurn",
                        "event=guide_back_dashboard_cancel mode=combined prompt=$showGuideBackPrompt",
                    )
                    if (showGuideBackPrompt) {
                        onDismissGuideBackPrompt()
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp),
            )
        }
    }
}

@Composable
private fun CombinedRecordingPage(
    slots: List<String>,
    snapshot: RecordingDashboardSnapshot,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    onSlotLongPress: (Int) -> Unit,
) {
    val tileSlots =
        List(RECORDING_DASHBOARD_PAGE_SLOT_COUNT) { index ->
            slots.getOrElse(index) { SettingsRepository.RECORDING_METRIC_DISTANCE }
        }
    TurnByTurnMetricDashboardGrid(
        metrics = tileSlots.map { formattedRecordingMetric(it, snapshot, isMetric) },
        header = null,
        screenSize = screenSize,
        onSlotLongPress = onSlotLongPress,
    )
}

@Composable
private fun CombinedActionPrompt(
    guidancePaused: Boolean,
    recordingPaused: Boolean,
    modifier: Modifier,
    onPauseResumeGuidance: () -> Unit,
    onStopGuidance: () -> Unit,
    onPauseResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 2.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        cappedFontScale(maxFontScale = 1.2f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-4).dp),
            ) {
                CompactActionRow(
                    label = "Guide",
                    pauseIcon = if (guidancePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    pauseContentDescription = if (guidancePaused) "Resume guidance" else "Pause guidance",
                    onPauseResume = onPauseResumeGuidance,
                    stopContentDescription = "Stop guidance",
                    onStop = onStopGuidance,
                )
                CompactActionRow(
                    label = "REC",
                    pauseIcon = if (recordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    pauseContentDescription = if (recordingPaused) "Resume recording" else "Pause recording",
                    onPauseResume = onPauseResumeRecording,
                    stopContentDescription = "Stop recording",
                    onStop = onStopRecording,
                )
                CompactCancelButton(onClick = onCancel)
            }
        }
    }
}

@Composable
private fun CompactActionRow(
    label: String,
    pauseIcon: ImageVector,
    pauseContentDescription: String,
    onPauseResume: () -> Unit,
    stopContentDescription: String,
    onStop: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactActionLabel(label)
        CompactActionButton(
            icon = pauseIcon,
            contentDescription = pauseContentDescription,
            onClick = onPauseResume,
            selected = true,
        )
        CompactActionButton(
            icon = Icons.Default.Stop,
            contentDescription = stopContentDescription,
            onClick = onStop,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CompactActionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.66f),
        fontSize = 8.sp,
        lineHeight = 8.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(30.dp),
    )
}

@Composable
private fun CompactActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    tint: Color = Color.White,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else tint,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun CompactCancelButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .width(122.dp)
                .height(48.dp)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .height(24.dp)
                    .width(68.dp)
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Cancel",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 9.sp,
                lineHeight = 9.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CombinedGuidanceIcon(
    state: TurnByTurnGuidanceState,
    compassHeadingDeg: Float,
    guideBackToRouteActive: Boolean,
    modifier: Modifier,
    tint: Color = Color.White,
) {
    GuidanceManeuverIcon(
        state = state,
        compassHeadingDeg = compassHeadingDeg,
        guideBackToRouteActive = guideBackToRouteActive,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
private fun CombinedGuideBackShortcut(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Route,
            contentDescription = "Route back to GPX",
            tint = COMBINED_OFF_ROUTE_AMBER,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CombinedVoiceToggle(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (enabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            contentDescription = if (enabled) "Turn voice off" else "Turn voice on",
            tint = if (enabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.62f),
            modifier = Modifier.size(17.dp),
        )
    }
}

private fun combinedGuidancePrimaryText(
    state: TurnByTurnGuidanceState,
    guideBackToRouteActive: Boolean,
): String =
    if (guideBackToRouteActive) {
        "To route"
    } else if (state.offRoute) {
        "Off route"
    } else {
        when (state.mode) {
            GuidanceMode.WAITING_FOR_LOCATION -> "Waiting GPS"
            GuidanceMode.TO_START -> "To start"
            GuidanceMode.FOLLOW_ROUTE -> guidanceInstructionPrimaryText(state)
            GuidanceMode.FINISHED -> "Finished"
        }
    }

private fun combinedGuidanceSecondaryText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
    guideBackToRouteActive: Boolean,
): String =
    if (guideBackToRouteActive) {
        state.distanceToRouteMeters?.let { formatLiveDistanceLabel(it, isMetric) } ?: "Find route"
    } else if (state.offRoute) {
        state.distanceToRouteMeters?.let { "${formatLiveDistanceLabel(it, isMetric)} from GPX" } ?: "Find route"
    } else {
        when (state.mode) {
            GuidanceMode.WAITING_FOR_LOCATION -> state.trackTitle ?: "GPX guidance"
            GuidanceMode.TO_START ->
                state.distanceToStartMeters?.let { formatLiveDistanceLabel(it, isMetric) } ?: "Find the start"
            GuidanceMode.FOLLOW_ROUTE ->
                guidanceInstructionDistanceText(state, isMetric) ?: "On route"
            GuidanceMode.FINISHED -> state.trackTitle ?: "Route complete"
        }
    }

private fun combinedGuidanceCompactText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
    guideBackToRouteActive: Boolean,
): String =
    if (guideBackToRouteActive) {
        state.distanceToRouteMeters?.let { "Route ${formatLiveDistanceLabel(it, isMetric)}" } ?: "To route"
    } else if (state.offRoute) {
        state.distanceToRouteMeters?.let { "Off ${formatLiveDistanceLabel(it, isMetric)}" } ?: "Off route"
    } else {
        when (state.mode) {
            GuidanceMode.WAITING_FOR_LOCATION -> "Waiting GPS"
            GuidanceMode.TO_START ->
                state.distanceToStartMeters?.let { "Start ${formatLiveDistanceLabel(it, isMetric)}" } ?: "To start"
            GuidanceMode.FOLLOW_ROUTE -> guidanceCompactInstructionText(state, isMetric)
            GuidanceMode.FINISHED -> "Finished"
        }
    }

private const val NO_SELECTED_SLOT = -1
private const val COMBINED_POPUP_DRAG_THRESHOLD_PX = 24f
private val COMBINED_OFF_ROUTE_AMBER = Color(0xFFFFC107)
