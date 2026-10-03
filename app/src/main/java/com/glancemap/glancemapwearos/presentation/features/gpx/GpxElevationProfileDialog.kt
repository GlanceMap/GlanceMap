package com.glancemap.glancemapwearos.presentation.features.gpx

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.ui.WearWindowClass
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec

private data class ElevationProfileDialogSizing(
    val outerPadding: Dp,
    val surfaceWidthFraction: Float,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val contentSpacing: Dp,
    val chartHeight: Dp,
    val chartTopInset: Dp,
    val chartBottomAxisInset: Dp,
    val chartFingerSafeZone: Dp,
    val yScaleWidth: Dp,
    val statsIconSize: Dp,
    val statsSummaryTextSize: TextUnit,
    val axisTextSize: TextUnit,
    val yScaleTextSize: TextUnit,
)

@Composable
private fun rememberElevationProfileDialogSizing(): ElevationProfileDialogSizing {
    val adaptive = rememberWearAdaptiveSpec()
    return when (adaptive.windowClass) {
        WearWindowClass.EXPANDED -> {
            if (adaptive.isRound) {
                ElevationProfileDialogSizing(
                    outerPadding = 12.dp,
                    surfaceWidthFraction = 0.90f,
                    horizontalPadding = 14.dp,
                    verticalPadding = 11.dp,
                    contentSpacing = 6.dp,
                    chartHeight = 134.dp,
                    chartTopInset = 12.dp,
                    chartBottomAxisInset = 8.dp,
                    chartFingerSafeZone = 16.dp,
                    yScaleWidth = 38.dp,
                    statsIconSize = 18.dp,
                    statsSummaryTextSize = 11.sp,
                    axisTextSize = 12.sp,
                    yScaleTextSize = 10.sp,
                )
            } else {
                ElevationProfileDialogSizing(
                    outerPadding = 8.dp,
                    surfaceWidthFraction = 0.94f,
                    horizontalPadding = 14.dp,
                    verticalPadding = 11.dp,
                    contentSpacing = 6.dp,
                    chartHeight = 136.dp,
                    chartTopInset = 12.dp,
                    chartBottomAxisInset = 8.dp,
                    chartFingerSafeZone = 16.dp,
                    yScaleWidth = 36.dp,
                    statsIconSize = 18.dp,
                    statsSummaryTextSize = 11.sp,
                    axisTextSize = 12.sp,
                    yScaleTextSize = 10.sp,
                )
            }
        }

        WearWindowClass.STANDARD -> {
            if (adaptive.isRound) {
                ElevationProfileDialogSizing(
                    outerPadding = 11.dp,
                    surfaceWidthFraction = 0.92f,
                    horizontalPadding = 12.dp,
                    verticalPadding = 10.dp,
                    contentSpacing = 5.dp,
                    chartHeight = 126.dp,
                    chartTopInset = 11.dp,
                    chartBottomAxisInset = 7.dp,
                    chartFingerSafeZone = 14.dp,
                    yScaleWidth = 35.dp,
                    statsIconSize = 16.dp,
                    statsSummaryTextSize = 10.sp,
                    axisTextSize = 11.sp,
                    yScaleTextSize = 10.sp,
                )
            } else {
                ElevationProfileDialogSizing(
                    outerPadding = 8.dp,
                    surfaceWidthFraction = 0.96f,
                    horizontalPadding = 12.dp,
                    verticalPadding = 10.dp,
                    contentSpacing = 5.dp,
                    chartHeight = 128.dp,
                    chartTopInset = 11.dp,
                    chartBottomAxisInset = 7.dp,
                    chartFingerSafeZone = 14.dp,
                    yScaleWidth = 33.dp,
                    statsIconSize = 16.dp,
                    statsSummaryTextSize = 10.sp,
                    axisTextSize = 11.sp,
                    yScaleTextSize = 10.sp,
                )
            }
        }

        WearWindowClass.COMPACT -> {
            if (adaptive.isRound) {
                ElevationProfileDialogSizing(
                    outerPadding = 10.dp,
                    surfaceWidthFraction = 0.94f,
                    horizontalPadding = 11.dp,
                    verticalPadding = 8.dp,
                    contentSpacing = 4.dp,
                    chartHeight = 112.dp,
                    chartTopInset = 10.dp,
                    chartBottomAxisInset = 6.dp,
                    chartFingerSafeZone = 12.dp,
                    yScaleWidth = 32.dp,
                    statsIconSize = 15.dp,
                    statsSummaryTextSize = 10.sp,
                    axisTextSize = 10.sp,
                    yScaleTextSize = 10.sp,
                )
            } else {
                ElevationProfileDialogSizing(
                    outerPadding = 7.dp,
                    surfaceWidthFraction = 0.97f,
                    horizontalPadding = 10.dp,
                    verticalPadding = 8.dp,
                    contentSpacing = 4.dp,
                    chartHeight = 114.dp,
                    chartTopInset = 10.dp,
                    chartBottomAxisInset = 6.dp,
                    chartFingerSafeZone = 10.dp,
                    yScaleWidth = 30.dp,
                    statsIconSize = 15.dp,
                    statsSummaryTextSize = 10.sp,
                    axisTextSize = 10.sp,
                    yScaleTextSize = 10.sp,
                )
            }
        }
    }
}

// Existing dialog state intentionally keeps zoom, selection, and touch handling in one composable.
@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
fun GpxElevationProfileDialog(
    profile: GpxElevationProfileUiState,
    locationMarker: ElevationProfileLocationMarker?,
    isMetric: Boolean,
    onDismiss: () -> Unit,
) {
    val sizing = rememberElevationProfileDialogSizing()
    val samples = profile.samples

    var selectedIndex by remember(profile.trackPath, samples.size) {
        mutableIntStateOf(0)
    }
    val safeLastIndex = (samples.size - 1).coerceAtLeast(0)
    selectedIndex = selectedIndex.coerceIn(0, safeLastIndex)
    val focusRequester = remember { FocusRequester() }
    val trackDistance =
        remember(samples, profile.totalDistance) {
            maxOf(profile.totalDistance, samples.lastOrNull()?.distance ?: 0.0)
        }
    val minimumVisibleDistance =
        remember(samples, trackDistance) {
            computeMinimumVisibleDistance(samples = samples, totalDistance = trackDistance)
        }
    var viewport by remember(profile.trackPath, samples.size, trackDistance) {
        mutableStateOf(
            ElevationViewport(
                startDistance = 0.0,
                endDistance = trackDistance,
            ),
        )
    }
    var rotaryScrollAccumulator by remember(profile.trackPath) {
        mutableStateOf(0f)
    }

    LaunchedEffect(profile.trackPath) {
        focusRequester.requestFocus()
    }

    val progress =
        remember(profile, selectedIndex) {
            computeElevationProgress(profile = profile, selectedIndex = selectedIndex)
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(sizing.surfaceWidthFraction)
                        .onPreRotaryScrollEvent { event ->
                            if (samples.size < 2 || trackDistance <= 0.0) return@onPreRotaryScrollEvent false

                            val delta = event.verticalScrollPixels
                            if (!delta.isFinite() || delta == 0f) return@onPreRotaryScrollEvent false

                            rotaryScrollAccumulator += delta
                            var updatedViewport = viewport
                            var consumed = false

                            while (rotaryScrollAccumulator >= ROTARY_ZOOM_THRESHOLD_PX) {
                                updatedViewport =
                                    zoomViewportAroundDistance(
                                        current = updatedViewport,
                                        anchorDistance =
                                            samples
                                                .getOrNull(selectedIndex)
                                                ?.distance
                                                ?: (updatedViewport.startDistance + updatedViewport.span / 2.0),
                                        totalDistance = trackDistance,
                                        minimumSpan = minimumVisibleDistance,
                                        zoomIn = true,
                                    )
                                consumed = consumed || updatedViewport != viewport
                                rotaryScrollAccumulator -= ROTARY_ZOOM_THRESHOLD_PX
                            }
                            while (rotaryScrollAccumulator <= -ROTARY_ZOOM_THRESHOLD_PX) {
                                updatedViewport =
                                    zoomViewportAroundDistance(
                                        current = updatedViewport,
                                        anchorDistance =
                                            samples
                                                .getOrNull(selectedIndex)
                                                ?.distance
                                                ?: (updatedViewport.startDistance + updatedViewport.span / 2.0),
                                        totalDistance = trackDistance,
                                        minimumSpan = minimumVisibleDistance,
                                        zoomIn = false,
                                    )
                                consumed = consumed || updatedViewport != viewport
                                rotaryScrollAccumulator += ROTARY_ZOOM_THRESHOLD_PX
                            }

                            if (updatedViewport != viewport) {
                                viewport = updatedViewport
                            }
                            consumed
                        }.focusRequester(focusRequester)
                        .focusable()
                        .pointerInput(Unit) {
                            var downwardDrag = 0f
                            detectVerticalDragGestures(
                                onDragEnd = { downwardDrag = 0f },
                                onDragCancel = { downwardDrag = 0f },
                            ) { _, dragAmount ->
                                if (dragAmount > 0f) {
                                    downwardDrag += dragAmount
                                    if (downwardDrag > SWIPE_DISMISS_THRESHOLD_PX) {
                                        onDismiss()
                                        downwardDrag = 0f
                                    }
                                } else {
                                    downwardDrag = 0f
                                }
                            }
                        }.padding(sizing.outerPadding)
                        .padding(
                            horizontal = sizing.horizontalPadding,
                            vertical = sizing.verticalPadding,
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sizing.contentSpacing),
            ) {
                SwipeHandleHint()

                if (samples.size < 2 || profile.minElevation == null || profile.maxElevation == null) {
                    Text(
                        text = "No elevation data in this GPX.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val doneDistanceText =
                        progress?.let {
                            formatDistanceScaleText(it.doneDistance, isMetric)
                        } ?: formatDistanceScaleText(0.0, isMetric)
                    val doneEta = formatDurationText(progress?.doneDurationSec)

                    val doneUp = formatElevationCompactText(progress?.doneAscent ?: 0.0, isMetric)
                    val remainingUp =
                        formatElevationCompactText(
                            (profile.totalAscent - (progress?.doneAscent ?: 0.0)).coerceAtLeast(0.0),
                            isMetric,
                        )
                    val doneDown = formatElevationCompactText(progress?.doneDescent ?: 0.0, isMetric)
                    val remainingDown =
                        formatElevationCompactText(
                            (profile.totalDescent - (progress?.doneDescent ?: 0.0)).coerceAtLeast(0.0),
                            isMetric,
                        )
                    val ascentSummaryText = "$doneUp/$remainingUp"
                    val descentSummaryText = "$doneDown/$remainingDown"
                    val movingChipTextSize = sizing.axisTextSize

                    ElevationDeltaSummaryRow(
                        ascentText = ascentSummaryText,
                        descentText = descentSummaryText,
                        iconSize = sizing.statsIconSize,
                        textSize = sizing.statsSummaryTextSize,
                    )

                    BoxWithConstraints(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(sizing.chartHeight),
                    ) {
                        val density = LocalDensity.current
                        val chartHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                        val plotTopInsetPx = with(density) { sizing.chartTopInset.toPx() }
                        val plotBottomInsetPx =
                            with(density) {
                                (sizing.chartBottomAxisInset + sizing.chartFingerSafeZone).toPx()
                            }
                        val selectedElevationBias =
                            remember(
                                samples,
                                selectedIndex,
                                chartHeightPx,
                                plotTopInsetPx,
                                plotBottomInsetPx,
                            ) {
                                selectedElevationBiasY(
                                    samples = samples,
                                    selectedIndex = selectedIndex,
                                    chartHeightPx = chartHeightPx,
                                    plotTopInsetPx = plotTopInsetPx,
                                    plotBottomInsetPx = plotBottomInsetPx,
                                )
                            }
                        val selectedElevationText =
                            remember(samples, selectedIndex, isMetric) {
                                samples.getOrNull(selectedIndex)?.elevation?.let {
                                    formatElevationScaleTick(it, isMetric)
                                } ?: "--"
                            }
                        val visualElevationBounds =
                            remember(samples) {
                                elevationVisualBounds(samples)
                            }

                        ElevationProfileChart(
                            samples = samples,
                            selectedIndex = selectedIndex,
                            locationDistanceMeters = locationMarker?.distance,
                            onSelectIndex = { selectedIndex = it },
                            viewport = viewport,
                            plotTopInset = sizing.chartTopInset,
                            plotBottomInset = sizing.chartBottomAxisInset + sizing.chartFingerSafeZone,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ElevationLeftScale(
                            minElevationMeters = visualElevationBounds.minElevation,
                            maxElevationMeters = visualElevationBounds.maxElevation,
                            isMetric = isMetric,
                            textSize = sizing.yScaleTextSize,
                            modifier =
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxSize()
                                    .width(sizing.yScaleWidth)
                                    .padding(
                                        start = 3.dp,
                                        top = sizing.chartTopInset,
                                        bottom = sizing.chartBottomAxisInset + sizing.chartFingerSafeZone,
                                    ),
                        )
                        MovingAxisChip(
                            text = doneEta,
                            textSize = movingChipTextSize,
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 4.dp),
                        )
                        MovingAxisChip(
                            text = doneDistanceText,
                            textSize = movingChipTextSize,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 3.dp),
                        )
                        CurrentElevationLabel(
                            value = selectedElevationText,
                            textSize = sizing.axisTextSize,
                            modifier =
                                Modifier
                                    .align(BiasAlignment(1f, selectedElevationBias))
                                    .padding(end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
