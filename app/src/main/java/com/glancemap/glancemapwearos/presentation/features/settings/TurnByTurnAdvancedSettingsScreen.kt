package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment

@Composable
@Suppress("FunctionNaming", "LongMethod")
fun TurnByTurnAdvancedSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenTurnByTurnSettings: () -> Unit,
) {
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
                label = "Route start behavior",
                selectedValue = routeStartBehavior,
                options = TurnByTurnRouteStartOptions,
                secondaryLabel = routeStartBehaviorLabel(routeStartBehavior),
                onSelect = viewModel::setTurnByTurnRouteStartBehavior,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "Suggest reverse route",
                selectedValue = reverseSuggestionMode,
                options = TurnByTurnReverseSuggestionOptions,
                secondaryLabel = reverseSuggestionLabel(reverseSuggestionMode),
                onSelect = viewModel::setTurnByTurnReverseSuggestionMode,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "Turn alerts",
                selectedValue = turnAlertsMode,
                options = TurnByTurnAlertsOptions,
                secondaryLabel = turnAlertsLabel(turnAlertsMode),
                onSelect = viewModel::setTurnByTurnTurnAlertsMode,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "Off-route distance",
                selectedValue = offRouteThresholdMeters,
                options = TurnByTurnOffRouteThresholdOptions,
                secondaryLabel = "$offRouteThresholdMeters m",
                onSelect = viewModel::setTurnByTurnOffRouteAlertThresholdMeters,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "Repeat off-route",
                selectedValue = offRouteRepeatSeconds,
                options = TurnByTurnOffRouteRepeatOptions,
                secondaryLabel = "${offRouteRepeatSeconds}s",
                onSelect = viewModel::setTurnByTurnOffRouteRepeatSeconds,
            )
        }
        item {
            SettingsToggleChip(
                checked = screenOffBatchingEnabled,
                onCheckedChanged = viewModel::setTurnByTurnScreenOffBatchingEnabled,
                label = "Screen-off saver",
                secondaryLabel =
                    if (screenOffBatchingEnabled) {
                        "Save battery, later alerts"
                    } else {
                        "Normal alert timing"
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = elevationProgressRingEnabled,
                onCheckedChanged = viewModel::setTurnByTurnElevationProgressRingEnabled,
                label = "Elevation progress ring",
                secondaryLabel =
                    if (elevationProgressRingEnabled) {
                        "Colour progress by route grade"
                    } else {
                        "Use a green progress ring"
                    },
            )
        }
    }
}
