@file:Suppress("FunctionName", "FunctionNaming", "LongMethod")

package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

@Composable
fun MapDisplaySettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val resources = LocalResources.current
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
            timeFormats.map { it to timeFormatLabel(it, resources = resources) }
        }
    val northIndicatorOptions =
        remember {
            northIndicatorModes.map { it to northIndicatorModeLabel(it, resources = resources) }
        }
    val markerStyleOptions =
        remember {
            markerStyles.map { it to markerStyleLabel(it, resources = resources) }
        }
    val zoomButtonOptions =
        remember {
            zoomButtonModes.map { it to zoomButtonsModeLabel(it, resources = resources) }
        }
    val mapLabelSizeOptions =
        remember {
            mapLabelSizes.map { it to mapLabelSizeLabel(it, resources = resources) }
        }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }
        item {
            SettingsToggleChip(
                checked = showTimeInNavigate,
                onCheckedChanged = { viewModel.setShowTimeInNavigate(it) },
                label = stringResource(R.string.settings_show_time_on_map),
                modifier = Modifier.testTag(TAG_MAP_DISPLAY_SHOW_TIME_CHIP),
            )
        }
        if (showTimeInNavigate) {
            item {
                SettingsOptionPickerRow(
                    label = stringResource(R.string.settings_time_format),
                    selectedValue = navigateTimeFormat,
                    options = timeFormatOptions,
                    secondaryLabel = timeFormatLabel(navigateTimeFormat, resources = resources),
                    onSelect = viewModel::setNavigateTimeFormat,
                )
            }
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_north_indicator),
                selectedValue = northIndicatorMode,
                options = northIndicatorOptions,
                secondaryLabel = northIndicatorModeLabel(northIndicatorMode, resources = resources),
                onSelect = viewModel::setNorthIndicatorMode,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_zoom_buttons),
                selectedValue = mapZoomButtonsMode,
                options = zoomButtonOptions,
                secondaryLabel = zoomButtonsModeLabel(mapZoomButtonsMode, resources = resources),
                onSelect = viewModel::setMapZoomButtonsMode,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_map_label_size),
                selectedValue = mapLabelSize,
                options = mapLabelSizeOptions,
                secondaryLabel = mapLabelSizeLabel(mapLabelSize, resources = resources),
                onSelect = viewModel::setMapLabelSize,
            )
        }
        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_marker_style),
                selectedValue = navigationMarkerStyle,
                options = markerStyleOptions,
                secondaryLabel = markerStyleLabel(navigationMarkerStyle, resources = resources),
                onSelect = viewModel::setNavigationMarkerStyle,
            )
        }
        item {
            SettingsToggleChip(
                checked = gpsAccuracyCircleEnabled,
                onCheckedChanged = { viewModel.setGpsAccuracyCircleEnabled(it) },
                label = stringResource(R.string.settings_gps_accuracy_circle),
                secondaryLabel = stringResource(R.string.settings_show_uncertainty_radius),
            )
        }
    }
}

internal const val TAG_MAP_DISPLAY_SHOW_TIME_CHIP = "map_display_show_time_chip"

private fun timeFormatLabel(
    format: String,
    resources: Resources,
): String =
    when (format) {
        SettingsRepository.TIME_FORMAT_24_HOUR -> resources.getString(R.string.settings_24_hour)
        SettingsRepository.TIME_FORMAT_12_HOUR -> resources.getString(R.string.settings_12_hour)
        else -> resources.getString(R.string.settings_24_hour)
    }

private fun markerStyleLabel(
    style: String,
    resources: Resources,
): String =
    when (style) {
        SettingsRepository.MARKER_STYLE_DOT -> resources.getString(R.string.settings_dot_cone)
        SettingsRepository.MARKER_STYLE_TRIANGLE -> resources.getString(R.string.settings_arrow)
        else -> resources.getString(R.string.settings_dot_cone)
    }

private fun northIndicatorModeLabel(
    mode: String,
    resources: Resources,
): String =
    when (mode) {
        "ALWAYS" -> resources.getString(R.string.settings_always)
        "COMPASS_ONLY" -> resources.getString(R.string.settings_compass_only)
        "NORTH_UP_ONLY" -> resources.getString(R.string.settings_north_up_only)
        "NEVER" -> resources.getString(R.string.settings_never)
        else ->
            mode
                .replace("_", " ")
                .lowercase()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun zoomButtonsModeLabel(
    mode: String,
    resources: Resources,
): String =
    when (mode) {
        SettingsRepository.ZOOM_BUTTONS_HIDE_BOTH -> resources.getString(R.string.settings_hide_and)
        SettingsRepository.ZOOM_BUTTONS_HIDE_PLUS -> resources.getString(R.string.settings_hide_only)
        else -> resources.getString(R.string.settings_show_and)
    }

private fun mapLabelSizeLabel(
    size: String,
    resources: Resources,
): String =
    when (size) {
        SettingsRepository.MAP_LABEL_SIZE_SMALL -> resources.getString(R.string.settings_small_80)
        SettingsRepository.MAP_LABEL_SIZE_LARGE -> resources.getString(R.string.settings_large_150)
        SettingsRepository.MAP_LABEL_SIZE_EXTRA_LARGE -> resources.getString(R.string.settings_extra_large_175)
        else -> resources.getString(R.string.settings_default_100)
    }
