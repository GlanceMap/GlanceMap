@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.glancemap.glancemapwearos.data.repository.TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT
import com.glancemap.glancemapwearos.data.repository.normalizeTurnByTurnDashboardMetricSlots
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.recording.dashboard.RecordingFullscreenPageShell
import com.glancemap.glancemapwearos.presentation.features.settings.OptionPickerDialog
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.TurnByTurnGuidanceOverlay(
    state: TurnByTurnGuidanceState,
    paused: Boolean,
    dashboardMetricSlots: List<String>,
    voiceGuidanceEnabled: Boolean,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    compassHeadingDeg: Float,
    guideBackToRouteActive: Boolean,
    showGuideBackPrompt: Boolean,
    startDecisionPrompt: GuidanceDecisionPrompt?,
    expandRequestToken: Long,
    actionPromptRequestToken: Long,
    compactPopupEnabled: Boolean,
    suppressed: Boolean = false,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onVoiceGuidanceChange: (Boolean) -> Unit,
    onDashboardMetricSelected: (Int, String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onGuideBackToRoute: () -> Unit,
    onDismissGuideBackPrompt: () -> Unit,
    onAcceptStartDecisionPrompt: () -> Unit,
    onDismissStartDecisionPrompt: () -> Unit,
) {
    LaunchedEffect(state.active) {
        if (!state.active) {
            onExpandedChange(false)
        }
    }
    if (!state.active && !paused) return

    var expanded by remember(state.trackTitle) { mutableStateOf(false) }
    var showActionPrompt by remember(state.trackTitle, paused) { mutableStateOf(false) }
    var arrivalPromptDismissed by remember(state.trackTitle) { mutableStateOf(false) }
    var expandedPageIndex by remember(state.trackTitle) { mutableIntStateOf(0) }
    var selectedDashboardSlot by remember(state.trackTitle) { mutableStateOf<Int?>(null) }
    var lastHandledExpandRequestToken by remember {
        mutableLongStateOf(expandRequestToken)
    }
    var lastHandledActionPromptRequestToken by remember {
        mutableLongStateOf(actionPromptRequestToken)
    }
    val dashboardSlots = normalizeTurnByTurnDashboardMetricSlots(dashboardMetricSlots)
    val dashboardPageCount = dashboardSlots.size / TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT
    val expandedPageCount = dashboardPageCount + 1
    LaunchedEffect(expandedPageCount) {
        expandedPageIndex = expandedPageIndex.coerceIn(0, expandedPageCount - 1)
    }
    LaunchedEffect(suppressed) {
        if (suppressed) {
            expanded = false
            showActionPrompt = false
            onExpandedChange(false)
        }
    }
    LaunchedEffect(expanded) {
        onExpandedChange(expanded)
    }
    LaunchedEffect(expandRequestToken) {
        val shouldHandle =
            expandRequestToken != 0L &&
                expandRequestToken != lastHandledExpandRequestToken
        lastHandledExpandRequestToken = expandRequestToken
        if (shouldHandle && !suppressed && (state.active || paused)) {
            DebugTelemetry.log("TurnByTurn", "event=time_chip_expand handled=true")
            showActionPrompt = false
            expanded = true
        }
    }
    LaunchedEffect(actionPromptRequestToken) {
        val shouldHandle =
            actionPromptRequestToken != 0L &&
                actionPromptRequestToken != lastHandledActionPromptRequestToken
        lastHandledActionPromptRequestToken = actionPromptRequestToken
        if (shouldHandle && !suppressed && (state.active || paused)) {
            DebugTelemetry.log("TurnByTurn", "event=time_chip_long_press handled=true")
            expanded = false
            showActionPrompt = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { onExpandedChange(false) }
    }
    if (suppressed) return

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

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center),
    ) {
        SwipeToDismissBox(onDismissed = { expanded = false }) { isBackground ->
            if (!isBackground) {
                RecordingFullscreenPageShell(
                    pageIndex = expandedPageIndex,
                    pageCount = expandedPageCount,
                    dragKey = state.mode to paused,
                    onPreviousPage = { expandedPageIndex = (expandedPageIndex - 1).coerceAtLeast(0) },
                    onNextPage = { expandedPageIndex = (expandedPageIndex + 1).coerceAtMost(expandedPageCount - 1) },
                    onShowActions = { showActionPrompt = true },
                    onDismiss = { expanded = false },
                    telemetryTag = "TurnByTurn",
                ) {
                    if (expandedPageIndex == 0) {
                        ExpandedGuidanceOverlay(
                            state = state,
                            screenSize = screenSize,
                            isMetric = isMetric,
                            compassHeadingDeg = compassHeadingDeg,
                            guideBackToRouteActive = guideBackToRouteActive,
                            showGuideBackPrompt = showGuideBackPrompt,
                            voiceGuidanceEnabled = voiceGuidanceEnabled,
                            onVoiceGuidanceChange = onVoiceGuidanceChange,
                            onGuideBackToRoute = onGuideBackToRoute,
                            onLongPress = { showActionPrompt = true },
                        )
                    } else {
                        val metricPageIndex = expandedPageIndex - 1
                        val pageSlots =
                            dashboardSlots
                                .drop(metricPageIndex * TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT)
                                .take(TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT)
                        TurnByTurnMetricDashboardPage(
                            state = state,
                            slots = pageSlots,
                            screenSize = screenSize,
                            isMetric = isMetric,
                            onSlotLongPress = { pageSlot ->
                                selectedDashboardSlot =
                                    metricPageIndex * TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT + pageSlot
                            },
                        )
                    }
                }
            }
        }
    }

    if (!expanded && compactPopupEnabled) {
        val compactBackground = Color.Black.copy(alpha = 0.9f)
        val compactBorder = Color.Transparent
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding)
                    .width(compactMaxWidth)
                    .height(48.dp)
                    .combinedClickable(
                        onClick = {
                            DebugTelemetry.log("TurnByTurn", "event=compact_popup_tap mode=guidance")
                            showActionPrompt = false
                            expanded = true
                        },
                        onLongClick = {
                            DebugTelemetry.log("TurnByTurn", "event=compact_popup_long_press mode=guidance")
                            showActionPrompt = true
                        },
                    ),
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
                        GuidanceManeuverIcon(
                            state = state,
                            compassHeadingDeg = compassHeadingDeg,
                            guideBackToRouteActive = guideBackToRouteActive,
                            modifier = Modifier.size(compactIconSize),
                        )
                        Text(
                            text =
                                if (paused) {
                                    "Paused"
                                } else {
                                    guidanceCompactText(state, isMetric, guideBackToRouteActive)
                                },
                            color = if (state.offRoute) OFF_ROUTE_AMBER else Color.White,
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
    selectedDashboardSlot?.let { slotIndex ->
        OptionPickerDialog(
            visible = true,
            title = "Dashboard measure",
            selectedValue = dashboardSlots[slotIndex],
            options = turnByTurnMetricPickerOptions,
            onDismiss = { selectedDashboardSlot = null },
            onSelect = { metricId ->
                onDashboardMetricSelected(slotIndex, metricId)
                selectedDashboardSlot = null
            },
        )
    }

    if (showActionPrompt) {
        GuidanceActionPromptCard(
            paused = paused,
            onPause = {
                showActionPrompt = false
                expanded = false
                onPause()
            },
            onResume = {
                showActionPrompt = false
                onResume()
            },
            onStop = {
                showActionPrompt = false
                expanded = false
                onStop()
            },
            onCancel = { showActionPrompt = false },
            modifier =
                if (expanded) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                } else {
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topPadding + 36.dp)
                },
        )
    } else if (startDecisionPrompt != null) {
        GuidanceDecisionPromptCard(
            title = startDecisionPrompt.title,
            detail = startDecisionPrompt.detail,
            acceptText = startDecisionPrompt.acceptText,
            dismissText = startDecisionPrompt.dismissText,
            onAccept = onAcceptStartDecisionPrompt,
            onDismiss = onDismissStartDecisionPrompt,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
        )
    } else if (showGuideBackPrompt) {
        GuidanceDecisionPromptCard(
            title = "Off route",
            detail =
                state.distanceToRouteMeters?.let {
                    "${formatLiveDistanceLabel(it, isMetric)} from GPX"
                } ?: "Guide back?",
            acceptText = "Guide",
            dismissText = "Ignore",
            onAccept = onGuideBackToRoute,
            onDismiss = onDismissGuideBackPrompt,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
        )
    } else if (state.mode == GuidanceMode.FINISHED && !arrivalPromptDismissed) {
        GuidanceDecisionPromptCard(
            title = "Route complete",
            detail = "End turn-by-turn guidance?",
            acceptText = "End",
            dismissText = "Keep",
            onAccept = onStop,
            onDismiss = { arrivalPromptDismissed = true },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
        )
    }
}

internal data class GuidanceDecisionPrompt(
    val title: String,
    val detail: String,
    val acceptText: String,
    val dismissText: String,
)

@Composable
private fun ExpandedGuidanceOverlay(
    state: TurnByTurnGuidanceState,
    screenSize: WearScreenSize,
    isMetric: Boolean,
    compassHeadingDeg: Float,
    guideBackToRouteActive: Boolean,
    showGuideBackPrompt: Boolean,
    voiceGuidanceEnabled: Boolean,
    onVoiceGuidanceChange: (Boolean) -> Unit,
    onGuideBackToRoute: () -> Unit,
    onLongPress: () -> Unit,
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
    val showGuideBackShortcut = state.active && state.offRoute && !guideBackToRouteActive && !showGuideBackPrompt
    var showGuideBackShortcutConfirm by remember(state.trackTitle) { mutableStateOf(false) }
    LaunchedEffect(state.offRoute, guideBackToRouteActive, showGuideBackPrompt) {
        if (!state.offRoute || guideBackToRouteActive || showGuideBackPrompt) {
            showGuideBackShortcutConfirm = false
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                ),
        contentAlignment = Alignment.Center,
    ) {
        RouteProgressRing(
            progress = state.routeProgressFraction,
            offRoute = state.offRoute,
            modifier = Modifier.fillMaxSize(),
        )

        GuidanceVoiceToggle(
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
            GuidanceGuideBackShortcut(
                onClick = {
                    DebugTelemetry.log("TurnByTurn", "event=guide_back_dashboard_shortcut_click")
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
                                if (state.offRoute) OFF_ROUTE_AMBER else MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    GuidanceManeuverIcon(
                        state = state,
                        compassHeadingDeg = compassHeadingDeg,
                        guideBackToRouteActive = guideBackToRouteActive,
                        modifier = Modifier.size(arrowIconSize),
                        tint = if (state.offRoute) Color.Black else MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = guidancePrimaryText(state, guideBackToRouteActive),
                    color = if (state.offRoute) OFF_ROUTE_AMBER else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = guidanceSecondaryText(state, isMetric, guideBackToRouteActive),
                    color = Color.White.copy(alpha = 0.82f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showRouteProgressDetails) {
                    guidanceTerrainPopupPresentation(state, isMetric)?.let { terrain ->
                        Spacer(modifier = Modifier.size(2.dp))
                        Text(
                            text = terrain.label,
                            color = Color.White.copy(alpha = 0.82f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = terrain.detail,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
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
                    state.distanceRemainingMeters?.let { remaining ->
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = guidanceRemainingText(remaining, state.estimatedRemainingSeconds, isMetric),
                            color = Color.White.copy(alpha = 0.64f),
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                    state.routeProgressFraction?.let { progress ->
                        Spacer(modifier = Modifier.size(1.dp))
                        Text(
                            text = "${(progress * 100f).roundToInt()}%",
                            color = Color.White.copy(alpha = 0.46f),
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        if (showGuideBackShortcutConfirm) {
            GuidanceDecisionPromptCard(
                title = "Route back?",
                detail =
                    state.distanceToRouteMeters?.let {
                        "${formatLiveDistanceLabel(it, isMetric)} from GPX"
                    } ?: "Create route to GPX",
                acceptText = "Guide",
                dismissText = "Cancel",
                onAccept = {
                    showGuideBackShortcutConfirm = false
                    DebugTelemetry.log("TurnByTurn", "event=guide_back_dashboard_shortcut_confirm")
                    onGuideBackToRoute()
                },
                onDismiss = {
                    showGuideBackShortcutConfirm = false
                    DebugTelemetry.log("TurnByTurn", "event=guide_back_dashboard_shortcut_cancel")
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp),
            )
        }
    }
}

internal fun guidanceFollowingText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
): String? {
    if (guidanceShowsCurrentStraight(state)) {
        return state.nextInstruction?.let { "Then ${it.message.lowercase()}" }
    }
    val following = state.followingInstruction ?: return null
    val followingDistance = state.distanceToFollowingInstructionMeters ?: return null
    val currentDistance = state.distanceToInstructionMeters ?: return null
    val gapMeters = followingDistance - currentDistance
    if (gapMeters !in 0.0..FOLLOWING_TURN_MAX_GAP_METERS) return null
    return "Then ${following.message.lowercase()} · ${formatLiveDistanceLabel(gapMeters, isMetric)}"
}

internal fun guidanceRemainingText(
    remainingMeters: Double,
    estimatedRemainingSeconds: Long?,
    isMetric: Boolean,
): String {
    val distance = "${formatLiveDistanceLabel(remainingMeters, isMetric)} remaining"
    val eta = estimatedRemainingSeconds?.let(::formatGuidanceDuration) ?: return distance
    return "$distance · $eta"
}

private fun formatGuidanceDuration(seconds: Long): String {
    val totalMinutes = (seconds.coerceAtLeast(0L) + 30L) / 60L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes.coerceAtLeast(1L)}m"
    }
}

@Composable
private fun GuidanceGuideBackShortcut(
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
            tint = OFF_ROUTE_AMBER,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun GuidanceVoiceToggle(
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

@Composable
private fun GuidanceActionPromptCard(
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 2.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        cappedFontScale(maxFontScale = 1.2f) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                GuidanceMiniIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Dismiss guidance controls",
                    onClick = onCancel,
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                )
                GuidanceMiniIconButton(
                    icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (paused) "Resume guidance" else "Pause guidance",
                    onClick = if (paused) onResume else onPause,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
                GuidanceMiniStopButton(onClick = onStop)
            }
        }
    }
}

@Composable
private fun GuidanceMiniIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
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
                    .size(30.dp)
                    .background(containerColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun GuidanceMiniStopButton(onClick: () -> Unit) {
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
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.94f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop guidance",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
internal fun GuidanceDecisionPromptCard(
    title: String,
    detail: String,
    acceptText: String,
    dismissText: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    tertiaryText: String? = null,
    onTertiary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    GuidancePromptCard(
        title = title,
        detail = detail,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GuideBackPromptButton(
                text = acceptText,
                selected = true,
                onClick = onAccept,
            )
            GuideBackPromptButton(
                text = dismissText,
                selected = false,
                onClick = onDismiss,
            )
            if (tertiaryText != null && onTertiary != null) {
                GuideBackPromptButton(
                    text = tertiaryText,
                    selected = false,
                    onClick = onTertiary,
                )
            }
        }
    }
}

@Composable
private fun GuidancePromptCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    buttons: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .widthIn(max = 180.dp)
                .background(Color.Black.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        cappedFontScale(maxFontScale = 1.2f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    color = Color(0xFFFFB74D),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                buttons()
            }
        }
    }
}

@Composable
private fun GuideBackPromptButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        modifier =
            Modifier
                .widthIn(min = 48.dp)
                .heightIn(min = 40.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(6.dp),
                ).clickable(onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 5.dp),
        color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 10.sp,
    )
}

@Composable
private fun RouteProgressRing(
    progress: Float?,
    offRoute: Boolean,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress?.coerceIn(0f, 1f) ?: return
    val progressColor = if (offRoute) OFF_ROUTE_AMBER else MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val strokeWidth = 5.dp.toPx()
        val inset = strokeWidth / 2f + 5.dp.toPx()
        val side = min(size.width, size.height) - inset * 2f
        if (side <= 0f) return@Canvas
        val topLeft =
            Offset(
                x = (size.width - side) / 2f,
                y = (size.height - side) / 2f,
            )
        val arcSize = Size(side, side)
        drawArc(
            color = Color.White.copy(alpha = 0.12f),
            startAngle = PROGRESS_ARC_START_DEGREES,
            sweepAngle = PROGRESS_ARC_SWEEP_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth),
        )
        if (clampedProgress > 0f) {
            drawArc(
                color = progressColor,
                startAngle = PROGRESS_ARC_START_DEGREES,
                sweepAngle = PROGRESS_ARC_SWEEP_DEGREES * clampedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

private fun guidancePrimaryText(
    state: TurnByTurnGuidanceState,
    guideBackToRouteActive: Boolean = false,
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

private fun guidanceSecondaryText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
    guideBackToRouteActive: Boolean = false,
): String =
    if (guideBackToRouteActive) {
        state.distanceToRouteMeters?.let { formatLiveDistanceLabel(it, isMetric) } ?: "Find route"
    } else if (state.offRoute) {
        state.distanceToRouteMeters?.let { "${formatLiveDistanceLabel(it, isMetric)} from GPX" } ?: "Find route"
    } else {
        when (state.mode) {
            GuidanceMode.WAITING_FOR_LOCATION -> state.trackTitle ?: "GPX guidance"
            GuidanceMode.TO_START ->
                state.distanceToStartMeters?.let { formatLiveDistanceLabel(it, isMetric) }
                    ?: "Find the start"
            GuidanceMode.FOLLOW_ROUTE ->
                guidanceInstructionDistanceText(state, isMetric)
                    ?: "On route"
            GuidanceMode.FINISHED -> state.trackTitle ?: "Route complete"
        }
    }

private fun guidanceCompactText(
    state: TurnByTurnGuidanceState,
    isMetric: Boolean,
    guideBackToRouteActive: Boolean = false,
): String =
    if (guideBackToRouteActive) {
        state.distanceToRouteMeters?.let { "Route ${formatLiveDistanceLabel(it, isMetric)}" } ?: "To route"
    } else if (state.offRoute) {
        state.distanceToRouteMeters?.let { "Off ${formatLiveDistanceLabel(it, isMetric)}" } ?: "Off route"
    } else {
        when (state.mode) {
            GuidanceMode.WAITING_FOR_LOCATION -> "Waiting GPS"
            GuidanceMode.TO_START ->
                state.distanceToStartMeters?.let { "Start ${formatLiveDistanceLabel(it, isMetric)}" }
                    ?: "To start"
            GuidanceMode.FOLLOW_ROUTE -> guidanceCompactInstructionText(state, isMetric)
            GuidanceMode.FINISHED -> "Finished"
        }
    }

private const val FOLLOWING_TURN_MAX_GAP_METERS = 300.0
private const val PROGRESS_ARC_START_DEGREES = -66f
private const val PROGRESS_ARC_SWEEP_DEGREES = 312f
private val OFF_ROUTE_AMBER = Color(0xFFFFC107)
