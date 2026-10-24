package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R

@Composable
fun TurnByTurnBackgroundSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenTurnByTurnSettings: () -> Unit,
) {
    val guidanceGpsInAmbient by viewModel.turnByTurnGpsInAmbientMode.collectAsState()

    WearSettingsListScreen(horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            TurnByTurnSettingsShortcutChip(onClick = onOpenTurnByTurnSettings)
        }
        item {
            SettingsToggleChip(
                checked = guidanceGpsInAmbient,
                onCheckedChanged = viewModel::setTurnByTurnGpsInAmbientMode,
                label = stringResource(R.string.settings_guidance_gps_ambient),
                secondaryLabel =
                    if (guidanceGpsInAmbient) {
                        stringResource(R.string.settings_keep_gps_for_alerts_while_screen_is_off)
                    } else {
                        stringResource(R.string.settings_use_normal_gps_ambient_setting)
                    },
            )
        }
    }
}
