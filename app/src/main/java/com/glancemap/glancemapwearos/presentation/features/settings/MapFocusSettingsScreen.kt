package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
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
        item { Text(stringResource(R.string.settings_fullscreen_mode), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                stringResource(R.string.settings_fullscreen_controls_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        MapFocusControl.entries.forEach { control ->
            item {
                SettingsToggleChip(
                    checked = control in visibleControls,
                    onCheckedChanged = { onControlVisibleChange(control, it) },
                    label = stringResource(mapFocusControlLabelRes(control)),
                )
            }
        }
    }
}

@StringRes
internal fun mapFocusControlLabelRes(control: MapFocusControl): Int =
    when (control) {
        MapFocusControl.MENU_BUTTON -> R.string.settings_fullscreen_show_menu
        MapFocusControl.TOOLS_BUTTON -> R.string.settings_fullscreen_show_tools
        MapFocusControl.ZOOM_PLUS -> R.string.settings_fullscreen_show_zoom_plus
        MapFocusControl.ZOOM_MINUS -> R.string.settings_fullscreen_show_zoom_minus
        MapFocusControl.NORTH_INDICATOR -> R.string.settings_fullscreen_show_north
        MapFocusControl.NAV_MODE_BUTTON -> R.string.settings_fullscreen_show_nav_mode
        MapFocusControl.SCALE -> R.string.settings_fullscreen_show_scale
        MapFocusControl.TIME -> R.string.settings_fullscreen_show_time
        MapFocusControl.LIVE_METRICS -> R.string.settings_fullscreen_show_metrics
    }
