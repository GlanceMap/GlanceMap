@file:Suppress("FunctionName", "FunctionNaming", "LongMethod")

package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.material.ToggleChip
import com.google.android.horologist.compose.material.ToggleChipToggleControl
import kotlin.math.abs

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun GpsSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()

    val isWatchGpsOnly by viewModel.watchGpsOnly.collectAsState()
    val gpsUsageProfile by viewModel.gpsUsageProfile.collectAsState()
    val recordingSampleIntervalSeconds by viewModel.recordingSampleIntervalSeconds.collectAsState()
    val recordingScreenOffSampleIntervalSeconds by viewModel.recordingScreenOffSampleIntervalSeconds.collectAsState()
    val turnByTurnGpsIntervalSeconds by viewModel.turnByTurnGpsIntervalSeconds.collectAsState()
    val turnByTurnScreenOffGpsIntervalSeconds by viewModel.turnByTurnScreenOffGpsIntervalSeconds.collectAsState()
    val gpsDebugTelemetry by viewModel.gpsDebugTelemetry.collectAsState()
    val diagnosticsCaptureMode by viewModel.diagnosticsCaptureMode.collectAsState()
    val gpsPassiveLocationExperiment by viewModel.gpsPassiveLocationExperiment.collectAsState()

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }

        item {
            ToggleChip(
                checked = isWatchGpsOnly,
                onCheckedChanged = { viewModel.setWatchGpsOnly(it) },
                label = stringResource(R.string.gps_source),
                secondaryLabel =
                    when {
                        isWatchGpsOnly -> stringResource(R.string.gps_source_watch_only)
                        else -> stringResource(R.string.gps_source_auto)
                    },
                toggleControl = ToggleChipToggleControl.Switch,
            )
        }

        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.gps_profile),
                selectedValue = gpsUsageProfile,
                options = GPS_USAGE_PROFILE_OPTIONS.map { it to gpsUsageProfileLabel(it) },
                secondaryLabel = gpsUsageProfileLabel(gpsUsageProfile),
                onSelect = viewModel::setGpsUsageProfile,
            )
        }
        item {
            GpsIntervalSummary(
                primaryText =
                    stringResource(
                        R.string.gps_profile_rec_summary,
                        gpsIntervalLabel(recordingSampleIntervalSeconds),
                        gpsScreenOffIntervalLabel(
                            seconds = recordingScreenOffSampleIntervalSeconds,
                            screenOnSeconds = recordingSampleIntervalSeconds,
                        ),
                    ),
                secondaryText =
                    stringResource(
                        R.string.gps_profile_tbt_summary,
                        gpsIntervalLabel(turnByTurnGpsIntervalSeconds),
                        gpsScreenOffIntervalLabel(
                            seconds = turnByTurnScreenOffGpsIntervalSeconds,
                            screenOnSeconds = turnByTurnGpsIntervalSeconds,
                        ),
                    ),
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
                ToggleChip(
                    checked = gpsPassiveLocationExperiment,
                    onCheckedChanged = { viewModel.setGpsPassiveLocationExperiment(it) },
                    label = stringResource(R.string.gps_use_other_apps),
                    secondaryLabel =
                        if (gpsPassiveLocationExperiment) {
                            stringResource(R.string.gps_other_apps_on_during_capture)
                        } else {
                            stringResource(R.string.gps_other_apps_off_during_capture)
                        },
                    toggleControl = ToggleChipToggleControl.Switch,
                )
            }
        }
    }
}

@Composable
fun GpsAdvancedSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGpsSettings: () -> Unit,
) {
    val recordingSampleIntervalSeconds by viewModel.recordingSampleIntervalSeconds.collectAsState()
    val recordingScreenOffSampleIntervalSeconds by viewModel.recordingScreenOffSampleIntervalSeconds.collectAsState()
    val turnByTurnGpsIntervalSeconds by viewModel.turnByTurnGpsIntervalSeconds.collectAsState()
    val turnByTurnScreenOffGpsIntervalSeconds by viewModel.turnByTurnScreenOffGpsIntervalSeconds.collectAsState()
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
            SettingsSectionChip(
                label = stringResource(R.string.gps_settings),
                secondaryLabel = stringResource(R.string.gps_profiles_and_source),
                onClick = onOpenGpsSettings,
            )
        }
        item { GpsSectionTitle(text = "REC") }
        item {
            GpsTimingPickerRow(
                label = stringResource(R.string.screen_on),
                selectedValue = recordingSampleIntervalSeconds,
                options = REC_SCREEN_ON_OPTIONS_SECONDS,
                secondaryLabel = gpsIntervalLabel(recordingSampleIntervalSeconds),
                dialogTitle = stringResource(R.string.gps_recording_screen_on),
                onSelect = viewModel::setRecordingSampleIntervalSeconds,
            )
        }
        item {
            GpsTimingPickerRow(
                label = stringResource(R.string.screen_off),
                selectedValue = recordingScreenOffSampleIntervalSeconds,
                options = SCREEN_OFF_OPTIONS_SECONDS,
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
            GpsTimingPickerRow(
                label = stringResource(R.string.screen_on),
                selectedValue = turnByTurnGpsIntervalSeconds,
                options = TBT_SCREEN_ON_OPTIONS_SECONDS,
                secondaryLabel = gpsIntervalLabel(turnByTurnGpsIntervalSeconds),
                dialogTitle = stringResource(R.string.gps_guidance_screen_on),
                offWarningText = stringResource(R.string.gps_guidance_screen_on_off_warning),
                onSelect = viewModel::setTurnByTurnGpsIntervalSeconds,
            )
        }
        item {
            GpsTimingPickerRow(
                label = stringResource(R.string.screen_off),
                selectedValue = turnByTurnScreenOffGpsIntervalSeconds,
                options = TBT_SCREEN_OFF_OPTIONS_SECONDS,
                secondaryLabel =
                    gpsScreenOffIntervalLabel(
                        seconds = turnByTurnScreenOffGpsIntervalSeconds,
                        screenOnSeconds = turnByTurnGpsIntervalSeconds,
                    ),
                dialogTitle = stringResource(R.string.gps_guidance_screen_off),
                screenOnSeconds = turnByTurnGpsIntervalSeconds,
                offWarningText = stringResource(R.string.gps_guidance_screen_off_warning),
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
private fun GpsTimingPickerRow(
    label: String,
    selectedValue: Int,
    options: List<Int>,
    secondaryLabel: String,
    dialogTitle: String,
    onSelect: (Int) -> Unit,
    screenOnSeconds: Int? = null,
    offWarningText: String? = null,
) {
    var pickerVisible by remember { mutableStateOf(false) }

    SettingsPickerChip(
        label = label,
        secondaryLabel = secondaryLabel,
        onClick = { pickerVisible = true },
    )
    GpsTimingStepperDialog(
        visible = pickerVisible,
        title = dialogTitle,
        selectedValue = selectedValue,
        options = options,
        screenOnSeconds = screenOnSeconds,
        offWarningText = offWarningText,
        onDismiss = { pickerVisible = false },
        onSelect = onSelect,
    )
}

@Composable
private fun GpsTimingStepperDialog(
    visible: Boolean,
    title: String,
    selectedValue: Int,
    options: List<Int>,
    screenOnSeconds: Int?,
    offWarningText: String?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    if (!visible) return

    val adaptive = rememberWearAdaptiveSpec()
    val focusRequester = remember { FocusRequester() }
    var selectedIndex by remember(options, selectedValue) {
        mutableIntStateOf(options.indexOf(selectedValue).coerceAtLeast(0))
    }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    val selectedSeconds = options.getOrElse(selectedIndex) { selectedValue }
    val selectedOption = gpsTimingOption(seconds = selectedSeconds, screenOnSeconds = screenOnSeconds)
    val canDecrease = selectedIndex > 0
    val canIncrease = selectedIndex < options.lastIndex
    val compactHighFontLayout = adaptive.fontScale > 1.05f

    fun selectIndex(index: Int) {
        val safeIndex = index.coerceIn(0, options.lastIndex)
        if (safeIndex == selectedIndex) return
        selectedIndex = safeIndex
        onSelect(options[safeIndex])
    }

    fun selectBySecondsDelta(deltaSeconds: Int) {
        val currentSeconds = options.getOrElse(selectedIndex) { selectedValue }
        val positiveOptions = options.filter { it > 0 }
        if (positiveOptions.isEmpty()) return
        if (currentSeconds <= 0) {
            if (deltaSeconds > 0) {
                selectIndex(options.indexOf(positiveOptions.first()))
            } else {
                selectIndex(selectedIndex - 1)
            }
            return
        }

        val firstPositiveIndex = options.indexOf(positiveOptions.first())
        val targetSeconds = currentSeconds + deltaSeconds
        val targetOption =
            if (deltaSeconds > 0) {
                positiveOptions.firstOrNull { it >= targetSeconds } ?: positiveOptions.last()
            } else {
                if (targetSeconds < positiveOptions.first()) {
                    selectIndex(firstPositiveIndex - 1)
                    return
                }
                positiveOptions.lastOrNull { it <= targetSeconds } ?: positiveOptions.first()
            }
        selectIndex(options.indexOf(targetOption))
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
                            rotaryAccumulator += event.verticalScrollPixels
                            if (abs(rotaryAccumulator) >= GPS_STEPPER_ROTARY_STEP_PX) {
                                if (rotaryAccumulator > 0f) {
                                    selectIndex(selectedIndex + 1)
                                } else {
                                    selectIndex(selectedIndex - 1)
                                }
                                rotaryAccumulator = 0f
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
                GpsTimingStepperValue(
                    label = selectedOption.label,
                    detail = selectedOption.detail,
                    compactHighFontLayout = compactHighFontLayout,
                    canDecrease = canDecrease,
                    canIncrease = canIncrease,
                    onDecrease = { selectIndex(selectedIndex - 1) },
                    onIncrease = { selectIndex(selectedIndex + 1) },
                    onLongDecrease = { selectBySecondsDelta(-GPS_STEPPER_LONG_PRESS_SECONDS) },
                    onLongIncrease = { selectBySecondsDelta(GPS_STEPPER_LONG_PRESS_SECONDS) },
                )
                if (
                    offWarningText != null &&
                    selectedSeconds == SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
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
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onLongDecrease: () -> Unit,
    onLongIncrease: () -> Unit,
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
            GpsStepperButton(
                enabled = canDecrease,
                size = stepperButtonSize,
                onClick = onDecrease,
                onLongClick = onLongDecrease,
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.gps_decrease_timing),
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
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
            GpsStepperButton(
                enabled = canIncrease,
                size = stepperButtonSize,
                onClick = onIncrease,
                onLongClick = onLongIncrease,
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.gps_increase_timing),
            )
        }
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
    primaryText: String,
    secondaryText: String = "",
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = primaryText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
        if (secondaryText.isNotBlank()) {
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val GPS_PICKER_DRAG_DISMISS_PX = 55f
private const val GPS_STEPPER_ROTARY_STEP_PX = 48f
private const val GPS_STEPPER_LONG_PRESS_SECONDS = 5

private val GPS_TIMING_SECONDS_OPTIONS = (1..60).toList() + listOf(90, 120)

private val REC_SCREEN_ON_OPTIONS_SECONDS =
    listOf(SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS) + GPS_TIMING_SECONDS_OPTIONS

private val TBT_SCREEN_ON_OPTIONS_SECONDS =
    listOf(SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS) + GPS_TIMING_SECONDS_OPTIONS

private val SCREEN_OFF_OPTIONS_SECONDS =
    listOf(
        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS,
        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS,
    ) + GPS_TIMING_SECONDS_OPTIONS

private val TBT_SCREEN_OFF_OPTIONS_SECONDS =
    listOf(SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS) + SCREEN_OFF_OPTIONS_SECONDS

private val GPS_USAGE_PROFILE_OPTIONS =
    listOf(
        SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE,
        SettingsRepository.GPS_USAGE_PROFILE_BALANCED,
        SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY,
    )

private fun gpsUsageProfileLabel(profile: String): String =
    when (profile) {
        SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE -> "Best trace · more battery"
        SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY -> "Long battery · less detail"
        SettingsRepository.GPS_USAGE_PROFILE_CUSTOM -> "Custom · advanced"
        else -> "Balanced · recommended"
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
