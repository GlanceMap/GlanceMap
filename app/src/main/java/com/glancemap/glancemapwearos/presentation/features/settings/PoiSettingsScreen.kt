package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

@Composable
fun PoiSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val listTokens = rememberSettingsListTokens()
    val poiIconSizePx by viewModel.poiIconSizePx.collectAsState()
    val poiMarkerStyle by viewModel.poiMarkerStyle.collectAsState()
    val poiTapToCenterEnabled by viewModel.poiTapToCenterEnabled.collectAsState()
    val poiMapLongPressActionsEnabled by viewModel.poiMapLongPressActionsEnabled.collectAsState()
    val linkGpxWaypointPoiFolders by viewModel.linkGpxWaypointPoiFolders.collectAsState()
    val poiPopupTimeoutSeconds by viewModel.poiPopupTimeoutSeconds.collectAsState()
    val poiPopupManualCloseOnly by viewModel.poiPopupManualCloseOnly.collectAsState()
    val iconSizeOptions =
        remember {
            listOf(
                SettingsRepository.POI_ICON_SIZE_SMALL_PX to resources.getString(R.string.settings_small),
                SettingsRepository.POI_ICON_SIZE_MEDIUM_PX to resources.getString(R.string.settings_medium),
                SettingsRepository.POI_ICON_SIZE_LARGE_PX to resources.getString(R.string.settings_big),
            )
        }
    val markerStyleOptions =
        remember {
            listOf(
                SettingsRepository.POI_MARKER_STYLE_BADGE to resources.getString(R.string.settings_colored_circle),
                SettingsRepository.POI_MARKER_STYLE_THEME_ICON to resources.getString(R.string.settings_theme_icon),
            )
        }
    val tapActionOptions =
        remember {
            listOf(
                true to resources.getString(R.string.settings_open_navigate_at_poi),
                false to resources.getString(R.string.settings_disabled),
            )
        }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_poi_icon_size),
                selectedValue = poiIconSizePx,
                options = iconSizeOptions,
                secondaryLabel = poiIconSizeLabel(poiIconSizePx, resources = resources),
                onSelect = viewModel::setPoiIconSizePx,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_poi_marker_style),
                selectedValue = poiMarkerStyle,
                options = markerStyleOptions,
                secondaryLabel = poiMarkerStyleLabel(poiMarkerStyle, resources = resources),
                onSelect = viewModel::setPoiMarkerStyle,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_poi_list_tap_action),
                selectedValue = poiTapToCenterEnabled,
                options = tapActionOptions,
                secondaryLabel =
                    if (poiTapToCenterEnabled) {
                        stringResource(R.string.settings_open_navigate_at_poi)
                    } else {
                        stringResource(R.string.settings_disabled)
                    },
                onSelect = viewModel::setPoiTapToCenterEnabled,
            )
        }
        item {
            SettingsToggleChip(
                checked = poiMapLongPressActionsEnabled,
                onCheckedChanged = viewModel::setPoiMapLongPressActionsEnabled,
                label = stringResource(R.string.settings_long_press_action),
                secondaryLabel =
                    if (poiMapLongPressActionsEnabled) {
                        stringResource(R.string.settings_create_poi_or_route)
                    } else {
                        stringResource(R.string.settings_disabled)
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = linkGpxWaypointPoiFolders,
                onCheckedChanged = viewModel::setLinkGpxWaypointPoiFolders,
                label = stringResource(R.string.settings_link_gpx_waypoint_folders),
            )
        }
        item {
            PoiPopupTimeoutSettings(
                autoTimeoutEnabled = !poiPopupManualCloseOnly,
                poiPopupTimeoutSeconds = poiPopupTimeoutSeconds,
                onAutoTimeoutToggle = { enabled ->
                    viewModel.setPoiPopupManualCloseOnly(!enabled)
                },
                onTimeoutChange = { target ->
                    val clamped =
                        target
                            .coerceAtLeast(SettingsRepository.POI_POPUP_TIMEOUT_MIN_SECONDS)
                            .coerceAtMost(SettingsRepository.POI_POPUP_TIMEOUT_MAX_SECONDS)
                    if (clamped != poiPopupTimeoutSeconds) {
                        viewModel.setPoiPopupTimeoutSeconds(clamped)
                    }
                },
            )
        }
    }
}

private fun poiIconSizeLabel(
    sizePx: Int,
    resources: Resources,
): String =
    when (sizePx) {
        SettingsRepository.POI_ICON_SIZE_SMALL_PX -> resources.getString(R.string.settings_small)
        SettingsRepository.POI_ICON_SIZE_MEDIUM_PX -> resources.getString(R.string.settings_medium)
        SettingsRepository.POI_ICON_SIZE_LARGE_PX -> resources.getString(R.string.settings_big)
        else -> resources.getString(R.string.settings_medium)
    }

private fun poiMarkerStyleLabel(
    style: String,
    resources: Resources,
): String =
    when (style) {
        SettingsRepository.POI_MARKER_STYLE_THEME_ICON -> resources.getString(R.string.settings_theme_icon)
        else -> resources.getString(R.string.settings_colored_circle)
    }

@Composable
private fun PoiPopupTimeoutSettings(
    autoTimeoutEnabled: Boolean,
    poiPopupTimeoutSeconds: Int,
    onAutoTimeoutToggle: (Boolean) -> Unit,
    onTimeoutChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsToggleChip(
            checked = autoTimeoutEnabled,
            onCheckedChanged = onAutoTimeoutToggle,
            label = stringResource(R.string.settings_poi_popup_timeout),
        )

        if (autoTimeoutEnabled) {
            PoiPopupTimeoutSlider(
                timeoutSeconds = poiPopupTimeoutSeconds,
                minSeconds = SettingsRepository.POI_POPUP_TIMEOUT_MIN_SECONDS,
                maxSeconds = SettingsRepository.POI_POPUP_TIMEOUT_MAX_SECONDS,
                onValueChange = onTimeoutChange,
            )
        }
    }
}

@Composable
private fun PoiPopupTimeoutSlider(
    timeoutSeconds: Int,
    minSeconds: Int,
    maxSeconds: Int,
    onValueChange: (Int) -> Unit,
) {
    var internalValue by remember(timeoutSeconds) { mutableStateOf(timeoutSeconds.toFloat()) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.settings_popup_timeout_s, internalValue.toInt()),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = internalValue,
            onValueChange = { raw ->
                val snapped = raw.toInt().coerceIn(minSeconds, maxSeconds)
                internalValue = snapped.toFloat()
                onValueChange(snapped)
            },
            valueRange = minSeconds.toFloat()..maxSeconds.toFloat(),
            steps = (maxSeconds - minSeconds - 1).coerceAtLeast(0),
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
