package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
                contentDescription = "Turn-by-turn info",
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
                label = "Small map popup",
                secondaryLabel =
                    if (compactPopupEnabled) {
                        "Show TBT chip on the map"
                    } else {
                        "Hide TBT chip on the map"
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = mapProgressEnabled,
                onCheckedChanged = viewModel::setTurnByTurnMapProgressEnabled,
                label = "Map distance and time",
                secondaryLabel =
                    if (mapProgressEnabled) {
                        "Show remaining route details"
                    } else {
                        "Keep route details in TBT view"
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = offRouteAlertsEnabled,
                onCheckedChanged = viewModel::setTurnByTurnOffRouteAlertsEnabled,
                label = "Off-route alerts",
                secondaryLabel =
                    if (offRouteAlertsEnabled) {
                        "Warn when leaving the GPX"
                    } else {
                        "Show off-route status only"
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = hapticsEnabled,
                onCheckedChanged = viewModel::setTurnByTurnHapticsEnabled,
                label = "Guidance haptics",
                secondaryLabel = if (hapticsEnabled) "Vibrate for guidance cues" else "Silent guidance",
            )
        }
        item {
            SettingsToggleChip(
                checked = voiceGuidanceEnabled,
                onCheckedChanged = viewModel::setTurnByTurnVoiceGuidanceEnabled,
                label = "Voice guidance",
                secondaryLabel = if (voiceGuidanceEnabled) "Speak turn cues" else "Voice cues off",
            )
        }
        item {
            SettingsSectionChip(
                label = "Dashboard",
                secondaryLabel = "Route metrics and pages",
                onClick = onOpenDashboardSettings,
            )
        }
        item {
            SettingsSectionChip(
                label = "Advanced settings",
                secondaryLabel = "Route, alerts and battery",
                onClick = onOpenAdvancedSettings,
            )
        }
    }
    WearHelpDialog(
        visible = showInfoDialog,
        title = "Turn-by-turn",
        lines =
            listOf(
                "Tap the small guidance popup to open the full turn view.",
                "Long press the popup to pause or stop guidance.",
                "Use the crown or swipe vertically to move between guidance, route metrics and REC pages.",
                "Tap the speaker icon in the full turn view to switch voice guidance on or off.",
                "Amber guidance means you are off route. The distance shows how far you are from the GPX.",
                "Set TBT GPS timing in GPS settings. Faster timing gives more reliable alerts but uses more battery.",
                "Screen-off saver reduces battery use, but turn and off-route alerts may arrive later.",
                "Turn instructions depend on the GPX geometry or routing hints available in the file.",
            ),
        onDismiss = { showInfoDialog = false },
    )
}
