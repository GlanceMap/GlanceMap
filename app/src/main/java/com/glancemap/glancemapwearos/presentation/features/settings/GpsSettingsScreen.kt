@file:Suppress("CyclomaticComplexMethod", "FunctionName", "FunctionNaming", "LongMethod", "LongParameterList")

package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.nextGpsTimingMode
import com.glancemap.glancemapwearos.presentation.ui.WearHelpDialog
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import kotlin.math.abs

@Composable
fun GpsSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()

    val activityProfile by viewModel.activityProfile.collectAsState()
    val isWatchGpsOnly by viewModel.watchGpsOnly.collectAsState()
    val gpsUsageProfile by viewModel.gpsUsageProfile.collectAsState()
    val recordingSampleIntervalSeconds by viewModel.recordingSampleIntervalSeconds.collectAsState()
    val recordingScreenOffSampleIntervalSeconds by viewModel.recordingScreenOffSampleIntervalSeconds.collectAsState()
    val turnByTurnGpsIntervalSeconds by viewModel.turnByTurnGpsIntervalSeconds.collectAsState()
    val turnByTurnScreenOffGpsIntervalSeconds by viewModel.turnByTurnScreenOffGpsIntervalSeconds.collectAsState()
    val gpsDebugTelemetry by viewModel.gpsDebugTelemetry.collectAsState()
    val diagnosticsCaptureMode by viewModel.diagnosticsCaptureMode.collectAsState()
    val gpsPassiveLocationExperiment by viewModel.gpsPassiveLocationExperiment.collectAsState()
    var showInfoDialog by remember { mutableStateOf(false) }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            SettingsInfoButton(
                contentDescription = "GPS settings info",
                onClick = { showInfoDialog = true },
            )
        }
        item {
            GeneralSettingsShortcutChip(
                onClick = onOpenGeneralSettings,
                applyTopPadding = false,
            )
        }

        item {
            SettingsToggleChip(
                checked = isWatchGpsOnly,
                onCheckedChanged = { viewModel.setWatchGpsOnly(it) },
                label = stringResource(R.string.gps_source),
                secondaryLabel =
                    when {
                        isWatchGpsOnly -> stringResource(R.string.gps_source_watch_only)
                        else -> stringResource(R.string.gps_source_auto)
                    },
            )
        }

        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.gps_profile),
                selectedValue = gpsUsageProfile,
                options = GPS_USAGE_PROFILE_OPTIONS.map { it to gpsUsageProfileLabel(it, activityProfile) },
                secondaryLabel = gpsUsageProfileLabel(gpsUsageProfile, activityProfile),
                onSelect = viewModel::setGpsUsageProfile,
            )
        }
        item {
            GpsIntervalSummary(
                recordingScreenOn = gpsShortLabel(recordingSampleIntervalSeconds),
                recordingScreenOff = gpsCompactScreenOffIntervalLabel(recordingScreenOffSampleIntervalSeconds),
                turnByTurnScreenOn = gpsShortLabel(turnByTurnGpsIntervalSeconds),
                turnByTurnScreenOff = gpsCompactScreenOffIntervalLabel(turnByTurnScreenOffGpsIntervalSeconds),
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.gps_advanced),
                secondaryLabel = stringResource(R.string.gps_advanced_summary),
                onClick = onOpenAdvancedSettings,
            )
        }

        if (isFullDiagnosticsCapture(gpsDebugTelemetry, diagnosticsCaptureMode)) {
            item {
                SettingsToggleChip(
                    checked = gpsPassiveLocationExperiment,
                    onCheckedChanged = { viewModel.setGpsPassiveLocationExperiment(it) },
                    label = stringResource(R.string.gps_use_other_apps),
                    secondaryLabel =
                        if (gpsPassiveLocationExperiment) {
                            stringResource(R.string.gps_other_apps_on_during_capture)
                        } else {
                            stringResource(R.string.gps_other_apps_off_during_capture)
                        },
                )
            }
        }
    }

    GpsSettingsInfoDialog(
        visible = showInfoDialog,
        activityProfile = activityProfile,
        gpsUsageProfile = gpsUsageProfile,
        turnByTurnScreenOffGpsIntervalSeconds = turnByTurnScreenOffGpsIntervalSeconds,
        onDismiss = { showInfoDialog = false },
    )
}

@Composable
private fun GpsSettingsInfoDialog(
    visible: Boolean,
    activityProfile: String,
    gpsUsageProfile: String,
    turnByTurnScreenOffGpsIntervalSeconds: Int,
    onDismiss: () -> Unit,
) {
    val adaptiveCadence =
        if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
            "5 → 3 → 1 s"
        } else {
            "10 → 5 → 3 s"
        }
    val turnByTurnLine =
        if (turnByTurnScreenOffGpsIntervalSeconds == SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS) {
            "Adaptive TBT: $adaptiveCadence near turns."
        } else {
            "TBT off: ${gpsCompactScreenOffIntervalLabel(turnByTurnScreenOffGpsIntervalSeconds)}."
        }

    WearHelpDialog(
        visible = visible,
        title = "GPS",
        lines =
            listOf(
                gpsUsageProfileName(gpsUsageProfile, activityProfile),
                "Normal map: 3 s awake; GPS pauses in ambient/screen-off (same for hike/bike).",
                turnByTurnLine,
                "Shorter timing means more battery use.",
                "REC + TBT use the shorter timing.",
            ),
        onDismiss = onDismiss,
    )
}

@Composable
fun GpsAdvancedSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGpsSettings: () -> Unit,
) {
    val recordingSampleIntervalSeconds by viewModel.recordingSampleIntervalSeconds.collectAsState()
    val recordingScreenOnFixedGpsIntervalSeconds by
        viewModel.recordingScreenOnFixedGpsIntervalSeconds.collectAsState()
    val recordingScreenOffSampleIntervalSeconds by viewModel.recordingScreenOffSampleIntervalSeconds.collectAsState()
    val recordingScreenOffFixedGpsIntervalSeconds by
        viewModel.recordingScreenOffFixedGpsIntervalSeconds.collectAsState()
    val turnByTurnGpsIntervalSeconds by viewModel.turnByTurnGpsIntervalSeconds.collectAsState()
    val turnByTurnScreenOnFixedGpsIntervalSeconds by
        viewModel.turnByTurnScreenOnFixedGpsIntervalSeconds.collectAsState()
    val turnByTurnScreenOffGpsIntervalSeconds by viewModel.turnByTurnScreenOffGpsIntervalSeconds.collectAsState()
    val turnByTurnScreenOffFixedGpsIntervalSeconds by
        viewModel.turnByTurnScreenOffFixedGpsIntervalSeconds.collectAsState()
    val recordingScreenOnDisabled =
        recordingSampleIntervalSeconds == SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
    val recordingScreenOffDisabled =
        when (recordingScreenOffSampleIntervalSeconds) {
            SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> recordingScreenOnDisabled
            SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> true
            else -> false
        }
    val turnByTurnScreenOnDisabled =
        turnByTurnGpsIntervalSeconds == SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
    val turnByTurnScreenOffDisabled =
        when (turnByTurnScreenOffGpsIntervalSeconds) {
            SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> turnByTurnScreenOnDisabled
            SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> true
            else -> false
        }

    WearSettingsListScreen(horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GpsSettingsShortcutChip(onClick = onOpenGpsSettings)
        }
        item { GpsSectionTitle(text = "REC") }
        item {
            GpsTimingModePickerRow(
                label = stringResource(R.string.screen_on),
                selectedValue = recordingSampleIntervalSeconds,
                fixedSeconds = recordingScreenOnFixedGpsIntervalSeconds,
                modes = SCREEN_ON_GPS_TIMING_MODES,
                secondaryLabel = gpsIntervalLabel(recordingSampleIntervalSeconds),
                dialogTitle = stringResource(R.string.gps_recording_screen_on),
                onSelect = viewModel::setRecordingSampleIntervalSeconds,
            )
        }
        item {
            GpsTimingModePickerRow(
                label = stringResource(R.string.screen_off),
                selectedValue = recordingScreenOffSampleIntervalSeconds,
                fixedSeconds = recordingScreenOffFixedGpsIntervalSeconds,
                modes = REC_SCREEN_OFF_GPS_TIMING_MODES,
                secondaryLabel =
                    gpsScreenOffIntervalLabel(
                        seconds = recordingScreenOffSampleIntervalSeconds,
                        screenOnSeconds = recordingSampleIntervalSeconds,
                    ),
                dialogTitle = stringResource(R.string.gps_recording_screen_off),
                screenOnSeconds = recordingSampleIntervalSeconds,
                offWarningText = stringResource(R.string.gps_recording_off_warning),
                onSelect = viewModel::setRecordingScreenOffSampleIntervalSeconds,
            )
        }
        if (recordingScreenOffDisabled) {
            item { GpsWarningText(text = stringResource(R.string.gps_recording_screen_off_disabled_warning)) }
        }

        item { GpsSectionTitle(text = "TBT") }
        item {
            GpsTimingModePickerRow(
                label = stringResource(R.string.screen_on),
                selectedValue = turnByTurnGpsIntervalSeconds,
                fixedSeconds = turnByTurnScreenOnFixedGpsIntervalSeconds,
                modes = SCREEN_ON_GPS_TIMING_MODES,
                secondaryLabel = gpsIntervalLabel(turnByTurnGpsIntervalSeconds),
                dialogTitle = stringResource(R.string.gps_guidance_screen_on),
                offWarningText = stringResource(R.string.gps_guidance_screen_on_off_warning),
                onSelect = viewModel::setTurnByTurnGpsIntervalSeconds,
            )
        }
        item {
            GpsTimingModePickerRow(
                label = stringResource(R.string.screen_off),
                selectedValue = turnByTurnScreenOffGpsIntervalSeconds,
                fixedSeconds = turnByTurnScreenOffFixedGpsIntervalSeconds,
                modes = TBT_SCREEN_OFF_GPS_TIMING_MODES,
                secondaryLabel =
                    gpsScreenOffIntervalLabel(
                        seconds = turnByTurnScreenOffGpsIntervalSeconds,
                        screenOnSeconds = turnByTurnGpsIntervalSeconds,
                    ),
                dialogTitle = stringResource(R.string.gps_guidance_screen_off),
                screenOnSeconds = turnByTurnGpsIntervalSeconds,
                onSelect = viewModel::setTurnByTurnScreenOffGpsIntervalSeconds,
            )
        }
        if (turnByTurnScreenOffDisabled) {
            item { GpsWarningText(text = stringResource(R.string.gps_guidance_screen_off_disabled_warning)) }
        }
        if (turnByTurnScreenOnDisabled) {
            item { GpsWarningText(text = stringResource(R.string.gps_guidance_screen_on_disabled_warning)) }
        }
    }
}

private fun isFullDiagnosticsCapture(
    captureActive: Boolean,
    captureMode: String,
): Boolean = captureActive && captureMode == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_FULL

@Composable
private fun GpsTimingModePickerRow(
    label: String,
    selectedValue: Int,
    fixedSeconds: Int,
    modes: List<Int>,
    secondaryLabel: String,
    dialogTitle: String,
    onSelect: (Int) -> Unit,
    screenOnSeconds: Int? = null,
    offWarningText: String? = null,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    var pendingSelectedValue by remember { mutableStateOf<Int?>(null) }
    val displayedSelectedValue = pendingSelectedValue ?: selectedValue

    LaunchedEffect(pickerVisible) {
        if (pickerVisible) pendingSelectedValue = null
    }
    LaunchedEffect(selectedValue) {
        if (pendingSelectedValue == selectedValue) pendingSelectedValue = null
    }

    SettingsPickerChip(
        label = label,
        secondaryLabel = secondaryLabel,
        onClick = { pickerVisible = true },
    )
    GpsTimingModeDialog(
        visible = pickerVisible,
        title = dialogTitle,
        selectedValue = displayedSelectedValue,
        fixedSeconds = fixedSeconds,
        modes = modes,
        screenOnSeconds = screenOnSeconds,
        offWarningText = offWarningText,
        onDismiss = { pickerVisible = false },
        onSelect = { seconds ->
            pendingSelectedValue = seconds
            onSelect(seconds)
        },
    )
}

@Composable
private fun GpsTimingModeDialog(
    visible: Boolean,
    title: String,
    selectedValue: Int,
    fixedSeconds: Int,
    modes: List<Int>,
    screenOnSeconds: Int?,
    offWarningText: String?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    if (!visible) return

    val adaptive = rememberWearAdaptiveSpec()
    val focusRequester = remember { FocusRequester() }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    val compactHighFontLayout = adaptive.fontScale > 1.05f
    val fixedSecondsIndex = GPS_TIMING_SECONDS_OPTIONS.indexOf(fixedSeconds).coerceAtLeast(0)
    val fixedSecondsSelected = selectedValue > 0
    val canDecrease = fixedSecondsIndex > 0
    val canIncrease = fixedSecondsIndex < GPS_TIMING_SECONDS_OPTIONS.lastIndex

    fun selectFixedSeconds(index: Int) {
        val safeIndex = index.coerceIn(0, GPS_TIMING_SECONDS_OPTIONS.lastIndex)
        onSelect(GPS_TIMING_SECONDS_OPTIONS[safeIndex])
    }

    fun selectBySecondsDelta(deltaSeconds: Int) {
        val targetSeconds = fixedSeconds + deltaSeconds
        val targetOption =
            if (deltaSeconds > 0) {
                GPS_TIMING_SECONDS_OPTIONS.firstOrNull { it >= targetSeconds }
                    ?: GPS_TIMING_SECONDS_OPTIONS.last()
            } else {
                GPS_TIMING_SECONDS_OPTIONS.lastOrNull { it <= targetSeconds }
                    ?: GPS_TIMING_SECONDS_OPTIONS.first()
            }
        onSelect(targetOption)
    }

    fun selectNextMode() {
        onSelect(
            nextGpsTimingMode(
                selectedSeconds = selectedValue,
                fixedSeconds = fixedSeconds,
                modes = modes,
            ),
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f)),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = adaptive.dialogHorizontalPadding,
                            top = adaptive.dialogVerticalPadding + 12.dp,
                            end = adaptive.dialogHorizontalPadding,
                            bottom = adaptive.dialogVerticalPadding + 22.dp,
                        ).onPreRotaryScrollEvent { event ->
                            if (fixedSecondsSelected) {
                                rotaryAccumulator += event.verticalScrollPixels
                                if (abs(rotaryAccumulator) >= GPS_STEPPER_ROTARY_STEP_PX) {
                                    selectFixedSeconds(
                                        fixedSecondsIndex +
                                            if (rotaryAccumulator > 0f) {
                                                1
                                            } else {
                                                -1
                                            },
                                    )
                                    rotaryAccumulator = 0f
                                }
                            }
                            true
                        }.focusRequester(focusRequester)
                        .focusable(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GpsPickerDismissHandle(onDismiss = onDismiss)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                )
                val selectedOption =
                    gpsTimingOption(
                        seconds = selectedValue,
                        screenOnSeconds = screenOnSeconds,
                    )
                GpsTimingStepperValue(
                    label = if (fixedSecondsSelected) gpsShortLabel(fixedSeconds) else selectedOption.label,
                    detail = if (fixedSecondsSelected) null else selectedOption.detail,
                    compactHighFontLayout = compactHighFontLayout,
                    canDecrease = canDecrease,
                    canIncrease = canIncrease,
                    showStepperControls = fixedSecondsSelected,
                    onDecrease = { selectFixedSeconds(fixedSecondsIndex - 1) },
                    onIncrease = { selectFixedSeconds(fixedSecondsIndex + 1) },
                    onLongDecrease = { selectBySecondsDelta(-GPS_STEPPER_LONG_PRESS_SECONDS) },
                    onLongIncrease = { selectBySecondsDelta(GPS_STEPPER_LONG_PRESS_SECONDS) },
                    onValueClick = ::selectNextMode,
                )
                if (fixedSecondsSelected) {
                    Text(
                        text = stringResource(R.string.gps_timing_fixed_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (
                    offWarningText != null &&
                    selectedValue == SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
                ) {
                    Text(
                        text = offWarningText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GpsPickerDismissHandle(onDismiss: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { totalDrag = 0f },
                        onDragCancel = { totalDrag = 0f },
                    ) { _, dragAmount ->
                        totalDrag += dragAmount
                        if (totalDrag > GPS_PICKER_DRAG_DISMISS_PX) {
                            onDismiss()
                            totalDrag = 0f
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .width(26.dp)
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.42f), RoundedCornerShape(50)),
        )
    }
}

@Composable
private fun GpsTimingStepperValue(
    label: String,
    detail: String?,
    compactHighFontLayout: Boolean,
    canDecrease: Boolean,
    canIncrease: Boolean,
    showStepperControls: Boolean = true,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onLongDecrease: () -> Unit,
    onLongIncrease: () -> Unit,
    onValueClick: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compactHighFontLayout) 5.dp else 8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val stepperButtonSize = if (compactHighFontLayout) 48.dp else 64.dp
        val stepperSpacing = if (compactHighFontLayout) 4.dp else 12.dp
        Row(
            horizontalArrangement =
                Arrangement.spacedBy(
                    stepperSpacing,
                    Alignment.CenterHorizontally,
                ),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showStepperControls) {
                GpsStepperButton(
                    enabled = canDecrease,
                    size = stepperButtonSize,
                    onClick = onDecrease,
                    onLongClick = onLongDecrease,
                    icon = Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.gps_decrease_timing),
                )
            }
            Box(
                modifier =
                    (if (showStepperControls) Modifier.weight(1f) else Modifier.fillMaxWidth())
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                            shape = RoundedCornerShape(28.dp),
                        ).padding(
                            horizontal = if (compactHighFontLayout) 6.dp else 10.dp,
                            vertical =
                                when {
                                    compactHighFontLayout -> if (detail == null) 10.dp else 8.dp
                                    detail == null -> 14.dp
                                    else -> 10.dp
                                },
                        ).then(
                            if (onValueClick == null) {
                                Modifier
                            } else {
                                Modifier.clickable(onClick = onValueClick)
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        style =
                            if (compactHighFontLayout) {
                                MaterialTheme.typography.titleLarge
                            } else if (label.length <= 3) {
                                MaterialTheme.typography.displaySmall
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (showStepperControls) {
                GpsStepperButton(
                    enabled = canIncrease,
                    size = stepperButtonSize,
                    onClick = onIncrease,
                    onLongClick = onLongIncrease,
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.gps_increase_timing),
                )
            }
        }
        if (showStepperControls) {
            Text(
                text =
                    stringResource(
                        if (compactHighFontLayout) {
                            R.string.gps_stepper_hint_compact
                        } else {
                            R.string.gps_stepper_hint
                        },
                    ),
                style =
                    if (compactHighFontLayout) {
                        MaterialTheme.typography.labelSmall
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GpsStepperButton(
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    val containerColor =
        if (enabled) {
            Color.White.copy(alpha = 0.22f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.96f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
        }
    Box(
        modifier =
            Modifier
                .width(size)
                .height(size)
                .background(containerColor, CircleShape)
                .pointerInput(enabled, onClick, onLongClick) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongClick() },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
        )
    }
}

@Composable
private fun GpsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
private fun GpsWarningText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Suppress("FunctionName")
@Composable
private fun GpsIntervalSummary(
    recordingScreenOn: String,
    recordingScreenOff: String,
    turnByTurnScreenOn: String,
    turnByTurnScreenOff: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(16.dp),
                ).padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        GpsIntervalSummaryRow(
            screenLabel = "Screen",
            recordingLabel = "REC",
            turnByTurnLabel = "TBT",
            header = true,
        )
        GpsIntervalSummaryRow(
            screenLabel = "On",
            recordingLabel = recordingScreenOn,
            turnByTurnLabel = turnByTurnScreenOn,
        )
        GpsIntervalSummaryRow(
            screenLabel = "Off",
            recordingLabel = recordingScreenOff,
            turnByTurnLabel = turnByTurnScreenOff,
        )
    }
}

@Composable
private fun GpsIntervalSummaryRow(
    screenLabel: String,
    recordingLabel: String,
    turnByTurnLabel: String,
    header: Boolean = false,
) {
    val textStyle =
        if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    val textColor =
        if (header) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Row(modifier = Modifier.fillMaxWidth()) {
        GpsIntervalSummaryCell(
            text = screenLabel,
            style = textStyle,
            color = textColor,
            modifier = Modifier.weight(1.15f),
        )
        GpsIntervalSummaryCell(
            text = recordingLabel,
            style = textStyle,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        GpsIntervalSummaryCell(
            text = turnByTurnLabel,
            style = textStyle,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GpsIntervalSummaryCell(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

private const val GPS_PICKER_DRAG_DISMISS_PX = 55f
private const val GPS_STEPPER_ROTARY_STEP_PX = 48f
private const val GPS_STEPPER_LONG_PRESS_SECONDS = 5

private val GPS_TIMING_SECONDS_OPTIONS = (1..60).toList() + listOf(90, 120)

private val SCREEN_ON_GPS_TIMING_MODES =
    listOf(SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS)

private val REC_SCREEN_OFF_GPS_TIMING_MODES =
    listOf(
        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS,
        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS,
    )

private val TBT_SCREEN_OFF_GPS_TIMING_MODES =
    listOf(
        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS,
        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS,
        SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
    )

private val GPS_USAGE_PROFILE_OPTIONS =
    listOf(
        SettingsRepository.GPS_USAGE_PROFILE_BALANCED,
        SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE,
        SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY,
        SettingsRepository.GPS_USAGE_PROFILE_CUSTOM,
    )

private fun gpsUsageProfileLabel(
    profile: String,
    activityProfile: String,
): String =
    when (profile) {
        SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE -> "Best trace · more battery"
        SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY -> "Long battery · less detail"
        SettingsRepository.GPS_USAGE_PROFILE_CUSTOM -> "Custom ${gpsActivityLabel(activityProfile)} · keep timings"
        else -> "Balanced · recommended"
    }

private fun gpsUsageProfileName(
    profile: String,
    activityProfile: String,
): String =
    when (profile) {
        SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE -> "Best trace"
        SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY -> "Long battery"
        SettingsRepository.GPS_USAGE_PROFILE_CUSTOM -> "Custom ${gpsActivityLabel(activityProfile)}"
        else -> "Balanced"
    }

private fun gpsActivityLabel(activityProfile: String) =
    when (activityProfile) {
        SettingsRepository.ACTIVITY_PROFILE_BIKE -> "Bike"
        else -> "Hike"
    }

@Composable
private fun gpsScreenOffIntervalLabel(
    seconds: Int,
    screenOnSeconds: Int? = null,
): String =
    when (seconds) {
        SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS ->
            stringResource(R.string.gps_adaptive)

        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS ->
            screenOnSeconds?.let {
                stringResource(R.string.gps_same_with_value, gpsIntervalLabel(it))
            } ?: stringResource(R.string.gps_same_as_screen_on)

        else -> gpsIntervalLabel(seconds)
    }

@Composable
private fun gpsCompactScreenOffIntervalLabel(seconds: Int): String =
    when (seconds) {
        SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS ->
            stringResource(R.string.gps_adaptive)

        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> stringResource(R.string.gps_same)
        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> stringResource(R.string.state_off)
        else -> gpsShortLabel(seconds)
    }

@Composable
private fun gpsIntervalLabel(seconds: Int): String =
    when {
        seconds == SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS ->
            stringResource(R.string.state_off)
        else -> pluralStringResource(R.plurals.gps_interval_seconds, seconds, seconds)
    }

private data class GpsTimingOption(
    val label: String,
    val detail: String? = null,
)

@Composable
private fun gpsTimingOption(
    seconds: Int,
    screenOnSeconds: Int?,
): GpsTimingOption =
    when (seconds) {
        SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS ->
            GpsTimingOption(
                label = stringResource(R.string.gps_adaptive),
                detail = stringResource(R.string.gps_adaptive_tbt_detail),
            )

        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS ->
            GpsTimingOption(
                label = stringResource(R.string.gps_same),
                detail =
                    screenOnSeconds?.let {
                        stringResource(R.string.gps_screen_on_with_value, gpsShortLabel(it))
                    } ?: stringResource(R.string.screen_on),
            )

        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS ->
            GpsTimingOption(label = stringResource(R.string.state_off))

        else ->
            GpsTimingOption(
                label = gpsShortLabel(seconds),
                detail = null,
            )
    }

@Composable
private fun gpsShortLabel(seconds: Int) = stringResource(R.string.gps_short_interval_seconds, seconds.coerceAtLeast(1))
