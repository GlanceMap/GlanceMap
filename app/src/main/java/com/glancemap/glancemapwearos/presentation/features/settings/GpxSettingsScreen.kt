package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterDefaults
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import kotlin.math.roundToInt

private const val KMPH_TO_MPS = 1f / 3.6f
private const val MPS_TO_KMPH = 3.6f
private const val MPS_TO_MPH = 2.2369363f
private const val MPH_TO_MPS = 1f / MPS_TO_MPH
private const val METER_TO_FOOT = 3.28084f
private const val FOOT_TO_METER = 1f / METER_TO_FOOT
private const val VERTICAL_RATE_STEP_METERS_PER_HOUR = 50f
private const val VERTICAL_RATE_STEP_FEET_PER_HOUR = 100f

@Composable
fun GpxSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
    onOpenTurnByTurnSettings: () -> Unit,
    onOpenGpxToolsSettings: () -> Unit,
    onOpenGpxAppearanceSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val listTokens = rememberSettingsListTokens()
    val isGpxInspectionEnabled by viewModel.isGpxInspectionEnabled.collectAsState()
    val gpxFlatSpeedMps by viewModel.gpxFlatSpeedMps.collectAsState()
    val gpxAdvancedEtaEnabled by viewModel.gpxAdvancedEtaEnabled.collectAsState()
    val gpxStaminaAdjustmentEnabled by viewModel.gpxStaminaAdjustmentEnabled.collectAsState()
    val gpxUphillVerticalMetersPerHour by viewModel.gpxUphillVerticalMetersPerHour.collectAsState()
    val gpxDownhillVerticalMetersPerHour by viewModel.gpxDownhillVerticalMetersPerHour.collectAsState()
    val gpxElevationSmoothingDistanceMeters by viewModel.gpxElevationSmoothingDistanceMeters.collectAsState()
    val gpxElevationNeutralDiffThresholdMeters by viewModel.gpxElevationNeutralDiffThresholdMeters.collectAsState()
    val gpxElevationTrendActivationThresholdMeters by viewModel.gpxElevationTrendActivationThresholdMeters
        .collectAsState()
    val gpxElevationAutoAdjustPerGpx by viewModel.gpxElevationAutoAdjustPerGpx.collectAsState()
    val isMetric by viewModel.isMetric.collectAsState()
    var showAdvancedElevationFilter by remember { mutableStateOf(false) }
    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }

        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_turn_by_turn_settings),
                secondaryLabel = stringResource(R.string.settings_guidance_alerts_and_dashboard),
                onClick = onOpenTurnByTurnSettings,
            )
        }

        item {
            SettingsSectionChip(
                label = stringResource(R.string.gpx_gpx_tools_settings),
                secondaryLabel = stringResource(R.string.settings_route_creation_defaults),
                onClick = onOpenGpxToolsSettings,
            )
        }

        item {
            SettingsSectionChip(
                label = stringResource(R.string.settings_gpx_appearance),
                secondaryLabel = stringResource(R.string.settings_track_color_and_width),
                onClick = onOpenGpxAppearanceSettings,
            )
        }

        item {
            SettingsToggleChip(
                checked = isGpxInspectionEnabled,
                onCheckedChanged = { viewModel.setGpxInspectionEnabled(it) },
                label = stringResource(R.string.settings_route_analyzer),
                secondaryLabel = stringResource(R.string.settings_long_press_on_track),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            FlatSpeedSetting(
                speedMps = gpxFlatSpeedMps,
                isMetric = isMetric,
                onSpeedChange = viewModel::setGpxFlatSpeedMps,
            )
        }
        item {
            SettingsToggleChip(
                checked = gpxStaminaAdjustmentEnabled,
                onCheckedChanged = viewModel::setGpxStaminaAdjustmentEnabled,
                label = stringResource(R.string.settings_long_route_adjustment),
                secondaryLabel =
                    if (gpxStaminaAdjustmentEnabled) {
                        stringResource(R.string.settings_reduce_speed_on_long_routes)
                    } else {
                        stringResource(R.string.settings_use_the_same_flat_speed)
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SettingsToggleChip(
                checked = gpxAdvancedEtaEnabled,
                onCheckedChanged = viewModel::setGpxAdvancedEtaEnabled,
                label = stringResource(R.string.settings_advanced_eta),
                secondaryLabel =
                    if (gpxAdvancedEtaEnabled) {
                        stringResource(R.string.settings_use_uphill_downhill_rates)
                    } else {
                        stringResource(R.string.settings_flat_speed_only)
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (gpxAdvancedEtaEnabled) {
            item {
                AdjustableElevationFilterSetting(
                    label = stringResource(R.string.settings_uphill_rate),
                    valueText =
                        formatVerticalRate(
                            metersPerHour = gpxUphillVerticalMetersPerHour,
                            isMetric = isMetric,
                            resources = resources,
                        ),
                    canDecrease =
                        gpxUphillVerticalMetersPerHour >
                            SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR,
                    canIncrease =
                        gpxUphillVerticalMetersPerHour <
                            SettingsRepository.MAX_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
                    onDecrease = {
                        viewModel.setGpxUphillVerticalMetersPerHour(
                            stepVerticalRateMetersPerHour(
                                currentMetersPerHour = gpxUphillVerticalMetersPerHour,
                                isMetric = isMetric,
                                increase = false,
                                minMetersPerHour = SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR,
                                maxMetersPerHour = SettingsRepository.MAX_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
                            ),
                        )
                    },
                    onIncrease = {
                        viewModel.setGpxUphillVerticalMetersPerHour(
                            stepVerticalRateMetersPerHour(
                                currentMetersPerHour = gpxUphillVerticalMetersPerHour,
                                isMetric = isMetric,
                                increase = true,
                                minMetersPerHour = SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR,
                                maxMetersPerHour = SettingsRepository.MAX_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
                            ),
                        )
                    },
                )
            }
            item {
                AdjustableElevationFilterSetting(
                    label = stringResource(R.string.settings_downhill_rate),
                    valueText =
                        formatVerticalRate(
                            metersPerHour = gpxDownhillVerticalMetersPerHour,
                            isMetric = isMetric,
                            resources = resources,
                        ),
                    canDecrease =
                        gpxDownhillVerticalMetersPerHour >
                            SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR,
                    canIncrease =
                        gpxDownhillVerticalMetersPerHour <
                            SettingsRepository.MAX_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
                    onDecrease = {
                        viewModel.setGpxDownhillVerticalMetersPerHour(
                            stepVerticalRateMetersPerHour(
                                currentMetersPerHour = gpxDownhillVerticalMetersPerHour,
                                isMetric = isMetric,
                                increase = false,
                                minMetersPerHour = SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR,
                                maxMetersPerHour = SettingsRepository.MAX_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
                            ),
                        )
                    },
                    onIncrease = {
                        viewModel.setGpxDownhillVerticalMetersPerHour(
                            stepVerticalRateMetersPerHour(
                                currentMetersPerHour = gpxDownhillVerticalMetersPerHour,
                                isMetric = isMetric,
                                increase = true,
                                minMetersPerHour = SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR,
                                maxMetersPerHour = SettingsRepository.MAX_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
                            ),
                        )
                    },
                )
            }
            item {
                Text(
                    text =
                        stringResource(
                            R.string.settings_flat_speed_still_drives_easy_terrain_uphill_and_downhill_rates_only_li,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                )
            }
        }
        item {
            Text(
                text =
                    stringResource(
                        R.string.settings_fine_tune_how_gpx_ascent_and_descent_are_calculated_most_tracks_can_st,
                    ),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
            )
        }
        item {
            SettingsToggleChip(
                checked = showAdvancedElevationFilter,
                onCheckedChanged = { showAdvancedElevationFilter = it },
                label = stringResource(R.string.settings_advanced_filter),
                secondaryLabel = stringResource(R.string.settings_tune_gpx_elevation_totals),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (showAdvancedElevationFilter) {
            item {
                SettingsToggleChip(
                    checked = gpxElevationAutoAdjustPerGpx,
                    onCheckedChanged = viewModel::setGpxElevationAutoAdjustPerGpx,
                    label = stringResource(R.string.settings_auto_adjust_per_gpx),
                    secondaryLabel =
                        if (gpxElevationAutoAdjustPerGpx) {
                            stringResource(R.string.settings_use_sliders_as_baseline)
                        } else {
                            stringResource(R.string.settings_apply_sliders_exactly)
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                AdjustableElevationFilterSetting(
                    label = stringResource(GpxElevationFilterUi.SMOOTHING_LABEL),
                    valueText =
                        GpxElevationFilterUi.formatSmoothingDistance(
                            gpxElevationSmoothingDistanceMeters,
                            resources = resources,
                        ),
                    canDecrease =
                        gpxElevationSmoothingDistanceMeters >
                            GpxElevationFilterDefaults.MIN_SMOOTHING_DISTANCE_METERS,
                    canIncrease =
                        gpxElevationSmoothingDistanceMeters <
                            GpxElevationFilterDefaults.MAX_SMOOTHING_DISTANCE_METERS,
                    onDecrease = {
                        viewModel.setGpxElevationSmoothingDistanceMeters(
                            gpxElevationSmoothingDistanceMeters -
                                GpxElevationFilterDefaults.STEP_SMOOTHING_DISTANCE_METERS,
                        )
                    },
                    onIncrease = {
                        viewModel.setGpxElevationSmoothingDistanceMeters(
                            gpxElevationSmoothingDistanceMeters +
                                GpxElevationFilterDefaults.STEP_SMOOTHING_DISTANCE_METERS,
                        )
                    },
                )
            }
            item {
                AdjustableElevationFilterSetting(
                    label = stringResource(GpxElevationFilterUi.NOISE_THRESHOLD_LABEL),
                    valueText =
                        GpxElevationFilterUi.formatThreshold(
                            gpxElevationNeutralDiffThresholdMeters,
                            resources = resources,
                        ),
                    canDecrease =
                        gpxElevationNeutralDiffThresholdMeters >
                            GpxElevationFilterDefaults.MIN_NEUTRAL_DIFF_THRESHOLD_METERS,
                    canIncrease =
                        gpxElevationNeutralDiffThresholdMeters <
                            GpxElevationFilterDefaults.MAX_NEUTRAL_DIFF_THRESHOLD_METERS,
                    onDecrease = {
                        viewModel.setGpxElevationNeutralDiffThresholdMeters(
                            gpxElevationNeutralDiffThresholdMeters -
                                GpxElevationFilterDefaults.STEP_NEUTRAL_DIFF_THRESHOLD_METERS,
                        )
                    },
                    onIncrease = {
                        viewModel.setGpxElevationNeutralDiffThresholdMeters(
                            gpxElevationNeutralDiffThresholdMeters +
                                GpxElevationFilterDefaults.STEP_NEUTRAL_DIFF_THRESHOLD_METERS,
                        )
                    },
                )
            }
            item {
                AdjustableElevationFilterSetting(
                    label = stringResource(GpxElevationFilterUi.TREND_THRESHOLD_LABEL),
                    valueText =
                        GpxElevationFilterUi.formatThreshold(
                            gpxElevationTrendActivationThresholdMeters,
                            resources = resources,
                        ),
                    canDecrease =
                        gpxElevationTrendActivationThresholdMeters >
                            GpxElevationFilterDefaults.MIN_TREND_ACTIVATION_THRESHOLD_METERS,
                    canIncrease =
                        gpxElevationTrendActivationThresholdMeters <
                            GpxElevationFilterDefaults.MAX_TREND_ACTIVATION_THRESHOLD_METERS,
                    onDecrease = {
                        viewModel.setGpxElevationTrendActivationThresholdMeters(
                            gpxElevationTrendActivationThresholdMeters -
                                GpxElevationFilterDefaults.STEP_TREND_ACTIVATION_THRESHOLD_METERS,
                        )
                    },
                    onIncrease = {
                        viewModel.setGpxElevationTrendActivationThresholdMeters(
                            gpxElevationTrendActivationThresholdMeters +
                                GpxElevationFilterDefaults.STEP_TREND_ACTIVATION_THRESHOLD_METERS,
                        )
                    },
                )
            }
            item {
                Text(
                    text =
                        if (gpxElevationAutoAdjustPerGpx) {
                            stringResource(R.string.settings_auto_adjust_can_nudge_these_values_depending_on_the_gpx)
                        } else {
                            stringResource(R.string.settings_these_values_are_applied_exactly_to_every_gpx)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
fun GpxAppearanceSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGpxSettings: () -> Unit,
) {
    val screenSize = rememberWearScreenSize()
    val listTokens = rememberSettingsListTokens()
    val trackColor by viewModel.gpxTrackColor.collectAsState()
    val trackColorMode by viewModel.gpxTrackColorMode.collectAsState()
    val trackWidth by viewModel.gpxTrackWidth.collectAsState()
    val trackOpacityPercent by viewModel.gpxTrackOpacityPercent.collectAsState()
    val trackDirectionArrowsEnabled by viewModel.gpxTrackDirectionArrowsEnabled.collectAsState()
    val trackColorModeOptions =
        listOf(
            SettingsRepository.GPX_TRACK_COLOR_MODE_SOLID to stringResource(R.string.settings_solid_color),
            SettingsRepository.GPX_TRACK_COLOR_MODE_ELEVATION to stringResource(R.string.common_elevation),
        )

    val colorPalette =
        listOf(
            Color.Magenta,
            Color.Blue,
            Color(0xFFFFA500),
            Color.Red,
        )

    val groupSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 7.dp
            WearScreenSize.SMALL -> 6.dp
        }
    val colorPickerSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.dp
            WearScreenSize.MEDIUM -> 8.dp
            WearScreenSize.SMALL -> 6.dp
        }
    val colorButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 32.dp
            WearScreenSize.MEDIUM -> 30.dp
            WearScreenSize.SMALL -> 28.dp
        }
    val selectedIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 16.dp
            WearScreenSize.MEDIUM -> 14.dp
            WearScreenSize.SMALL -> 12.dp
        }
    val trackWidthSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 4.dp
            WearScreenSize.MEDIUM -> 3.dp
            WearScreenSize.SMALL -> 2.dp
        }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GpxSettingsShortcutChip(
                onClick = onOpenGpxSettings,
            )
        }

        item {
            SettingsOptionPickerRow(
                label = stringResource(R.string.settings_color_mode),
                selectedValue = trackColorMode,
                options = trackColorModeOptions,
                secondaryLabel =
                    when (trackColorMode) {
                        SettingsRepository.GPX_TRACK_COLOR_MODE_ELEVATION -> stringResource(R.string.common_elevation)
                        else -> stringResource(R.string.settings_solid_color)
                    },
                onSelect = viewModel::setGpxTrackColorMode,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trackColorMode == SettingsRepository.GPX_TRACK_COLOR_MODE_SOLID) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(groupSpacing),
                ) {
                    Text(
                        text = stringResource(R.string.settings_track_color),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    SimpleColorPicker(
                        colors = colorPalette,
                        selectedColor = Color(trackColor),
                        itemSpacing = colorPickerSpacing,
                        buttonSize = colorButtonSize,
                        selectedIconSize = selectedIconSize,
                        onColorSelected = { color ->
                            viewModel.setGpxTrackColor(color.toArgb())
                        },
                    )
                }
            }
        }
        item {
            SettingsToggleChip(
                checked = trackDirectionArrowsEnabled,
                onCheckedChanged = viewModel::setGpxTrackDirectionArrowsEnabled,
                label = stringResource(R.string.settings_track_direction),
                secondaryLabel =
                    if (trackDirectionArrowsEnabled) {
                        stringResource(
                            R.string.settings_hide_arrows,
                        )
                    } else {
                        stringResource(R.string.settings_show_arrows)
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            TrackWidthSetting(
                label = stringResource(R.string.settings_track_width),
                value = trackWidth,
                onValueChange = viewModel::setGpxTrackWidth,
                spacing = trackWidthSpacing,
            )
        }
        item {
            TrackOpacitySetting(
                label = stringResource(R.string.settings_track_opacity),
                valuePercent = trackOpacityPercent,
                onValueChange = viewModel::setGpxTrackOpacityPercent,
                spacing = trackWidthSpacing,
            )
        }
    }
}

@Composable
private fun SimpleColorPicker(
    colors: List<Color>,
    selectedColor: Color,
    itemSpacing: Dp,
    buttonSize: Dp,
    selectedIconSize: Dp,
    onColorSelected: (Color) -> Unit,
) {
    val selectionRingWidth = 2.dp
    val selectionRingInset = 2.dp
    val swatchSlotSize = buttonSize + (selectionRingInset * 2)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { color ->
            val isSelected = color.toArgb() == selectedColor.toArgb()

            Box(
                modifier =
                    Modifier
                        .size(swatchSlotSize)
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    width = selectionRingWidth,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    shape = CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        ).padding(selectionRingInset)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onColorSelected(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.common_selected),
                        modifier = Modifier.size(selectedIconSize),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlatSpeedSetting(
    speedMps: Float,
    isMetric: Boolean,
    onSpeedChange: (Float) -> Unit,
) {
    val currentLocale = LocalLocale.current.platformLocale
    val maxDisplaySpeed =
        if (isMetric) {
            SettingsRepository.MAX_GPX_FLAT_SPEED_MPS * MPS_TO_KMPH
        } else {
            SettingsRepository.MAX_GPX_FLAT_SPEED_MPS * MPS_TO_MPH
        }
    val displayUnit = if (isMetric) stringResource(R.string.settings_km_h) else stringResource(R.string.settings_mph)
    val displaySpeed =
        if (isMetric) {
            speedMps * MPS_TO_KMPH
        } else {
            speedMps * MPS_TO_MPH
        }.coerceIn(0f, maxDisplaySpeed)

    var sliderValue by remember(speedMps, isMetric) { mutableStateOf(displaySpeed) }
    val sliderStep = 0.1f
    val steps = ((maxDisplaySpeed / sliderStep).roundToInt() - 1).coerceAtLeast(0)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text =
                String.format(
                    currentLocale,
                    stringResource(R.string.settings_avg_flat_speed),
                    sliderValue,
                    displayUnit,
                ),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = sliderValue,
            onValueChange = { rawValue ->
                val snapped =
                    ((rawValue / sliderStep).roundToInt() * sliderStep)
                        .coerceIn(0f, maxDisplaySpeed)
                sliderValue = snapped
                val speedMpsValue =
                    if (isMetric) {
                        snapped * KMPH_TO_MPS
                    } else {
                        snapped * MPH_TO_MPS
                    }
                onSpeedChange(speedMpsValue)
            },
            valueRange = 0f..maxDisplaySpeed,
            steps = steps,
            increaseIcon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_increase)) },
            decreaseIcon = { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.action_decrease)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TrackWidthSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    spacing: Dp,
) {
    var internalValue by remember(value) { mutableStateOf(value) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Text(
            text = stringResource(R.string.settings_dp, internalValue.toInt(), label),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = internalValue,
            onValueChange = {
                internalValue = it
                onValueChange(it)
            },
            valueRange = 1f..15f,
            steps = 13,
            increaseIcon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_increase)) },
            decreaseIcon = { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.action_decrease)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TrackOpacitySetting(
    label: String,
    valuePercent: Int,
    onValueChange: (Int) -> Unit,
    spacing: Dp,
) {
    var internalValue by remember(valuePercent) { mutableStateOf(valuePercent.toFloat()) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Text(
            text = stringResource(R.string.settings_label_percent, label, internalValue.roundToInt()),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = internalValue,
            onValueChange = {
                val snapped =
                    it.roundToInt().coerceIn(
                        SettingsRepository.MIN_GPX_TRACK_OPACITY_PERCENT,
                        SettingsRepository.MAX_GPX_TRACK_OPACITY_PERCENT,
                    )
                internalValue = snapped.toFloat()
                onValueChange(snapped)
            },
            valueRange =
                SettingsRepository.MIN_GPX_TRACK_OPACITY_PERCENT.toFloat()..SettingsRepository.MAX_GPX_TRACK_OPACITY_PERCENT.toFloat(),
            steps =
                (
                    SettingsRepository.MAX_GPX_TRACK_OPACITY_PERCENT -
                        SettingsRepository.MIN_GPX_TRACK_OPACITY_PERCENT - 1
                ).coerceAtLeast(0),
            increaseIcon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_increase)) },
            decreaseIcon = { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.action_decrease)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AdjustableElevationFilterSetting(
    label: String,
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDecrease,
                enabled = canDecrease,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = Color.Black.copy(alpha = 0.35f),
                        disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    ),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.action_decrease))
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = onIncrease,
                enabled = canIncrease,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = Color.Black.copy(alpha = 0.35f),
                        disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_increase))
            }
        }
    }
}

private fun formatVerticalRate(
    metersPerHour: Float,
    isMetric: Boolean,
    resources: Resources,
): String =
    if (isMetric) {
        resources.getString(R.string.settings_m_h, metersPerHour.roundToInt())
    } else {
        resources.getString(R.string.settings_ft_h, (metersPerHour * METER_TO_FOOT).roundToInt())
    }

private fun stepVerticalRateMetersPerHour(
    currentMetersPerHour: Float,
    isMetric: Boolean,
    increase: Boolean,
    minMetersPerHour: Float,
    maxMetersPerHour: Float,
): Float =
    if (isMetric) {
        val delta =
            if (increase) {
                VERTICAL_RATE_STEP_METERS_PER_HOUR
            } else {
                -VERTICAL_RATE_STEP_METERS_PER_HOUR
            }
        (currentMetersPerHour + delta).coerceIn(minMetersPerHour, maxMetersPerHour)
    } else {
        val currentFeetPerHour = currentMetersPerHour * METER_TO_FOOT
        val snappedFeetPerHour =
            (currentFeetPerHour / VERTICAL_RATE_STEP_FEET_PER_HOUR)
                .roundToInt() * VERTICAL_RATE_STEP_FEET_PER_HOUR
        val delta =
            if (increase) {
                VERTICAL_RATE_STEP_FEET_PER_HOUR
            } else {
                -VERTICAL_RATE_STEP_FEET_PER_HOUR
            }
        (
            (snappedFeetPerHour + delta)
                .coerceIn(
                    minMetersPerHour * METER_TO_FOOT,
                    maxMetersPerHour * METER_TO_FOOT,
                )
        ) * FOOT_TO_METER
    }
