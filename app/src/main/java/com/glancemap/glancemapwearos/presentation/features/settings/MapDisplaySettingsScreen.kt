@file:Suppress("FunctionName", "FunctionNaming", "LongMethod")

package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

@Composable
fun MapDisplaySettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()
    val northIndicatorMode by viewModel.northIndicatorMode.collectAsState()
    val navigationMarkerStyle by viewModel.navigationMarkerStyle.collectAsState()
    val showTimeInNavigate by viewModel.showTimeInNavigate.collectAsState()
    val navigateTimeFormat by viewModel.navigateTimeFormat.collectAsState()
    val mapZoomButtonsMode by viewModel.mapZoomButtonsMode.collectAsState()
    val mapLabelSize by viewModel.mapLabelSize.collectAsState()
    val gpsAccuracyCircleEnabled by viewModel.gpsAccuracyCircleEnabled.collectAsState()

    val northIndicatorModes = listOf("ALWAYS", "COMPASS_ONLY", "NORTH_UP_ONLY", "NEVER")
    val markerStyles =
        listOf(
            SettingsRepository.MARKER_STYLE_DOT,
            SettingsRepository.MARKER_STYLE_TRIANGLE,
        )
    val zoomButtonModes =
        listOf(
            SettingsRepository.ZOOM_BUTTONS_BOTH,
            SettingsRepository.ZOOM_BUTTONS_HIDE_BOTH,
            SettingsRepository.ZOOM_BUTTONS_HIDE_PLUS,
        )
    val mapLabelSizes =
        listOf(
            SettingsRepository.MAP_LABEL_SIZE_SMALL,
            SettingsRepository.MAP_LABEL_SIZE_DEFAULT,
            SettingsRepository.MAP_LABEL_SIZE_LARGE,
            SettingsRepository.MAP_LABEL_SIZE_EXTRA_LARGE,
        )
    val timeFormats =
        listOf(
            SettingsRepository.TIME_FORMAT_24_HOUR,
            SettingsRepository.TIME_FORMAT_12_HOUR,
        )
    val timeFormatOptions =
        remember {
            timeFormats.map { it to timeFormatLabel(it) }
        }
    val northIndicatorOptions =
        remember {
            northIndicatorModes.map { it to northIndicatorModeLabel(it) }
        }
    val markerStyleOptions =
        remember {
            markerStyles.map { it to markerStyleLabel(it) }
        }
    val zoomButtonOptions =
        remember {
            zoomButtonModes.map { it to zoomButtonsModeLabel(it) }
        }
    val mapLabelSizeOptions =
        remember {
            mapLabelSizes.map { it to mapLabelSizeLabel(it) }
        }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }
        item {
            SettingsToggleChip(
                checked = showTimeInNavigate,
                onCheckedChanged = { viewModel.setShowTimeInNavigate(it) },
                label = "Show time on map",
                modifier = Modifier.testTag(TAG_MAP_DISPLAY_SHOW_TIME_CHIP),
            )
        }
        if (showTimeInNavigate) {
            item {
                SettingsOptionPickerRow(
                    label = "Time format",
                    selectedValue = navigateTimeFormat,
                    options = timeFormatOptions,
                    secondaryLabel = timeFormatLabel(navigateTimeFormat),
                    onSelect = viewModel::setNavigateTimeFormat,
                )
            }
        }
        item {
            SettingsOptionPickerRow(
                label = "North indicator",
                selectedValue = northIndicatorMode,
                options = northIndicatorOptions,
                secondaryLabel = northIndicatorModeLabel(northIndicatorMode),
                onSelect = viewModel::setNorthIndicatorMode,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "Zoom buttons",
                selectedValue = mapZoomButtonsMode,
                options = zoomButtonOptions,
                secondaryLabel = zoomButtonsModeLabel(mapZoomButtonsMode),
                onSelect = viewModel::setMapZoomButtonsMode,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "Map label size",
                selectedValue = mapLabelSize,
                options = mapLabelSizeOptions,
                secondaryLabel = mapLabelSizeLabel(mapLabelSize),
                onSelect = viewModel::setMapLabelSize,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = "Marker style",
                selectedValue = navigationMarkerStyle,
                options = markerStyleOptions,
                secondaryLabel = markerStyleLabel(navigationMarkerStyle),
                onSelect = viewModel::setNavigationMarkerStyle,
            )
        }
        item {
            SettingsToggleChip(
                checked = gpsAccuracyCircleEnabled,
                onCheckedChanged = { viewModel.setGpsAccuracyCircleEnabled(it) },
                label = "GPS accuracy circle",
                secondaryLabel = "Show uncertainty radius",
            )
        }
    }
}

internal const val TAG_MAP_DISPLAY_SHOW_TIME_CHIP = "map_display_show_time_chip"

private fun timeFormatLabel(format: String): String =
    when (format) {
        SettingsRepository.TIME_FORMAT_24_HOUR -> "24-hour"
        SettingsRepository.TIME_FORMAT_12_HOUR -> "12-hour"
        else -> "24-hour"
    }

private fun markerStyleLabel(style: String): String =
    when (style) {
        SettingsRepository.MARKER_STYLE_DOT -> "Dot + cone"
        SettingsRepository.MARKER_STYLE_TRIANGLE -> "Arrow"
        else -> "Dot + cone"
    }

private fun northIndicatorModeLabel(mode: String): String =
    when (mode) {
        "ALWAYS" -> "Always"
        "COMPASS_ONLY" -> "Compass only"
        "NORTH_UP_ONLY" -> "North-up only"
        "NEVER" -> "Never"
        else ->
            mode
                .replace("_", " ")
                .lowercase()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun zoomButtonsModeLabel(mode: String): String =
    when (mode) {
        SettingsRepository.ZOOM_BUTTONS_HIDE_BOTH -> "Hide + and -"
        SettingsRepository.ZOOM_BUTTONS_HIDE_PLUS -> "Hide + only"
        else -> "Show + and -"
    }

private fun mapLabelSizeLabel(size: String): String =
    when (size) {
        SettingsRepository.MAP_LABEL_SIZE_SMALL -> "Small (80%)"
        SettingsRepository.MAP_LABEL_SIZE_LARGE -> "Large (150%)"
        SettingsRepository.MAP_LABEL_SIZE_EXTRA_LARGE -> "Extra large (175%)"
        else -> "Default (100%)"
    }
