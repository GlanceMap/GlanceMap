package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlin.math.roundToInt

@Composable
fun RecordingAdvancedSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenRecordingSettings: () -> Unit,
) {
    val trackSmoothingMode by viewModel.recordingTrackSmoothingMode.collectAsState()
    val progressVibrationSettings by viewModel.recordingProgressVibrationSettings.collectAsState()

    WearSettingsListScreen(horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            RecordingSettingsShortcutChip(onClick = onOpenRecordingSettings)
        }
        item {
            SettingsOptionPickerRow(
                label = "Track smoothing",
                selectedValue = trackSmoothingMode,
                options = RECORDING_TRACK_SMOOTHING_OPTIONS.map { it to recordingTrackSmoothingLabel(it) },
                secondaryLabel = recordingTrackSmoothingLabel(trackSmoothingMode),
                onSelect = viewModel::setRecordingTrackSmoothingMode,
            )
        }
        item {
            SettingsToggleChip(
                checked = progressVibrationSettings.distanceEnabled,
                onCheckedChanged = viewModel::setRecordingProgressVibrationDistanceEnabled,
                label = "Distance vibration",
                secondaryLabel =
                    if (progressVibrationSettings.distanceEnabled) {
                        distanceVibrationIntervalLabel(progressVibrationSettings.distanceMeters)
                    } else {
                        "Off"
                    },
            )
        }
        if (progressVibrationSettings.distanceEnabled) {
            item {
                ProgressVibrationDistanceSlider(
                    distanceMeters = progressVibrationSettings.distanceMeters,
                    onDistanceMetersChanged = viewModel::setRecordingProgressVibrationDistanceMeters,
                )
            }
        }
        item {
            SettingsToggleChip(
                checked = progressVibrationSettings.timeEnabled,
                onCheckedChanged = viewModel::setRecordingProgressVibrationTimeEnabled,
                label = "Time vibration",
                secondaryLabel =
                    if (progressVibrationSettings.timeEnabled) {
                        timeVibrationIntervalLabel(progressVibrationSettings.timeMinutes)
                    } else {
                        "Off"
                    },
            )
        }
        if (progressVibrationSettings.timeEnabled) {
            item {
                ProgressVibrationTimeSlider(
                    timeMinutes = progressVibrationSettings.timeMinutes,
                    onTimeMinutesChanged = viewModel::setRecordingProgressVibrationTimeMinutes,
                )
            }
        }
    }
}

@Composable
private fun ProgressVibrationDistanceSlider(
    distanceMeters: Int,
    onDistanceMetersChanged: (Int) -> Unit,
) {
    var internalValue by remember(distanceMeters) { mutableStateOf(distanceMeters.toFloat()) }
    val distanceValueRange =
        progressVibrationValueRange(
            min = SettingsRepository.MIN_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
            max = SettingsRepository.MAX_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
        )

    ProgressVibrationSlider(
        label = "Distance",
        valueLabel = distanceVibrationIntervalLabel(internalValue.roundToInt()),
        value = internalValue,
        valueRange = distanceValueRange,
        steps =
            progressVibrationSliderSteps(
                min = SettingsRepository.MIN_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
                max = SettingsRepository.MAX_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
                step = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_STEP_METERS,
            ),
        onValueChange = { rawValue ->
            val snapped =
                snapProgressVibrationValue(
                    value = rawValue,
                    min = SettingsRepository.MIN_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
                    max = SettingsRepository.MAX_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
                    step = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_STEP_METERS,
                )
            internalValue = snapped.toFloat()
            onDistanceMetersChanged(snapped)
        },
    )
}

@Composable
private fun ProgressVibrationTimeSlider(
    timeMinutes: Int,
    onTimeMinutesChanged: (Int) -> Unit,
) {
    var internalValue by remember(timeMinutes) { mutableStateOf(timeMinutes.toFloat()) }
    val timeValueRange =
        progressVibrationValueRange(
            min = SettingsRepository.MIN_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
            max = SettingsRepository.MAX_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
        )

    ProgressVibrationSlider(
        label = "Time",
        valueLabel = timeVibrationIntervalLabel(internalValue.roundToInt()),
        value = internalValue,
        valueRange = timeValueRange,
        steps =
            progressVibrationSliderSteps(
                min = SettingsRepository.MIN_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
                max = SettingsRepository.MAX_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
                step = SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_STEP_MINUTES,
            ),
        onValueChange = { rawValue ->
            val snapped =
                snapProgressVibrationValue(
                    value = rawValue,
                    min = SettingsRepository.MIN_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
                    max = SettingsRepository.MAX_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
                    step = SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_STEP_MINUTES,
                )
            internalValue = snapped.toFloat()
            onTimeMinutesChanged(snapped)
        },
    )
}

@Composable
private fun ProgressVibrationSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PROGRESS_VIBRATION_SLIDER_SPACING),
    ) {
        Text(
            text = "$label: $valueLabel",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            increaseIcon = { Icon(Icons.Filled.Add, contentDescription = "Increase $label interval") },
            decreaseIcon = { Icon(Icons.Filled.Remove, contentDescription = "Decrease $label interval") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val RECORDING_TRACK_SMOOTHING_OPTIONS =
    listOf(
        SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF,
        SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
        SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
    )

private fun recordingTrackSmoothingLabel(mode: String): String =
    when (mode) {
        SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF -> "Off · quality checks only"
        SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG -> "Strong · cleaner track"
        else -> "Adaptive · recommended"
    }

private fun distanceVibrationIntervalLabel(distanceMeters: Int): String =
    if (distanceMeters % 1_000 == 0) {
        "Every ${distanceMeters / 1_000} km"
    } else {
        "Every ${distanceMeters / 1_000}.${(distanceMeters % 1_000) / 100} km"
    }

private fun timeVibrationIntervalLabel(timeMinutes: Int): String = "Every $timeMinutes min"

private fun progressVibrationSliderSteps(
    min: Int,
    max: Int,
    step: Int,
): Int = ((max - min) / step - 1).coerceAtLeast(0)

private fun progressVibrationValueRange(
    min: Int,
    max: Int,
): ClosedFloatingPointRange<Float> = min.toFloat()..max.toFloat()

private fun snapProgressVibrationValue(
    value: Float,
    min: Int,
    max: Int,
    step: Int,
): Int = (((value.coerceIn(min.toFloat(), max.toFloat()) - min) / step).roundToInt() * step) + min

private val PROGRESS_VIBRATION_SLIDER_SPACING = 4.dp
