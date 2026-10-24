package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R

@Composable
fun TurnByTurnAlertsSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenTurnByTurnSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val turnAlertsMode by viewModel.turnByTurnTurnAlertsMode.collectAsState()
    val offRouteThresholdMeters by viewModel.turnByTurnOffRouteAlertThresholdMeters.collectAsState()
    val offRouteRepeatSeconds by viewModel.turnByTurnOffRouteRepeatSeconds.collectAsState()

    WearSettingsListScreen(horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            TurnByTurnSettingsShortcutChip(onClick = onOpenTurnByTurnSettings)
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
    }
}
