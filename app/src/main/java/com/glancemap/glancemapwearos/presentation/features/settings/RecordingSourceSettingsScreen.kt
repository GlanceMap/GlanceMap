package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SplitSwitchButton
import androidx.wear.compose.material3.SwitchButtonDefaults
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

@Composable
fun RecordingSourceSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenRecordingSettings: () -> Unit,
    onOpenBikeSensorSettings: () -> Unit,
) {
    val resources = LocalResources.current
    val listTokens = rememberSettingsListTokens()
    val elevationSource by viewModel.recordingElevationSource.collectAsState()
    val heartRateSource by viewModel.recordingHeartRateSource.collectAsState()
    val cadenceSource by viewModel.recordingCadenceSource.collectAsState()
    val speedSource by viewModel.recordingSpeedSource.collectAsState()
    val distanceSource by viewModel.recordingDistanceSource.collectAsState()
    val stepsSource by viewModel.recordingStepsSource.collectAsState()
    val activityProfile by viewModel.activityProfile.collectAsState()
    val linkedHeartRateAddress by viewModel.recordingExternalHeartRateAddress.collectAsState()
    val linkedRunPodAddress by viewModel.recordingExternalRunPodAddress.collectAsState()
    val isBikeProfile = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    val linkedSensorLabel =
        if (isBikeProfile) {
            if (!linkedRunPodAddress.isNullOrBlank()) {
                stringResource(
                    R.string.settings_linked_bike_sensor,
                )
            } else {
                stringResource(R.string.settings_link_bike_sensor)
            }
        } else {
            if (!linkedRunPodAddress.isNullOrBlank()) {
                stringResource(
                    R.string.settings_linked_foot_pod,
                )
            } else {
                stringResource(R.string.settings_link_foot_pod)
            }
        }
    val elevationSourceOptions =
        RECORDING_ELEVATION_SOURCE_OPTIONS.map {
            it to
                recordingElevationSourceLabel(it, resources = resources)
        }
    val heartRateSourceOptions =
        RECORDING_HEART_RATE_SOURCE_OPTIONS.map {
            it to
                recordingHeartRateSourceLabel(it, resources = resources)
        }
    val sensorSourceOptions =
        RECORDING_SENSOR_SOURCE_OPTIONS.map {
            it to
                recordingSensorSourceLabel(it, isBikeProfile, resources = resources)
        }
    val cadenceSourceOptions =
        if (isBikeProfile) {
            listOf(SettingsRepository.RECORDING_SENSOR_SOURCE_POD to stringResource(R.string.settings_bike_sensor))
        } else {
            sensorSourceOptions
        }
    val stepsSourceOptions =
        RECORDING_STEPS_SOURCE_OPTIONS.map {
            it to
                recordingSensorSourceLabel(it, isBikeProfile, resources = resources)
        }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            RecordingSettingsShortcutChip(
                onClick = onOpenRecordingSettings,
            )
        }
        if (isBikeProfile) {
            item {
                SettingsSectionChip(
                    label = stringResource(R.string.settings_bike_sensor),
                    secondaryLabel = stringResource(R.string.settings_wheel_size_ble_setup),
                    iconImageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                    compactRoundWidthFraction = 0.86f,
                    onClick = onOpenBikeSensorSettings,
                )
            }
        }
        item {
            RecordingSourceSplitSetting(
                label = stringResource(R.string.common_elevation),
                source = elevationSource,
                defaultSource = SettingsRepository.DEFAULT_RECORDING_ELEVATION_SOURCE,
                options = elevationSourceOptions,
                secondaryLabel = recordingElevationSourceLabel(elevationSource, resources = resources),
                onSourceChange = viewModel::setRecordingElevationSource,
            )
        }
        item {
            RecordingSourceSplitSetting(
                label = stringResource(R.string.common_heart_rate),
                dialogTitle = stringResource(R.string.settings_hr_source),
                source = heartRateSource,
                defaultSource = SettingsRepository.DEFAULT_RECORDING_HEART_RATE_SOURCE,
                options = heartRateSourceOptions,
                secondaryLabel =
                    recordingHeartRateSourceSecondaryLabel(
                        heartRateSource,
                        !linkedHeartRateAddress.isNullOrBlank(),
                        resources = resources,
                    ),
                onSourceChange = viewModel::setRecordingHeartRateSource,
            )
        }
        item {
            RecordingSourceSplitSetting(
                label = stringResource(R.string.common_cadence),
                source = cadenceSource,
                defaultSource =
                    if (isBikeProfile) {
                        SettingsRepository.RECORDING_SENSOR_SOURCE_POD
                    } else {
                        SettingsRepository.DEFAULT_RECORDING_CADENCE_SOURCE
                    },
                options = cadenceSourceOptions,
                secondaryLabel =
                    if (isBikeProfile && cadenceSource != SettingsRepository.RECORDING_SENSOR_SOURCE_POD) {
                        stringResource(R.string.settings_bike_sensor_required)
                    } else {
                        recordingMetricSourceSecondaryLabel(
                            cadenceSource,
                            linkedSensorLabel,
                            resources = resources,
                        )
                    },
                onSourceChange = viewModel::setRecordingCadenceSource,
            )
        }
        item {
            RecordingSourceSplitSetting(
                label = stringResource(R.string.common_speed),
                source = speedSource,
                defaultSource = SettingsRepository.DEFAULT_RECORDING_SPEED_SOURCE,
                options = sensorSourceOptions,
                secondaryLabel =
                    recordingMetricSourceSecondaryLabel(
                        speedSource,
                        linkedSensorLabel,
                        resources = resources,
                    ),
                onSourceChange = viewModel::setRecordingSpeedSource,
            )
        }
        item {
            RecordingSourceSplitSetting(
                label = stringResource(R.string.common_distance),
                source = distanceSource,
                defaultSource = SettingsRepository.DEFAULT_RECORDING_DISTANCE_SOURCE,
                options = sensorSourceOptions,
                secondaryLabel =
                    recordingMetricSourceSecondaryLabel(
                        distanceSource,
                        linkedSensorLabel,
                        resources = resources,
                    ),
                onSourceChange = viewModel::setRecordingDistanceSource,
            )
        }
        if (!isBikeProfile) {
            item {
                RecordingSourceSplitSetting(
                    label = stringResource(R.string.settings_steps),
                    source = stepsSource,
                    defaultSource = SettingsRepository.DEFAULT_RECORDING_STEPS_SOURCE,
                    options = stepsSourceOptions,
                    secondaryLabel =
                        recordingMetricSourceSecondaryLabel(
                            stepsSource,
                            if (!linkedRunPodAddress.isNullOrBlank()) {
                                stringResource(
                                    R.string.settings_sensor_if_available,
                                )
                            } else {
                                stringResource(R.string.settings_link_sensor_first)
                            },
                            resources = resources,
                        ),
                    onSourceChange = viewModel::setRecordingStepsSource,
                )
            }
        }
    }
}

private val RECORDING_ELEVATION_SOURCE_OPTIONS =
    listOf(
        SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO,
        SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM,
        SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
    )
private val RECORDING_HEART_RATE_SOURCE_OPTIONS =
    listOf(
        SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH,
        SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP,
    )
private val RECORDING_SENSOR_SOURCE_OPTIONS =
    listOf(
        SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
        SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
    )
private val RECORDING_STEPS_SOURCE_OPTIONS =
    listOf(
        SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
    )

@Composable
// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("FunctionNaming", "LongParameterList")
private fun RecordingSourceSplitSetting(
    label: String,
    source: String,
    defaultSource: String,
    options: List<Pair<String, String>>,
    secondaryLabel: String,
    onSourceChange: (String) -> Unit,
    dialogTitle: String = stringResource(R.string.settings_source, label),
) {
    val enabled = source.isRecordingSourceEnabled()
    SettingsOptionPickerHost(
        title = dialogTitle,
        selectedValue = selectedRecordingSourceValue(source, enabled, options, defaultSource),
        options = options,
        onSelect = onSourceChange,
    ) { openPicker ->
        SplitSwitchButton(
            checked = enabled,
            onCheckedChange = { checked ->
                onSourceChange(
                    if (checked) {
                        defaultSource
                    } else {
                        SettingsRepository.RECORDING_SOURCE_DISABLED
                    },
                )
            },
            toggleContentDescription = stringResource(R.string.settings_use, label),
            onContainerClick = openPicker,
            containerClickLabel = stringResource(R.string.settings_choose_source, label),
            modifier = Modifier.fillMaxWidth(),
            colors =
                SwitchButtonDefaults.splitSwitchButtonColors(
                    checkedContainerColor = Color(0xFF5E6B7F),
                    checkedContentColor = Color.White,
                    checkedSecondaryContentColor = Color(0xFFE5E7EB),
                    checkedSplitContainerColor = Color.Black.copy(alpha = 0.10f),
                    uncheckedContainerColor = Color(0xFF2B2F36),
                    uncheckedContentColor = Color(0xFFF1F5FB),
                    uncheckedSecondaryContentColor = Color(0xFFBAC5D4),
                    uncheckedSplitContainerColor = Color.Black.copy(alpha = 0.18f),
                ),
            label = {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (options.size > 1) {
                    Icon(
                        imageVector = Icons.Filled.UnfoldMore,
                        contentDescription = stringResource(R.string.settings_choose_source, label),
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            secondaryLabel = {
                Text(
                    text = if (enabled) secondaryLabel else stringResource(R.string.glancemap_state_off),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

private fun selectedRecordingSourceValue(
    source: String,
    enabled: Boolean,
    options: List<Pair<String, String>>,
    defaultSource: String,
): String = source.takeIf { enabled && options.any { option -> option.first == source } } ?: defaultSource

private fun String.isRecordingSourceEnabled(): Boolean = this != SettingsRepository.RECORDING_SOURCE_DISABLED

private fun recordingElevationSourceLabel(
    source: String,
    resources: Resources,
): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> resources.getString(R.string.glancemap_state_off)
        SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM -> resources.getString(R.string.maps_dem)
        SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO -> resources.getString(R.string.settings_smart)
        else -> resources.getString(R.string.common_gps)
    }

private fun recordingHeartRateSourceLabel(
    source: String,
    resources: Resources,
): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> resources.getString(R.string.glancemap_state_off)
        SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP -> resources.getString(R.string.settings_strap)
        else -> resources.getString(R.string.settings_watch)
    }

private fun recordingSensorSourceLabel(
    source: String,
    isBikeProfile: Boolean,
    resources: Resources,
): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> resources.getString(R.string.glancemap_state_off)
        SettingsRepository.RECORDING_SENSOR_SOURCE_POD ->
            if (isBikeProfile) {
                resources.getString(
                    R.string.settings_bike_sensor,
                )
            } else {
                resources.getString(R.string.settings_foot_pod)
            }
        else -> resources.getString(R.string.settings_watch_gps)
    }

private fun recordingMetricSourceSecondaryLabel(
    source: String,
    podLabel: String,
    resources: Resources,
): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> resources.getString(R.string.glancemap_state_off)
        SettingsRepository.RECORDING_SENSOR_SOURCE_POD -> podLabel
        else -> resources.getString(R.string.settings_watch_gps)
    }

private fun recordingHeartRateSourceSecondaryLabel(
    source: String,
    hasLinkedStrap: Boolean,
    resources: Resources,
): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> resources.getString(R.string.glancemap_state_off)
        SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP ->
            if (hasLinkedStrap) {
                resources.getString(R.string.settings_linked_strap)
            } else {
                resources.getString(R.string.settings_link_strap_first)
            }
        else -> resources.getString(R.string.settings_watch_sensor)
    }
