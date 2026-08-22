package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.formatNavigateClockTime
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun RecordingFullscreenPageShell(
    pageIndex: Int,
    pageCount: Int,
    dragKey: Any?,
    recordingActive: Boolean = false,
    recordingPaused: Boolean = false,
    recordingSaving: Boolean = false,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onShowActions: () -> Unit,
    onDismiss: () -> Unit,
    telemetryTag: String = "TraceRecording",
    content: @Composable BoxScope.() -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusRequester = remember { FocusRequester() }
    var rotaryAccumulator by remember(pageCount) { mutableFloatStateOf(0f) }
    var rotaryFocusGeneration by remember { mutableIntStateOf(0) }
    var wakeResumedAtElapsedMs by remember { mutableLongStateOf(0L) }
    var pendingWakeRotaryEventGeneration by remember { mutableIntStateOf(0) }
    var loggedRotaryEventCount by remember(pageCount, pageIndex, rotaryFocusGeneration) {
        mutableIntStateOf(0)
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (dashboardRotaryLifecycleAction(event)) {
                    DashboardRotaryLifecycleAction.RESET -> {
                        rotaryAccumulator = 0f
                        DebugTelemetry.log(telemetryTag, "event=dashboard_rotary_pause accumulator_reset=true")
                    }

                    DashboardRotaryLifecycleAction.RESET_AND_REFOCUS -> {
                        rotaryAccumulator = 0f
                        rotaryFocusGeneration += 1
                        wakeResumedAtElapsedMs = SystemClock.elapsedRealtime()
                        pendingWakeRotaryEventGeneration = rotaryFocusGeneration
                        DebugTelemetry.log(
                            telemetryTag,
                            "event=dashboard_rotary_wake session=$rotaryFocusGeneration accumulator_reset=true",
                        )
                    }

                    DashboardRotaryLifecycleAction.NONE -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(pageCount, pageIndex, rotaryFocusGeneration) {
        var focusAcquired = false
        var attempt = 0
        while (!focusAcquired && attempt < POPUP_ROTARY_FOCUS_MAX_ATTEMPTS) {
            withFrameNanos { }
            focusAcquired = focusRequester.requestFocus()
            attempt += 1
        }
        DebugTelemetry.log(
            telemetryTag,
            "event=dashboard_rotary_focus_requested acquired=$focusAcquired " +
                "attempts=$attempt reason=${if (rotaryFocusGeneration == 0) "popup_open" else "wake"} " +
                "session=$rotaryFocusGeneration page=${pageIndex + 1} pageCount=$pageCount",
        )
    }
    BackHandler(onBack = onDismiss)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onShowActions,
                ).pointerInput(dragKey, pageIndex, pageCount) {
                    var totalDragY = 0f
                    detectVerticalDragGestures(
                        onDragEnd = {
                            when {
                                totalDragY < -POPUP_PAGE_DRAG_THRESHOLD_PX -> onNextPage()
                                totalDragY > POPUP_PAGE_DRAG_THRESHOLD_PX -> onPreviousPage()
                            }
                            totalDragY = 0f
                        },
                        onDragCancel = {
                            totalDragY = 0f
                        },
                    ) { _, dragAmount ->
                        totalDragY += dragAmount
                    }
                }.onPreRotaryScrollEvent { event ->
                    if (
                        rotaryFocusGeneration > 0 &&
                            pendingWakeRotaryEventGeneration == rotaryFocusGeneration
                    ) {
                        val latencyMs =
                            (SystemClock.elapsedRealtime() - wakeResumedAtElapsedMs).coerceAtLeast(0L)
                        pendingWakeRotaryEventGeneration = 0
                        DebugTelemetry.log(
                            telemetryTag,
                            "event=dashboard_rotary_first_event_after_wake session=$rotaryFocusGeneration " +
                                "latencyMs=$latencyMs delta=${event.verticalScrollPixels}",
                        )
                    }
                    val consumed =
                        handleRecordingRotaryPageEvent(
                            delta = event.verticalScrollPixels,
                            pageCount = pageCount,
                            accumulator = rotaryAccumulator,
                            onAccumulatorChange = { rotaryAccumulator = it },
                            onPreviousPage = onPreviousPage,
                            onNextPage = onNextPage,
                        )
                    if (loggedRotaryEventCount < DASHBOARD_ROTARY_EVENT_LOG_LIMIT) {
                        loggedRotaryEventCount += 1
                        DebugTelemetry.log(
                            telemetryTag,
                            "event=dashboard_rotary_event index=$loggedRotaryEventCount " +
                                "delta=${event.verticalScrollPixels} consumed=$consumed " +
                                "accumulator=$rotaryAccumulator page=${pageIndex + 1}",
                        )
                    }
                    consumed
                }.focusRequester(focusRequester)
                .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        content()
        FullscreenPopupTimeChip(
            recordingActive = recordingActive,
            recordingPaused = recordingPaused,
            recordingSaving = recordingSaving,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp),
        )
        RecordingDashboardPageIndicator(
            pageIndex = pageIndex,
            pageCount = pageCount,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
        )
    }
}

internal val LocalFullscreenPopupTimeFormat =
    staticCompositionLocalOf { SettingsRepository.TIME_FORMAT_24_HOUR }

@Composable
private fun FullscreenPopupTimeChip(
    recordingActive: Boolean,
    recordingPaused: Boolean,
    recordingSaving: Boolean,
    modifier: Modifier = Modifier,
) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val recordingVisible = recordingActive || recordingPaused || recordingSaving
    val accentColor =
        when {
            recordingSaving || recordingPaused -> Color(0xFFFFB74D)
            recordingActive -> Color(0xFFFF1744)
            else -> MaterialTheme.colorScheme.primary
        }
    Box(
        modifier =
            modifier
                .width(128.dp)
                .height(48.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .height(20.dp)
                    .widthIn(min = 62.dp)
                    .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(percent = 50))
                    .border(1.dp, accentColor.copy(alpha = 0.96f), RoundedCornerShape(percent = 50))
                    .padding(start = if (recordingVisible) 7.dp else 10.dp, end = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            cappedFontScale(maxFontScale = 1f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (recordingVisible) {
                        Box(
                            modifier =
                                Modifier
                                    .size(4.dp)
                                    .background(accentColor, CircleShape),
                        )
                    }
                    Text(
                        text = formatNavigateClockTime(nowMillis, LocalFullscreenPopupTimeFormat.current),
                        modifier = Modifier.padding(start = if (recordingVisible) 5.dp else 0.dp),
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 15.sp,
                            ),
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal fun logRecordingDashboardPageChange(
    pageIndex: Int,
    pageCount: Int,
    source: String,
) {
    DebugTelemetry.log(
        "TraceRecording",
        "event=dashboard_page_change page=${pageIndex + 1} pageCount=$pageCount source=$source",
    )
}

@Composable
private fun RecordingDashboardPageIndicator(
    pageIndex: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val adaptive = rememberWearAdaptiveSpec()
    if (adaptive.isRound) {
        RecordingDashboardArcPageIndicator(
            pageIndex = pageIndex,
            pageCount = pageCount,
            modifier = modifier,
        )
    } else {
        RecordingDashboardStraightPageIndicator(
            pageIndex = pageIndex,
            pageCount = pageCount,
            modifier = modifier,
        )
    }
}

@Composable
private fun RecordingDashboardStraightPageIndicator(
    pageIndex: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .height(if (index == pageIndex) 14.dp else 5.dp)
                        .background(
                            color = Color.White.copy(alpha = if (index == pageIndex) 0.72f else 0.28f),
                            shape = RoundedCornerShape(percent = 50),
                        ),
            )
        }
    }
}

@Composable
private fun RecordingDashboardArcPageIndicator(
    pageIndex: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .width(30.dp)
                .height(84.dp),
    ) {
        val sweepDegrees =
            when {
                pageCount <= 2 -> 24f
                pageCount == 3 -> 34f
                else -> 46f
            }
        val centerY = size.height / 2f
        val centerX = -size.height * 0.48f
        val rightInset = 6.dp.toPx()
        val radius = size.width - rightInset - centerX
        val activeRadius = 3.0.dp.toPx()
        val inactiveRadius = 2.0.dp.toPx()

        repeat(pageCount) { index ->
            val fraction =
                if (pageCount == 1) {
                    0.5f
                } else {
                    index.toFloat() / (pageCount - 1).toFloat()
                }
            val angleRadians = ((-sweepDegrees / 2f) + sweepDegrees * fraction) * (PI.toFloat() / 180f)
            val active = index == pageIndex
            drawCircle(
                color = Color.White.copy(alpha = if (active) 0.78f else 0.28f),
                radius = if (active) activeRadius else inactiveRadius,
                center =
                    Offset(
                        x = centerX + cos(angleRadians) * radius,
                        y = centerY + sin(angleRadians) * radius,
                    ),
            )
        }
    }
}

private fun handleRecordingRotaryPageEvent(
    delta: Float,
    pageCount: Int,
    accumulator: Float,
    onAccumulatorChange: (Float) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
): Boolean {
    if (!delta.isFinite() || delta == 0f) return false
    if (pageCount <= 1) return true
    var nextAccumulator =
        if (accumulator != 0f && (accumulator > 0f) != (delta > 0f)) {
            0f
        } else {
            accumulator
        }
    nextAccumulator += delta
    var consumed = false
    if (nextAccumulator >= POPUP_ROTARY_PAGE_THRESHOLD_PX) {
        onNextPage()
        nextAccumulator = 0f
        consumed = true
    }
    if (nextAccumulator <= -POPUP_ROTARY_PAGE_THRESHOLD_PX) {
        onPreviousPage()
        nextAccumulator = 0f
        consumed = true
    }
    onAccumulatorChange(nextAccumulator)
    return consumed || nextAccumulator != 0f
}

private const val POPUP_PAGE_DRAG_THRESHOLD_PX = 24f
private const val POPUP_ROTARY_PAGE_THRESHOLD_PX = 56f
private const val POPUP_ROTARY_FOCUS_MAX_ATTEMPTS = 20
private const val DASHBOARD_ROTARY_EVENT_LOG_LIMIT = 3

internal enum class DashboardRotaryLifecycleAction {
    NONE,
    RESET,
    RESET_AND_REFOCUS,
}

internal fun dashboardRotaryLifecycleAction(event: Lifecycle.Event): DashboardRotaryLifecycleAction =
    when (event) {
        Lifecycle.Event.ON_PAUSE -> DashboardRotaryLifecycleAction.RESET
        Lifecycle.Event.ON_RESUME -> DashboardRotaryLifecycleAction.RESET_AND_REFOCUS
        else -> DashboardRotaryLifecycleAction.NONE
    }

internal fun Int.floorMod(modulus: Int): Int =
    if (modulus <= 0) {
        0
    } else {
        ((this % modulus) + modulus) % modulus
    }

internal const val POPUP_MINIMIZE_DRAG_THRESHOLD_PX = 24f
internal const val POPUP_EXPAND_DRAG_THRESHOLD_PX = 24f
