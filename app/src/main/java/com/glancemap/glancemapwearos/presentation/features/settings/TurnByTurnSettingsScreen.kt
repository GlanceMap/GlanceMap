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
import com.glancemap.glancemapwearos.presentation.ui.WearHelpDialog

@Composable
@Suppress("FunctionNaming", "LongMethod")
fun TurnByTurnSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenDashboardSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()
    val hapticsEnabled by viewModel.turnByTurnHapticsEnabled.collectAsState()
    val voiceGuidanceEnabled by viewModel.turnByTurnVoiceGuidanceEnabled.collectAsState()
    val offRouteAlertsEnabled by viewModel.turnByTurnOffRouteAlertsEnabled.collectAsState()
    val compactPopupEnabled by viewModel.turnByTurnCompactPopupEnabled.collectAsState()
    val mapProgressEnabled by viewModel.turnByTurnMapProgressEnabled.collectAsState()
    var showInfoDialog by remember { mutableStateOf(false) }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            SettingsInfoButton(
                contentDescription = stringResource(R.string.settings_turn_by_turn_info),
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
                checked = compactPopupEnabled,
                onCheckedChanged = viewModel::setTurnByTurnCompactPopupEnabled,
                label = stringResource(R.string.settings_small_map_popup),
                secondaryLabel =
                    if (compactPopupEnabled) {
                        stringResource(R.string.settings_show_tbt_chip_on_the_map)
                    } else {
                        stringResource(R.string.settings_hide_tbt_chip_on_the_map)
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = mapProgressEnabled,
                onCheckedChanged = viewModel::setTurnByTurnMapProgressEnabled,
                label = stringResource(R.string.settings_show_on_map),
                secondaryLabel = stringResource(R.string.settings_remaining_distance_time),
            )
        }
        item {
            SettingsToggleChip(
                checked = offRouteAlertsEnabled,
                onCheckedChanged = viewModel::setTurnByTurnOffRouteAlertsEnabled,
                label = stringResource(R.string.settings_off_route_alerts),
                secondaryLabel =
                    if (offRouteAlertsEnabled) {
                        stringResource(R.string.settings_warn_when_leaving_the_gpx)
                    } else {
                        stringResource(R.string.settings_show_off_route_status_only)
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = hapticsEnabled,
                onCheckedChanged = viewModel::setTurnByTurnHapticsEnabled,
                label = stringResource(R.string.settings_guidance_haptics),
                secondaryLabel =
                    if (hapticsEnabled) {
                        stringResource(
                            R.string.settings_vibrate_for_guidance_cues,
                        )
                    } else {
                        stringResource(R.string.settings_silent_guidance)
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = voiceGuidanceEnabled,
                onCheckedChanged = viewModel::setTurnByTurnVoiceGuidanceEnabled,
                label = stringResource(R.string.settings_voice_guidance),
                secondaryLabel =
                    if (voiceGuidanceEnabled) {
                        stringResource(
                            R.string.settings_speak_turn_cues,
                        )
                    } else {
                        stringResource(R.string.settings_voice_cues_off)
                    },
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_dashboard_2),
                secondaryLabel = stringResource(R.string.settings_route_metrics_and_pages),
                onClick = onOpenDashboardSettings,
            )
        }
        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_advanced_settings),
                secondaryLabel = stringResource(R.string.settings_route_alerts_and_battery),
                onClick = onOpenAdvancedSettings,
            )
        }
    }
    WearHelpDialog(
        visible = showInfoDialog,
        title = stringResource(R.string.settings_turn_by_turn),
        lines =
            listOf(
                stringResource(R.string.settings_tap_the_small_guidance_popup_to_open_the_full_turn_view),
                stringResource(R.string.settings_long_press_the_popup_to_pause_or_stop_guidance),
                stringResource(
                    R.string.settings_use_the_crown_or_swipe_vertically_to_move_between_guidance_route_metri,
                ),
                stringResource(
                    R.string.settings_tap_the_speaker_icon_in_the_full_turn_view_to_switch_voice_guidance_on,
                ),
                stringResource(R.string.settings_amber_guidance_means_you_are_off_route_the_distance_shows_how_far_you),
                stringResource(
                    R.string.settings_set_tbt_gps_timing_in_gps_settings_faster_timing_gives_more_reliable_a,
                ),
                stringResource(
                    R.string.settings_screen_off_saver_reduces_battery_use_but_turn_and_off_route_alerts_may,
                ),
                stringResource(
                    R.string.settings_turn_instructions_depend_on_the_gpx_geometry_or_routing_hints_availabl,
                ),
            ),
        onDismiss = { showInfoDialog = false },
    )
}
