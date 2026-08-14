package com.glancemap.glancemapwearos.presentation.features.settings

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

@Composable
fun PoiSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()
    val poiIconSizePx by viewModel.poiIconSizePx.collectAsState()
    val poiMarkerStyle by viewModel.poiMarkerStyle.collectAsState()
    val poiTapToCenterEnabled by viewModel.poiTapToCenterEnabled.collectAsState()
    val linkGpxWaypointPoiFolders by viewModel.linkGpxWaypointPoiFolders.collectAsState()
    val poiPopupTimeoutSeconds by viewModel.poiPopupTimeoutSeconds.collectAsState()
    val poiPopupManualCloseOnly by viewModel.poiPopupManualCloseOnly.collectAsState()
    val iconSizeOptions =
        remember {
            listOf(
                SettingsRepository.POI_ICON_SIZE_SMALL_PX to "Small",
                SettingsRepository.POI_ICON_SIZE_MEDIUM_PX to "Medium",
                SettingsRepository.POI_ICON_SIZE_LARGE_PX to "Big",
            )
        }
    val markerStyleOptions =
        remember {
            listOf(
                SettingsRepository.POI_MARKER_STYLE_BADGE to "Colored circle",
                SettingsRepository.POI_MARKER_STYLE_THEME_ICON to "Theme icon",
            )
        }
    val tapActionOptions =
        remember {
            listOf(
                true to "Open Navigate at POI",
                false to "Disabled",
            )
        }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }
        item {
            SettingsOptionPickerRow(
                label = "POI icon size",
                selectedValue = poiIconSizePx,
                options = iconSizeOptions,
                secondaryLabel = poiIconSizeLabel(poiIconSizePx),
                onSelect = viewModel::setPoiIconSizePx,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "POI marker style",
                selectedValue = poiMarkerStyle,
                options = markerStyleOptions,
                secondaryLabel = poiMarkerStyleLabel(poiMarkerStyle),
                onSelect = viewModel::setPoiMarkerStyle,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "POI list tap action",
                selectedValue = poiTapToCenterEnabled,
                options = tapActionOptions,
                secondaryLabel =
                    if (poiTapToCenterEnabled) {
                        "Open Navigate at POI"
                    } else {
                        "Disabled"
                    },
                onSelect = viewModel::setPoiTapToCenterEnabled,
            )
        }
        item {
            SettingsToggleChip(
                checked = linkGpxWaypointPoiFolders,
                onCheckedChanged = viewModel::setLinkGpxWaypointPoiFolders,
                label = "Link GPX waypoint folders",
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

private fun poiIconSizeLabel(sizePx: Int): String =
    when (sizePx) {
        SettingsRepository.POI_ICON_SIZE_SMALL_PX -> "Small"
        SettingsRepository.POI_ICON_SIZE_MEDIUM_PX -> "Medium"
        SettingsRepository.POI_ICON_SIZE_LARGE_PX -> "Big"
        else -> "Medium"
    }

private fun poiMarkerStyleLabel(style: String): String =
    when (style) {
        SettingsRepository.POI_MARKER_STYLE_THEME_ICON -> "Theme icon"
        else -> "Colored circle"
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
            label = "POI popup timeout",
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
            "Popup timeout: ${internalValue.toInt()}s",
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
            increaseIcon = { Icon(Icons.Filled.Add, contentDescription = "Increase timeout") },
            decreaseIcon = { Icon(Icons.Filled.Remove, contentDescription = "Decrease timeout") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
