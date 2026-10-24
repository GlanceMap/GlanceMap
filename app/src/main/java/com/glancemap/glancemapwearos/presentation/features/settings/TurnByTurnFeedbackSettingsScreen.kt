package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R

@Composable
fun TurnByTurnFeedbackSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenTurnByTurnSettings: () -> Unit,
) {
    val hapticsEnabled by viewModel.turnByTurnHapticsEnabled.collectAsState()
    val voiceGuidanceEnabled by viewModel.turnByTurnVoiceGuidanceEnabled.collectAsState()

    WearSettingsListScreen(horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            TurnByTurnSettingsShortcutChip(onClick = onOpenTurnByTurnSettings)
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
                        stringResource(R.string.settings_off_by_default)
                    },
            )
        }
    }
}
