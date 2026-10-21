package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R

@Composable
fun TurnByTurnGuidanceSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenTurnByTurnSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val routeStartBehavior by viewModel.turnByTurnRouteStartBehavior.collectAsState()
    val reverseSuggestionMode by viewModel.turnByTurnReverseSuggestionMode.collectAsState()

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
    }
}
