package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.ui.WearHelpDialog

@Composable
fun RecordingSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onOpenExternalSensors: () -> Unit,
    onOpenDashboardSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()
    val showSavedGpxOnMap by viewModel.recordingShowSavedGpxOnMap.collectAsState()
    val startWithTurnByTurn by viewModel.recordingStartWithTurnByTurn.collectAsState()
    val autoPauseMode by viewModel.recordingAutoPauseMode.collectAsState()
    val autoPauseEnabled = autoPauseMode == SettingsRepository.RECORDING_AUTO_PAUSE_ALWAYS
    var showInfoDialog by remember { mutableStateOf(false) }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            SettingsInfoButton(
                contentDescription = stringResource(R.string.settings_recording_info),
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
                checked = autoPauseEnabled,
                onCheckedChanged = { enabled ->
                    viewModel.setRecordingAutoPauseMode(
                        if (enabled) {
                            SettingsRepository.RECORDING_AUTO_PAUSE_ALWAYS
                        } else {
                            SettingsRepository.RECORDING_AUTO_PAUSE_OFF
                        },
                    )
                },
                label = stringResource(R.string.gps_auto_pause),
                secondaryLabel =
                    if (autoPauseEnabled) {
                        stringResource(
                            R.string.settings_pause_rec_when_still,
                        )
                    } else {
                        stringResource(R.string.settings_keep_rec_running)
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = startWithTurnByTurn,
                onCheckedChanged = viewModel::setRecordingStartWithTurnByTurn,
                label = stringResource(R.string.settings_rec_with_guidance),
                secondaryLabel =
                    if (startWithTurnByTurn) {
                        stringResource(
                            R.string.settings_starts_with_turn_by_turn,
                        )
                    } else {
                        stringResource(R.string.settings_manual_start)
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = showSavedGpxOnMap,
                onCheckedChanged = viewModel::setRecordingShowSavedGpxOnMap,
                label = stringResource(R.string.settings_show_saved_activity),
                secondaryLabel =
                    if (showSavedGpxOnMap) {
                        stringResource(
                            R.string.settings_on_map_after_save,
                        )
                    } else {
                        stringResource(R.string.settings_saved_but_hidden)
                    },
            )
        }
        item {
            RecordingDashboardSettingsFolder(onClick = onOpenDashboardSettings)
        }
        item {
            RecordingSourceSettingsFolder(onClick = onOpenSourceSettings)
        }
        item {
            RecordingExternalSensorsSetting(onClick = onOpenExternalSensors)
        }
        item {
            RecordingAdvancedSettingsFolder(onClick = onOpenAdvancedSettings)
        }
    }

    WearHelpDialog(
        visible = showInfoDialog,
        title = stringResource(R.string.common_recording),
        lines =
            listOf(
                stringResource(R.string.settings_tap_rec_to_start_recording),
                stringResource(R.string.settings_while_recording_tap_the_time_or_rec_popup_to_open_the_dashboard),
                stringResource(R.string.settings_long_press_the_time_or_rec_popup_for_pause_stop_and_discard_controls),
                stringResource(R.string.settings_use_the_crown_or_swipe_vertically_to_change_dashboard_pages),
                stringResource(R.string.settings_long_press_a_dashboard_measure_to_replace_it),
                stringResource(
                    R.string.settings_set_rec_gps_timing_in_gps_settings_shorter_timing_gives_a_more_detaile,
                ),
                stringResource(R.string.settings_adaptive_track_smoothing_reduces_gps_noise_while_preserving_confirmed),
                stringResource(
                    R.string.settings_progress_vibration_is_off_by_default_set_it_in_advanced_to_buzz_at_a_d,
                ),
                stringResource(
                    R.string.settings_pausing_creates_a_break_in_the_saved_track_so_stopped_time_is_not_join,
                ),
            ),
        onDismiss = { showInfoDialog = false },
    )
}

@Composable
private fun RecordingSourceSettingsFolder(
    onClick: () -> Unit,
) {
    SettingsSectionChip(
        label = stringResource(R.string.common_source),
        secondaryLabel = stringResource(R.string.settings_gps_dem_sensors),
        onClick = onClick,
    )
}

@Composable
private fun RecordingExternalSensorsSetting(onClick: () -> Unit) {
    SettingsSectionChip(
        label = stringResource(R.string.settings_external_sensors),
        secondaryLabel = stringResource(R.string.settings_heart_steps_pods),
        onClick = onClick,
    )
}

@Composable
private fun RecordingDashboardSettingsFolder(
    onClick: () -> Unit,
) {
    SettingsSectionChip(
        label = stringResource(R.string.settings_dashboard_2),
        secondaryLabel = stringResource(R.string.settings_pages_and_metrics),
        onClick = onClick,
    )
}

@Composable
private fun RecordingAdvancedSettingsFolder(onClick: () -> Unit) {
    SettingsSectionChip(
        label = stringResource(R.string.gps_advanced),
        secondaryLabel = stringResource(R.string.settings_smoothing_progress_vibration),
        onClick = onClick,
    )
}
