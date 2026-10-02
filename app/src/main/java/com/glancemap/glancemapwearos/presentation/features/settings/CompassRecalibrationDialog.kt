package com.glancemap.glancemapwearos.presentation.features.settings

import android.os.SystemClock
import android.os.VibrationEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassViewModel
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.presentation.features.navigate.CompassMarkerQuality
import com.glancemap.glancemapwearos.presentation.features.navigate.compassQualityReadingFromRenderState
import com.glancemap.glancemapwearos.presentation.features.navigate.compassQualityTransitionHoldMs
import com.glancemap.glancemapwearos.presentation.features.navigate.coneColorArgbForQuality
import com.glancemap.glancemapwearos.presentation.ui.KeepScreenOnEffect
import com.glancemap.glancemapwearos.presentation.ui.WearVerticalScrollIndicator
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CompassRecalibrationDialog(
    compassViewModel: CompassViewModel,
    onApplyRecalibration: () -> Unit,
    onComplete: (Boolean) -> Unit,
) {
    KeepScreenOnEffect()

    val context = LocalContext.current
    val tokens = rememberCompassRecalibrationDialogTokens()
    val configuration = LocalConfiguration.current
    val dialogMaxHeight =
        (configuration.screenHeightDp.dp - (tokens.viewportVerticalInset * 2)).coerceAtLeast(120.dp)
    val vibrator = remember { vibratorFrom(context) }
    val compassRenderState by compassViewModel.renderState.collectAsState()
    val compassAccuracy = compassRenderState.accuracy
    val compassQualityReading =
        compassQualityReadingFromRenderState(
            renderState = compassRenderState,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
    val activeHeadingSource = compassRenderState.headingSource
    val headingSourceStatus = compassRenderState.headingSourceStatus
    val activeProviderType = compassRenderState.providerType
    var phase by remember { mutableStateOf(CalibrationPhase.MEASURING) }
    var remainingMs by remember { mutableLongStateOf(CALIBRATION_TIMEOUT_MS) }
    var startedAtMs by remember { mutableLongStateOf(0L) }
    var holdStillStartedAtMs by remember { mutableLongStateOf(0L) }
    var holdStillRemainingMs by remember { mutableLongStateOf(HOLD_STILL_TIMEOUT_MS) }
    var countdownStarted by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var lastQuality by remember { mutableStateOf<CompassMarkerQuality?>(null) }
    var displayedQuality by remember { mutableStateOf(CompassMarkerQuality.LOW) }
    var hasDisplayedInitialQuality by remember { mutableStateOf(false) }
    var initialQualityDisplayedAtMs by remember { mutableLongStateOf(0L) }
    var initialQuality by remember { mutableStateOf<CompassMarkerQuality?>(null) }
    var resultQuality by remember { mutableStateOf<CompassMarkerQuality?>(null) }
    var qualityImproved by remember { mutableStateOf(false) }
    var successHapticPlayed by remember { mutableStateOf(false) }
    val holdStillQualitySamples = remember { mutableStateListOf<CompassMarkerQuality>() }
    var lastLoggedDisplayedQuality by remember { mutableStateOf<CompassMarkerQuality?>(null) }
    val hasAnyHeadingSource =
        headingSourceStatus.headingSensorAvailable ||
            headingSourceStatus.rotationVectorAvailable ||
            headingSourceStatus.magAccelFallbackAvailable
    val hasQualitySample =
        activeProviderType == CompassProviderType.SENSOR_MANAGER &&
            activeHeadingSource != HeadingSource.NONE
    val rawQuality = compassQualityReading.quality
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    fun finish(success: Boolean) {
        if (completed) return
        completed = true
        logCompassCalibrationTelemetry(
            "dialog_close success=$success provider=${activeProviderType.name} " +
                "source=${activeHeadingSource.telemetryToken} accuracy=$compassAccuracy " +
                "initial=${(initialQuality ?: displayedQuality).name} " +
                "result=${(resultQuality ?: displayedQuality).name}",
        )
        onComplete(success)
    }

    fun currentResultSuccess(): Boolean =
        didCompleteCompassRecalibration(
            providerType = activeProviderType,
            headingSourceResolved = activeHeadingSource != HeadingSource.NONE,
            qualityImproved = qualityImproved,
            resultQuality = resultQuality ?: displayedQuality,
        )

    fun restartCalibrationFlow() {
        logCompassCalibrationTelemetry(
            "flow_restart provider=${activeProviderType.name} raw=${rawQuality?.name ?: "UNKNOWN"}",
        )
        phase = CalibrationPhase.MEASURING
        remainingMs = CALIBRATION_TIMEOUT_MS
        startedAtMs = 0L
        holdStillStartedAtMs = 0L
        holdStillRemainingMs = HOLD_STILL_TIMEOUT_MS
        countdownStarted = false
        lastQuality = null
        displayedQuality = rawQuality ?: CompassMarkerQuality.LOW
        hasDisplayedInitialQuality = false
        initialQualityDisplayedAtMs = 0L
        initialQuality = null
        resultQuality = null
        qualityImproved = false
        holdStillQualitySamples.clear()
        lastLoggedDisplayedQuality = null
    }

    LaunchedEffect(phase, hasAnyHeadingSource, activeHeadingSource) {
        if (phase != CalibrationPhase.MEASURING) return@LaunchedEffect
        if (activeProviderType != CompassProviderType.SENSOR_MANAGER) {
            logCompassCalibrationTelemetry(
                "dialog_unsupported reason=provider_not_custom provider=${activeProviderType.name}",
            )
            phase = CalibrationPhase.UNSUPPORTED
            return@LaunchedEffect
        }
        if (!hasAnyHeadingSource) {
            logCompassCalibrationTelemetry(
                "dialog_unsupported reason=no_heading_source provider=${activeProviderType.name}",
            )
            phase = CalibrationPhase.UNSUPPORTED
            return@LaunchedEffect
        }
        if (activeHeadingSource != HeadingSource.NONE) return@LaunchedEffect
        delay(COMPASS_SOURCE_RESOLUTION_TIMEOUT_MS)
        if (phase == CalibrationPhase.MEASURING && activeHeadingSource == HeadingSource.NONE) {
            logCompassCalibrationTelemetry(
                "dialog_unsupported reason=source_resolution_timeout provider=${activeProviderType.name}",
            )
            phase = CalibrationPhase.UNSUPPORTED
        }
    }

    // Start countdown only after current heading quality is visible/stabilized.
    LaunchedEffect(
        phase,
        hasQualitySample,
        countdownStarted,
        hasDisplayedInitialQuality,
        initialQualityDisplayedAtMs,
    ) {
        if (phase != CalibrationPhase.MEASURING) return@LaunchedEffect
        if (!hasQualitySample || countdownStarted || !hasDisplayedInitialQuality) return@LaunchedEffect
        if (initialQualityDisplayedAtMs <= 0L) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        val waitMs = (initialQualityDisplayedAtMs + COUNTDOWN_AFTER_QUALITY_VISIBLE_MS) - now
        if (waitMs > 0L) {
            delay(waitMs)
        }
        if (phase != CalibrationPhase.MEASURING || countdownStarted || !hasDisplayedInitialQuality) {
            return@LaunchedEffect
        }
        startedAtMs = SystemClock.elapsedRealtime()
        remainingMs = CALIBRATION_TIMEOUT_MS
        countdownStarted = true
        initialQuality = rawQuality ?: displayedQuality
        logCompassCalibrationTelemetry(
            "move_phase_started provider=${activeProviderType.name} " +
                "source=${activeHeadingSource.telemetryToken} " +
                "initial=${(rawQuality ?: displayedQuality).name} accuracy=$compassAccuracy",
        )
        onApplyRecalibration()
        logCompassCalibrationTelemetry("custom_reset_applied phase=${phase.name}")
    }

    // First stage: movement routine.
    LaunchedEffect(phase, startedAtMs, countdownStarted) {
        if (phase != CalibrationPhase.MEASURING) return@LaunchedEffect
        if (!countdownStarted || startedAtMs <= 0L) return@LaunchedEffect

        while (phase == CalibrationPhase.MEASURING) {
            val now = SystemClock.elapsedRealtime()
            remainingMs = (CALIBRATION_TIMEOUT_MS - (now - startedAtMs)).coerceAtLeast(0L)

            if (remainingMs == 0L) {
                logCompassCalibrationTelemetry(
                    "move_phase_complete provider=${activeProviderType.name} " +
                        "displayed=${displayedQuality.name} raw=${rawQuality?.name ?: "UNKNOWN"}",
                )
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        MOVE_PHASE_COMPLETE_HAPTIC_MS,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
                holdStillStartedAtMs = SystemClock.elapsedRealtime()
                holdStillRemainingMs = HOLD_STILL_TIMEOUT_MS
                holdStillQualitySamples.clear()
                rawQuality?.let { holdStillQualitySamples.add(it) }
                phase = CalibrationPhase.HOLD_STILL
                break
            }

            delay(200L)
        }
    }

    // Second stage: hold still and measure final quality.
    LaunchedEffect(phase, rawQuality, hasQualitySample) {
        if (phase != CalibrationPhase.HOLD_STILL) return@LaunchedEffect
        if (!hasQualitySample || rawQuality == null) return@LaunchedEffect
        holdStillQualitySamples.add(rawQuality)
    }

    LaunchedEffect(phase, holdStillStartedAtMs) {
        if (phase != CalibrationPhase.HOLD_STILL) return@LaunchedEffect
        if (holdStillStartedAtMs <= 0L) return@LaunchedEffect
        val timeoutMs = HOLD_STILL_TIMEOUT_MS
        while (phase == CalibrationPhase.HOLD_STILL) {
            val now = SystemClock.elapsedRealtime()
            holdStillRemainingMs =
                (timeoutMs - (now - holdStillStartedAtMs)).coerceAtLeast(0L)
            if (holdStillRemainingMs == 0L) {
                val finalQuality =
                    summarizeHoldStillQuality(
                        samples = holdStillQualitySamples,
                        fallback = rawQuality ?: displayedQuality,
                    )
                resultQuality = finalQuality
                displayedQuality = finalQuality
                qualityImproved =
                    compassQualityRank(finalQuality) > compassQualityRank(initialQuality ?: finalQuality)
                logCompassCalibrationTelemetry(
                    "result_ready provider=${activeProviderType.name} " +
                        "initial=${(initialQuality ?: finalQuality).name} " +
                        "final=${finalQuality.name} improved=$qualityImproved " +
                        "samples=${holdStillQualitySamples.size}",
                )
                phase = CalibrationPhase.RESULT
                break
            }
            delay(120L)
        }
    }

    // Stabilize quality display so it doesn't flip quickly due transient sensor noise.
    LaunchedEffect(phase, rawQuality, displayedQuality, hasQualitySample, activeHeadingSource) {
        if (phase == CalibrationPhase.RESULT) return@LaunchedEffect
        if (phase == CalibrationPhase.HOLD_STILL || phase == CalibrationPhase.UNSUPPORTED) {
            rawQuality?.let { displayedQuality = it }
            return@LaunchedEffect
        }
        if (phase != CalibrationPhase.MEASURING) return@LaunchedEffect
        if (hasQualitySample && rawQuality != null && !hasDisplayedInitialQuality) {
            displayedQuality = rawQuality
            hasDisplayedInitialQuality = true
            initialQualityDisplayedAtMs = SystemClock.elapsedRealtime()
            return@LaunchedEffect
        }
        if (!hasQualitySample || rawQuality == null || rawQuality == displayedQuality) return@LaunchedEffect
        delay(
            compassQualityTransitionHoldMs(
                from = displayedQuality,
                to = rawQuality,
            ),
        )
        if (phase == CalibrationPhase.MEASURING && activeHeadingSource != HeadingSource.NONE) {
            displayedQuality = rawQuality
        }
    }

    LaunchedEffect(Unit) {
        logCompassCalibrationTelemetry(
            "dialog_open provider=${activeProviderType.name} source=${activeHeadingSource.telemetryToken} " +
                "accuracy=$compassAccuracy hasAnyHeadingSource=$hasAnyHeadingSource",
        )
    }

    LaunchedEffect(phase, activeProviderType) {
        logCompassCalibrationTelemetry(
            "phase=${phase.name} provider=${activeProviderType.name}",
        )
    }

    LaunchedEffect(displayedQuality, rawQuality, phase, compassAccuracy) {
        if (lastLoggedDisplayedQuality == displayedQuality) return@LaunchedEffect
        lastLoggedDisplayedQuality = displayedQuality
        logCompassCalibrationTelemetry(
            "quality_display phase=${phase.name} displayed=${displayedQuality.name} " +
                "raw=${rawQuality?.name ?: "UNKNOWN"} accuracy=$compassAccuracy " +
                "sampleAgeMs=${compassQualityReading.sampleAgeMs ?: -1L} " +
                "stale=${compassQualityReading.isStale}",
        )
    }

    // Haptic cue when entering GOOD quality.
    LaunchedEffect(displayedQuality, hasQualitySample, phase) {
        if (phase != CalibrationPhase.MEASURING && phase != CalibrationPhase.HOLD_STILL) return@LaunchedEffect
        if (
            hasQualitySample &&
            displayedQuality == CompassMarkerQuality.GOOD &&
            lastQuality != CompassMarkerQuality.GOOD
        ) {
            vibrator?.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        lastQuality = displayedQuality
    }

    // Completion haptic cue (wait for explicit user "Done" to close).
    LaunchedEffect(
        phase,
        qualityImproved,
        activeProviderType,
        activeHeadingSource,
        resultQuality,
    ) {
        if (phase == CalibrationPhase.RESULT) {
            if (!successHapticPlayed) {
                successHapticPlayed = true
                val durationMs =
                    if (
                        didCompleteCompassRecalibration(
                            providerType = activeProviderType,
                            headingSourceResolved = activeHeadingSource != HeadingSource.NONE,
                            qualityImproved = qualityImproved,
                            resultQuality = resultQuality ?: displayedQuality,
                        )
                    ) {
                        90L
                    } else {
                        45L
                    }
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            successHapticPlayed = false
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        visible = true,
        onDismissRequest = { finish(false) },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .padding(vertical = tokens.viewportVerticalInset),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tokens.dialogContentSpacing),
                modifier =
                    Modifier
                        .fillMaxWidth(tokens.dialogWidthFraction)
                        .heightIn(max = dialogMaxHeight)
                        .onPreRotaryScrollEvent { event ->
                            val consumed = scrollState.dispatchRawDelta(event.verticalScrollPixels)
                            abs(consumed) > 0.5f
                        }.focusRequester(focusRequester)
                        .focusable()
                        .verticalScroll(scrollState)
                        .padding(
                            horizontal = tokens.dialogHorizontalPadding,
                            vertical = tokens.dialogVerticalPadding,
                        ),
            ) {
                Text(
                    text = "Recalibrate Compass",
                    fontSize = tokens.titleFontSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = tokens.titleTopPadding),
                )

                Text(
                    text =
                        compassRecalibrationBodyText(
                            state =
                                CompassRecalibrationBodyState(
                                    phase = phase,
                                    providerType = activeProviderType,
                                    hasAnyHeadingSource = hasAnyHeadingSource,
                                    initialQuality = initialQuality ?: displayedQuality,
                                    resultQuality = resultQuality ?: displayedQuality,
                                    qualityImproved = qualityImproved,
                                ),
                        ),
                    fontSize = tokens.bodyFontSize,
                    lineHeight = tokens.bodyLineHeight,
                    textAlign = TextAlign.Center,
                )

                if (
                    phase == CalibrationPhase.MEASURING ||
                    phase == CalibrationPhase.HOLD_STILL ||
                    phase == CalibrationPhase.RESULT
                ) {
                    val meterQuality =
                        when {
                            phase == CalibrationPhase.HOLD_STILL -> displayedQuality
                            else -> resultQuality ?: displayedQuality
                        }
                    QualityMeter(
                        hasQualitySample = hasQualitySample,
                        quality = meterQuality,
                        widthFraction = tokens.qualityMeterWidthFraction,
                        barSpacing = tokens.qualityMeterSpacing,
                        barWidth = tokens.qualityBarWidth,
                        barHeight = tokens.qualityBarHeight,
                        barCornerRadius = tokens.qualityBarCornerRadius,
                    )
                    Text(
                        text =
                            compassQualityLabel(
                                hasQualitySample = hasQualitySample,
                                quality = meterQuality,
                                providerType = activeProviderType,
                                headingErrorDeg = null,
                            ),
                        fontSize = tokens.qualityFontSize,
                        color =
                            compassQualityLabelColor(
                                hasQualitySample = hasQualitySample,
                                quality = meterQuality,
                            ),
                    )
                }

                if (phase == CalibrationPhase.MEASURING || phase == CalibrationPhase.HOLD_STILL) {
                    Text(
                        text =
                            when (phase) {
                                CalibrationPhase.MEASURING -> {
                                    if (countdownStarted) {
                                        "Move: ${(remainingMs / 1000L).coerceAtLeast(0L)}s"
                                    } else {
                                        "Checking sensor..."
                                    }
                                }

                                CalibrationPhase.HOLD_STILL -> {
                                    "Hold still: ${(holdStillRemainingMs / 1000L).coerceAtLeast(0L)}s"
                                }

                                else -> ""
                            },
                        fontSize = tokens.timerFontSize,
                        color = Color.White,
                    )
                }

                when (phase) {
                    CalibrationPhase.MEASURING -> {
                        SingleActionButton(
                            label = "Cancel",
                            onClick = {
                                logCompassCalibrationTelemetry("button_cancel phase=${phase.name}")
                                finish(false)
                            },
                            widthFraction = tokens.skipButtonWidthFraction,
                            textFontSize = tokens.actionTextFontSize,
                        )
                    }

                    CalibrationPhase.HOLD_STILL -> {
                        SingleActionButton(
                            label = "Cancel",
                            onClick = {
                                logCompassCalibrationTelemetry("button_cancel phase=${phase.name}")
                                finish(false)
                            },
                            widthFraction = tokens.skipButtonWidthFraction,
                            textFontSize = tokens.actionTextFontSize,
                        )
                    }

                    CalibrationPhase.RESULT -> {
                        SingleActionButton(
                            label =
                                recalibrationResultButtonLabel(
                                    succeeded = currentResultSuccess(),
                                ),
                            onClick = {
                                val succeeded = currentResultSuccess()
                                logCompassCalibrationTelemetry(
                                    "button_result provider=${activeProviderType.name} " +
                                        "succeeded=$succeeded " +
                                        "qualityImproved=$qualityImproved " +
                                        "displayed=${displayedQuality.name} " +
                                        "result=${resultQuality?.name ?: "UNKNOWN"}",
                                )
                                if (succeeded) {
                                    finish(true)
                                } else {
                                    restartCalibrationFlow()
                                }
                            },
                            widthFraction = tokens.singleActionWidthFraction,
                            textFontSize = tokens.actionTextFontSize,
                        )
                    }

                    CalibrationPhase.UNSUPPORTED -> {
                        SingleActionButton(
                            label = "Close",
                            onClick = {
                                logCompassCalibrationTelemetry("button_close phase=${phase.name}")
                                finish(false)
                            },
                            widthFraction = tokens.singleActionWidthFraction,
                            textFontSize = tokens.actionTextFontSize,
                        )
                    }
                }
            }
            WearVerticalScrollIndicator(
                scrollState = scrollState,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp),
            )
        }
    }
}

internal data class CompassRecalibrationBodyState(
    val phase: CalibrationPhase,
    val providerType: CompassProviderType,
    val hasAnyHeadingSource: Boolean,
    val initialQuality: CompassMarkerQuality,
    val resultQuality: CompassMarkerQuality,
    val qualityImproved: Boolean,
)

internal fun compassRecalibrationBodyText(state: CompassRecalibrationBodyState): String =
    when (state.phase) {
        CalibrationPhase.MEASURING -> {
            "Move in a figure-8 for ${CALIBRATION_TIMEOUT_MS / 1000L} seconds. " +
                "Then hold still for ${HOLD_STILL_TIMEOUT_MS / 1000L} seconds."
        }

        CalibrationPhase.HOLD_STILL -> "Hold still while we check the compass."

        CalibrationPhase.RESULT -> {
            val before = compassQualityShortLabel(state.initialQuality)
            val after = compassQualityShortLabel(state.resultQuality)
            val isUsableQuality =
                compassQualityRank(state.resultQuality) >=
                    compassQualityRank(CompassMarkerQuality.MEDIUM)
            if (state.qualityImproved) {
                "Compass recalibrated.\nQuality improved: $before -> $after.\nTap Done."
            } else if (isUsableQuality) {
                "Compass recalibrated.\nQuality: $after.\nTap Done."
            } else {
                "Compass is still $after.\nMove away from metal, then tap Try Again."
            }
        }

        CalibrationPhase.UNSUPPORTED -> {
            if (state.providerType != CompassProviderType.SENSOR_MANAGER) {
                "Recalibration needs Orientation provider set to Custom sensors."
            } else if (state.hasAnyHeadingSource) {
                "Selected compass source unavailable."
            } else {
                "No compass source available."
            }
        }
    }

internal fun recalibrationResultButtonLabel(succeeded: Boolean): String = if (succeeded) "Done" else "Try Again"

internal fun didCompleteCompassRecalibration(
    providerType: CompassProviderType,
    headingSourceResolved: Boolean,
    qualityImproved: Boolean,
    resultQuality: CompassMarkerQuality,
): Boolean =
    when (providerType) {
        CompassProviderType.GOOGLE_FUSED -> false
        CompassProviderType.SENSOR_MANAGER ->
            headingSourceResolved &&
                (
                    qualityImproved ||
                        compassQualityRank(resultQuality) >=
                        compassQualityRank(CompassMarkerQuality.MEDIUM)
                )
    }

private const val CALIBRATION_DIALOG_TELEMETRY_TAG = "CalibrationTelemetry"
private const val MOVE_PHASE_COMPLETE_HAPTIC_MS = 70L

private fun logCompassCalibrationTelemetry(message: String) {
    if (!DebugTelemetry.isEnabled()) return
    DebugTelemetry.log(CALIBRATION_DIALOG_TELEMETRY_TAG, message)
}

private fun summarizeHoldStillQuality(
    samples: List<CompassMarkerQuality>,
    fallback: CompassMarkerQuality,
): CompassMarkerQuality {
    if (samples.isEmpty()) return fallback
    val sortedRanks = samples.map(::compassQualityRank).sorted()
    val medianRank = sortedRanks[sortedRanks.size / 2]
    return compassQualityFromRank(medianRank)
}

private fun compassQualityRank(quality: CompassMarkerQuality): Int =
    when (quality) {
        CompassMarkerQuality.NEUTRAL -> 0
        CompassMarkerQuality.UNRELIABLE -> 0
        CompassMarkerQuality.LOW -> 1
        CompassMarkerQuality.MEDIUM -> 2
        CompassMarkerQuality.GOOD -> 3
    }

private fun compassQualityFromRank(rank: Int): CompassMarkerQuality =
    when (rank.coerceIn(0, 3)) {
        3 -> CompassMarkerQuality.GOOD
        2 -> CompassMarkerQuality.MEDIUM
        1 -> CompassMarkerQuality.LOW
        else -> CompassMarkerQuality.UNRELIABLE
    }

private fun compassQualityShortLabel(quality: CompassMarkerQuality): String =
    when (quality) {
        CompassMarkerQuality.NEUTRAL -> "Neutral"
        CompassMarkerQuality.GOOD -> "Good"
        CompassMarkerQuality.MEDIUM -> "Caution"
        CompassMarkerQuality.LOW -> "Poor"
        CompassMarkerQuality.UNRELIABLE -> "Unreliable"
    }

@Composable
private fun QualityMeter(
    hasQualitySample: Boolean,
    quality: CompassMarkerQuality,
    widthFraction: Float,
    barSpacing: Dp,
    barWidth: Dp,
    barHeight: Dp,
    barCornerRadius: Dp,
) {
    val active =
        if (!hasQualitySample) {
            0
        } else {
            when (quality) {
                CompassMarkerQuality.NEUTRAL -> 0
                CompassMarkerQuality.UNRELIABLE -> 1
                CompassMarkerQuality.LOW -> 2
                CompassMarkerQuality.MEDIUM -> 3
                CompassMarkerQuality.GOOD -> 4
            }
        }
    Row(
        modifier = Modifier.fillMaxWidth(widthFraction),
        horizontalArrangement = Arrangement.spacedBy(barSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { idx ->
            val on = idx < active
            Box(
                modifier =
                    Modifier
                        .size(width = barWidth, height = barHeight)
                        .background(
                            color =
                                if (on) {
                                    compassQualityLabelColor(
                                        hasQualitySample = hasQualitySample,
                                        quality = quality,
                                    )
                                } else {
                                    Color.White.copy(alpha = 0.18f)
                                },
                            shape = RoundedCornerShape(barCornerRadius),
                        ),
            )
        }
    }
}

@Composable
private fun SingleActionButton(
    label: String,
    onClick: () -> Unit,
    widthFraction: Float,
    textFontSize: TextUnit,
) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(widthFraction)) {
        Text(text = label, fontSize = textFontSize)
    }
}

private fun compassQualityLabel(
    hasQualitySample: Boolean,
    quality: CompassMarkerQuality,
    providerType: CompassProviderType,
    headingErrorDeg: Float?,
): String {
    if (!hasQualitySample) return "Compass quality: Checking..."
    val label =
        when (quality) {
            CompassMarkerQuality.NEUTRAL -> "Compass quality: Neutral"
            CompassMarkerQuality.GOOD -> "Compass quality: Good"
            CompassMarkerQuality.MEDIUM -> "Compass quality: Caution"
            CompassMarkerQuality.LOW -> "Compass quality: Poor"
            CompassMarkerQuality.UNRELIABLE -> "Compass quality: Unreliable"
        }
    if (providerType != CompassProviderType.GOOGLE_FUSED) return label
    val safeHeadingErrorDeg = headingErrorDeg?.takeIf { it.isFinite() && it >= 0f } ?: return label
    return "$label (${safeHeadingErrorDeg.roundToInt()}°)"
}

private fun compassQualityLabelColor(
    hasQualitySample: Boolean,
    quality: CompassMarkerQuality,
): Color {
    if (!hasQualitySample) return Color(0xFFB0BEC5)
    return Color(coneColorArgbForQuality(quality))
}
