package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R

@Composable
@Suppress("FunctionNaming", "LongMethod")
fun TurnByTurnAdvancedSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenTurnByTurnSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val routeStartBehavior by viewModel.turnByTurnRouteStartBehavior.collectAsState()
    val reverseSuggestionMode by viewModel.turnByTurnReverseSuggestionMode.collectAsState()
    val turnAlertsMode by viewModel.turnByTurnTurnAlertsMode.collectAsState()
    val offRouteThresholdMeters by viewModel.turnByTurnOffRouteAlertThresholdMeters.collectAsState()
    val offRouteRepeatSeconds by viewModel.turnByTurnOffRouteRepeatSeconds.collectAsState()
    val screenOffBatchingEnabled by viewModel.turnByTurnScreenOffBatchingEnabled.collectAsState()
    val elevationProgressRingEnabled by viewModel.turnByTurnElevationProgressRingEnabled.collectAsState()

    WearSettingsListScreen(horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            TurnByTurnSettingsShortcutChip(onClick = onOpenTurnByTurnSettings)
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_route_start_behavior),
                selectedValue = routeStartBehavior,
                options = TurnByTurnRouteStartOptions.map { (value, labelRes) -> value to stringResource(labelRes) },
                secondaryLabel = routeStartBehaviorLabel(routeStartBehavior, resources = resources),
                onSelect = viewModel::setTurnByTurnRouteStartBehavior,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_suggest_reverse_route),
                selectedValue = reverseSuggestionMode,
                options =
                    TurnByTurnReverseSuggestionOptions.map { (value, labelRes) ->
                        value to
                            stringResource(labelRes)
                    },
                secondaryLabel = reverseSuggestionLabel(reverseSuggestionMode, resources = resources),
                onSelect = viewModel::setTurnByTurnReverseSuggestionMode,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_turn_alerts),
                selectedValue = turnAlertsMode,
                options = TurnByTurnAlertsOptions.map { (value, labelRes) -> value to stringResource(labelRes) },
                secondaryLabel = turnAlertsLabel(turnAlertsMode, resources = resources),
                onSelect = viewModel::setTurnByTurnTurnAlertsMode,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_off_route_distance),
                selectedValue = offRouteThresholdMeters,
                options = TurnByTurnOffRouteThresholdOptions.map { it to stringResource(R.string.settings_m, it) },
                secondaryLabel = stringResource(R.string.settings_m, offRouteThresholdMeters),
                onSelect = viewModel::setTurnByTurnOffRouteAlertThresholdMeters,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_repeat_off_route),
                selectedValue = offRouteRepeatSeconds,
                options = TurnByTurnOffRouteRepeatOptions.map { it to stringResource(R.string.settings_s, it) },
                secondaryLabel = stringResource(R.string.settings_s, offRouteRepeatSeconds),
                onSelect = viewModel::setTurnByTurnOffRouteRepeatSeconds,
            )
        }
        item {
            SettingsToggleChip(
                checked = screenOffBatchingEnabled,
                onCheckedChanged = viewModel::setTurnByTurnScreenOffBatchingEnabled,
                label = stringResource(R.string.settings_screen_off_saver),
                secondaryLabel =
                    if (screenOffBatchingEnabled) {
                        stringResource(R.string.settings_save_battery_later_alerts)
                    } else {
                        stringResource(R.string.settings_normal_alert_timing)
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = elevationProgressRingEnabled,
                onCheckedChanged = viewModel::setTurnByTurnElevationProgressRingEnabled,
                label = stringResource(R.string.settings_elevation_ring),
                secondaryLabel = stringResource(R.string.settings_uphill_downhill),
            )
        }
    }
}
