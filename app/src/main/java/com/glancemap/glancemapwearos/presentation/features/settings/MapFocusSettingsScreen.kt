package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
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
    val autoEnabled by viewModel.mapFocusAutoEnabled.collectAsState()
    val delaySeconds by viewModel.mapFocusAutoDelaySeconds.collectAsState()
    MapFocusSettingsContent(
        visibleControls,
        viewModel::setMapFocusControlVisible,
        onOpenGeneralSettings,
        MapFocusAutoSettings(
            autoEnabled,
            delaySeconds,
            viewModel::setMapFocusAutoEnabled,
            viewModel::setMapFocusAutoDelaySeconds,
        ),
    )
}

@Composable
@Suppress("FunctionNaming")
internal fun MapFocusSettingsContent(
    visibleControls: Set<MapFocusControl>,
    onControlVisibleChange: (MapFocusControl, Boolean) -> Unit,
    onOpenGeneralSettings: () -> Unit,
    auto: MapFocusAutoSettings = MapFocusAutoSettings(),
) {
    WearSettingsListScreen {
        item { GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings) }
        item { Text(stringResource(R.string.settings_fullscreen_mode), style = MaterialTheme.typography.titleMedium) }
        item {
            SettingsToggleChip(
                checked = auto.enabled,
                onCheckedChanged = auto.onEnabledChange,
                label = stringResource(R.string.settings_fullscreen_auto_mode),
            )
        }
        if (auto.enabled) {
            item {
                var value by remember(auto.delaySeconds) { mutableFloatStateOf(auto.delaySeconds.toFloat()) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_fullscreen_auto_delay, value.toInt()))
                    Slider(
                        value = value,
                        onValueChange = {
                            value = it.toInt().toFloat()
                            auto.onDelayChange(value.toInt())
                        },
                        valueRange = 1f..20f,
                        steps = 18,
                        increaseIcon = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.settings_increase_timeout),
                            )
                        },
                        decreaseIcon = {
                            Icon(
                                Icons.Filled.Remove,
                                contentDescription = stringResource(R.string.settings_decrease_timeout),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.settings_fullscreen_controls_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val firstControls = listOf(MapFocusControl.TIME, MapFocusControl.NAV_MODE_BUTTON)
        (firstControls + MapFocusControl.entries.filterNot { it in firstControls }).forEach { control ->
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
