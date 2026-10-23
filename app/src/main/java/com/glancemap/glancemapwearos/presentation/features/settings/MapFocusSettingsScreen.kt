package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.data.repository.MapFocusControl

@Composable
@Suppress("FunctionNaming")
fun MapFocusSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val visibleControls by viewModel.mapFocusVisibleControls.collectAsState()
    MapFocusSettingsContent(visibleControls, viewModel::setMapFocusControlVisible, onOpenGeneralSettings)
}

@Composable
@Suppress("FunctionNaming")
internal fun MapFocusSettingsContent(
    visibleControls: Set<MapFocusControl>,
    onControlVisibleChange: (MapFocusControl, Boolean) -> Unit,
    onOpenGeneralSettings: () -> Unit,
) {
    WearSettingsListScreen {
        item { GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings) }
        item { Text("Fullscreen mode", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Choose controls to keep visible. Normal map settings still apply. Exit is always available.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        MapFocusControl.entries.forEach { control ->
            item {
                SettingsToggleChip(
                    checked = control in visibleControls,
                    onCheckedChanged = { onControlVisibleChange(control, it) },
                    label = "Show ${mapFocusControlLabel(control)}",
                )
            }
        }
    }
}

internal fun mapFocusControlLabel(control: MapFocusControl): String =
    when (control) {
        MapFocusControl.MENU_BUTTON -> "menu button"
        MapFocusControl.TOOLS_BUTTON -> "tools button"
        MapFocusControl.ZOOM_PLUS -> "zoom +"
        MapFocusControl.ZOOM_MINUS -> "zoom −"
        MapFocusControl.NORTH_INDICATOR -> "north indicator"
        MapFocusControl.NAV_MODE_BUTTON -> "center/mode button"
        MapFocusControl.SCALE -> "scale"
        MapFocusControl.TIME -> "time"
        MapFocusControl.LIVE_METRICS -> "live metrics"
    }
