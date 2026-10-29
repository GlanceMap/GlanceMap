@file:Suppress("FunctionName", "FunctionNaming", "LongMethod")

package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
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
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.maps.mapZoomScaleStepsMeters
import com.glancemap.glancemapwearos.core.maps.nearestMetricScaleStepIndex
import com.glancemap.glancemapwearos.core.maps.sanitizeMapZoomScaleMeters
import kotlin.math.roundToInt

@Composable
fun MapZoomSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenGeneralSettings: () -> Unit,
) {
    val listTokens = rememberSettingsListTokens()
    val crownZoomEnabled by viewModel.crownZoomEnabled.collectAsState()
    val mapPinchZoomEnabled by viewModel.mapPinchZoomEnabled.collectAsState()
    val crownZoomInverted by viewModel.crownZoomInverted.collectAsState()
    val zoomDefaultScaleMeters by viewModel.mapZoomDefaultScaleMeters.collectAsState()
    val zoomMinScaleMeters by viewModel.mapZoomMinScaleMeters.collectAsState()
    val zoomMaxScaleMeters by viewModel.mapZoomMaxScaleMeters.collectAsState()
    val isMetric by viewModel.isMetric.collectAsState()

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }
        item {
            SettingsToggleChip(
                checked = crownZoomEnabled,
                onCheckedChanged = { viewModel.setCrownZoomEnabled(it) },
                label = stringResource(R.string.settings_crown_zoom),
                secondaryLabel =
                    if (crownZoomEnabled) {
                        stringResource(
                            R.string.settings_enabled,
                        )
                    } else {
                        stringResource(R.string.settings_disabled)
                    },
            )
        }
        item {
            SettingsToggleChip(
                checked = mapPinchZoomEnabled,
                onCheckedChanged = { viewModel.setMapPinchZoomEnabled(it) },
                label = stringResource(R.string.settings_pinch_to_zoom),
                secondaryLabel =
                    if (mapPinchZoomEnabled) {
                        stringResource(
                            R.string.settings_enabled,
                        )
                    } else {
                        stringResource(R.string.settings_disabled)
                    },
            )
        }
        if (crownZoomEnabled) {
            item {
                SettingsOptionPickerRow(
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.settings_crown_direction),
                    selectedValue = crownZoomInverted,
                    options =
                        listOf(
                            false to stringResource(R.string.settings_normal),
                            true to stringResource(R.string.settings_inverted),
                        ),
                    secondaryLabel =
                        if (crownZoomInverted) {
                            stringResource(
                                R.string.settings_inverted,
                            )
                        } else {
                            stringResource(R.string.settings_normal)
                        },
                    onSelect = viewModel::setCrownZoomInverted,
                )
            }
        }
        item {
            ScaleDistanceSetting(
                label = stringResource(R.string.settings_default_scale),
                value = zoomDefaultScaleMeters,
                isMetric = isMetric,
                onValueChange = viewModel::setMapZoomDefaultScaleMeters,
            )
        }
        item {
            ScaleDistanceSetting(
                label = stringResource(R.string.settings_farthest_out),
                value = zoomMinScaleMeters,
                isMetric = isMetric,
                onValueChange = viewModel::setMapZoomMinScaleMeters,
            )
        }
        item {
            ScaleDistanceSetting(
                label = stringResource(R.string.settings_closest_in),
                value = zoomMaxScaleMeters,
                isMetric = isMetric,
                onValueChange = viewModel::setMapZoomMaxScaleMeters,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ScaleDistanceSetting(
    label: String,
    value: Int,
    isMetric: Boolean,
    onValueChange: (Int) -> Unit,
) {
    val resources = LocalResources.current
    val sanitizedValue = sanitizeMapZoomScaleMeters(value)
    var internalValue by remember(sanitizedValue) {
        mutableStateOf(nearestMetricScaleStepIndex(sanitizedValue).toFloat())
    }
    val selectedIndex =
        internalValue
            .roundToInt()
            .coerceIn(0, mapZoomScaleStepsMeters.lastIndex)
    val selectedScaleMeters = mapZoomScaleStepsMeters[selectedIndex]
    val scaleText =
        remember(selectedScaleMeters, isMetric) {
            formatScaleDistance(selectedScaleMeters.toDouble(), isMetric, resources = resources)
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.settings_label_value, label, scaleText),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = internalValue,
            onValueChange = {
                val nextIndex =
                    it
                        .roundToInt()
                        .coerceIn(0, mapZoomScaleStepsMeters.lastIndex)
                internalValue = nextIndex.toFloat()
                onValueChange(mapZoomScaleStepsMeters[nextIndex])
            },
            valueRange = 0f..mapZoomScaleStepsMeters.lastIndex.toFloat(),
            steps = (mapZoomScaleStepsMeters.size - 2).coerceAtLeast(0),
            increaseIcon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_increase)) },
            decreaseIcon = { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.action_decrease)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val ImperialScaleStepsFeet =
    doubleArrayOf(
        20.0,
        50.0,
        100.0,
        150.0,
        200.0,
        250.0,
        300.0,
        500.0,
        800.0,
        1000.0,
        2000.0,
        3000.0,
        5000.0,
        8000.0,
    )

private val ImperialScaleStepsMiles =
    doubleArrayOf(
        0.1,
        0.2,
        0.5,
        1.0,
        2.0,
        5.0,
        10.0,
        20.0,
        25.0,
        30.0,
        40.0,
        50.0,
        80.0,
        100.0,
        200.0,
        250.0,
        500.0,
        1000.0,
        2000.0,
        2500.0,
        5000.0,
    )

private fun formatScaleDistance(
    meters: Double,
    isMetric: Boolean,
    resources: Resources,
): String {
    val roundedMeters = chooseScaleDistanceMeters(meters, isMetric)
    if (isMetric) {
        return if (roundedMeters >= 1000.0) {
            val km = roundedMeters / 1000.0
            if (km >= 10.0) {
                resources.getString(R.string.gpx_km, km.toInt())
            } else {
                resources.getString(R.string.settings_km, km)
            }
        } else {
            resources.getString(R.string.settings_m, roundedMeters.toInt())
        }
    }

    val feet = roundedMeters * 3.28084
    return if (feet < 2640.0) {
        resources.getString(R.string.settings_ft, feet.toInt())
    } else {
        val miles = roundedMeters * 0.000621371
        if (miles >= 10.0) {
            resources.getString(R.string.settings_mi, miles.toInt())
        } else {
            resources.getString(R.string.settings_mi_2, miles)
        }
    }
}

private fun chooseScaleDistanceMeters(
    targetMeters: Double,
    isMetric: Boolean,
): Double {
    if (targetMeters <= 0.0 || !targetMeters.isFinite()) return 0.0

    if (isMetric) {
        return pickLargestNotExceeding(mapZoomScaleStepsMeters, targetMeters)
    }

    val targetFeet = targetMeters * 3.28084
    return if (targetFeet < 2640.0) {
        val feet = pickLargestNotExceeding(ImperialScaleStepsFeet, targetFeet)
        feet / 3.28084
    } else {
        val targetMiles = targetMeters * 0.000621371
        val miles = pickLargestNotExceeding(ImperialScaleStepsMiles, targetMiles)
        miles / 0.000621371
    }
}

private fun pickLargestNotExceeding(
    steps: DoubleArray,
    target: Double,
): Double {
    var candidate = steps.firstOrNull() ?: target
    for (step in steps) {
        if (step <= target) candidate = step else break
    }
    return candidate
}

private fun pickLargestNotExceeding(
    steps: List<Int>,
    target: Double,
): Double {
    var candidate = steps.firstOrNull()?.toDouble() ?: target
    for (step in steps) {
        val stepMeters = step.toDouble()
        if (stepMeters <= target) candidate = stepMeters else break
    }
    return candidate
}
