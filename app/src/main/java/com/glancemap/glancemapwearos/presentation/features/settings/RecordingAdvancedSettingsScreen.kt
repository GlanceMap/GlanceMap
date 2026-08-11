package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

@Composable
fun RecordingAdvancedSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenRecordingSettings: () -> Unit,
) {
    val trackSmoothingMode by viewModel.recordingTrackSmoothingMode.collectAsState()
    val progressVibrationMode by viewModel.recordingProgressVibrationMode.collectAsState()

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
            SettingsOptionPickerRow(
                label = "Progress vibration",
                selectedValue = progressVibrationMode,
                options = RECORDING_PROGRESS_VIBRATION_OPTIONS.map { it to recordingProgressVibrationLabel(it) },
                secondaryLabel = recordingProgressVibrationLabel(progressVibrationMode),
                onSelect = viewModel::setRecordingProgressVibrationMode,
            )
        }
    }
}

private val RECORDING_TRACK_SMOOTHING_OPTIONS =
    listOf(
        SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF,
        SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
        SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
    )

private val RECORDING_PROGRESS_VIBRATION_OPTIONS =
    listOf(
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_OFF,
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS,
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS,
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS,
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES,
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES,
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES,
    )

private fun recordingTrackSmoothingLabel(mode: String): String =
    when (mode) {
        SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF -> "Off · quality checks only"
        SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG -> "Strong · cleaner track"
        else -> "Adaptive · recommended"
    }

private fun recordingProgressVibrationLabel(mode: String): String =
    when (mode) {
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS -> "Every 500 m"
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER -> "Every 1 km"
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS -> "Every 2 km"
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS -> "Every 5 km"
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES -> "Every 15 min"
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES -> "Every 30 min"
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES -> "Every 60 min"
        else -> "Off"
    }
