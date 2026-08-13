package com.glancemap.glancemapwearos.presentation.features.settings

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SplitSwitchButton
import androidx.wear.compose.material3.SwitchButtonDefaults
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

@Composable
fun RecordingSourceSettingsScreen(
    viewModel: SettingsViewModel,
    onOpenRecordingSettings: () -> Unit,
    onOpenBikeSensorSettings: () -> Unit,
) {
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
            if (!linkedRunPodAddress.isNullOrBlank()) "Linked bike sensor" else "Link bike sensor"
        } else {
            if (!linkedRunPodAddress.isNullOrBlank()) "Linked foot pod" else "Link foot pod"
        }
    val elevationSourceOptions = RECORDING_ELEVATION_SOURCE_OPTIONS.map { it to recordingElevationSourceLabel(it) }
    val heartRateSourceOptions = RECORDING_HEART_RATE_SOURCE_OPTIONS.map { it to recordingHeartRateSourceLabel(it) }
    val sensorSourceOptions = RECORDING_SENSOR_SOURCE_OPTIONS.map { it to recordingSensorSourceLabel(it, isBikeProfile) }
    val cadenceSourceOptions =
        if (isBikeProfile) {
            listOf(SettingsRepository.RECORDING_SENSOR_SOURCE_POD to "Bike sensor")
        } else {
            sensorSourceOptions
        }
    val stepsSourceOptions = RECORDING_STEPS_SOURCE_OPTIONS.map { it to recordingSensorSourceLabel(it, isBikeProfile) }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            RecordingSettingsShortcutChip(
                onClick = onOpenRecordingSettings,
            )
        }
        if (isBikeProfile) {
            item {
                SettingsSectionChip(
                    label = "Bike sensor",
                    secondaryLabel = "Wheel size + BLE setup",
                    iconImageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                    compactRoundWidthFraction = 0.86f,
                    onClick = onOpenBikeSensorSettings,
                )
            }
        }
        item {
            RecordingSourceSplitSetting(
                label = "Elevation",
                source = elevationSource,
                defaultSource = SettingsRepository.DEFAULT_RECORDING_ELEVATION_SOURCE,
                options = elevationSourceOptions,
                secondaryLabel = recordingElevationSourceLabel(elevationSource),
                onSourceChange = viewModel::setRecordingElevationSource,
            )
        }
        item {
            RecordingSourceSplitSetting(
                label = "Heart rate",
                dialogTitle = "HR source",
                source = heartRateSource,
                defaultSource = SettingsRepository.DEFAULT_RECORDING_HEART_RATE_SOURCE,
                options = heartRateSourceOptions,
                secondaryLabel =
                    recordingHeartRateSourceSecondaryLabel(
                        heartRateSource,
                        !linkedHeartRateAddress.isNullOrBlank(),
                    ),
                onSourceChange = viewModel::setRecordingHeartRateSource,
            )
        }
        item {
            RecordingSourceSplitSetting(
                label = "Cadence",
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
                        "Bike sensor required"
                    } else {
                        recordingMetricSourceSecondaryLabel(
                            cadenceSource,
                            linkedSensorLabel,
                        )
                    },
                onSourceChange = viewModel::setRecordingCadenceSource,
            )
        }
        item {
            RecordingSourceSplitSetting(
                label = "Speed",
                source = speedSource,
                defaultSource = SettingsRepository.DEFAULT_RECORDING_SPEED_SOURCE,
                options = sensorSourceOptions,
                secondaryLabel =
                    recordingMetricSourceSecondaryLabel(
                        speedSource,
                        linkedSensorLabel,
                    ),
                onSourceChange = viewModel::setRecordingSpeedSource,
            )
        }
        item {
            RecordingSourceSplitSetting(
                label = "Distance",
                source = distanceSource,
                defaultSource = SettingsRepository.DEFAULT_RECORDING_DISTANCE_SOURCE,
                options = sensorSourceOptions,
                secondaryLabel =
                    recordingMetricSourceSecondaryLabel(
                        distanceSource,
                        linkedSensorLabel,
                    ),
                onSourceChange = viewModel::setRecordingDistanceSource,
            )
        }
        if (!isBikeProfile) {
            item {
                RecordingSourceSplitSetting(
                    label = "Steps",
                    source = stepsSource,
                    defaultSource = SettingsRepository.DEFAULT_RECORDING_STEPS_SOURCE,
                    options = stepsSourceOptions,
                    secondaryLabel =
                        recordingMetricSourceSecondaryLabel(
                            stepsSource,
                            if (!linkedRunPodAddress.isNullOrBlank()) "Sensor if available" else "Link sensor first",
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
private fun RecordingSourceSplitSetting(
    label: String,
    source: String,
    defaultSource: String,
    options: List<Pair<String, String>>,
    secondaryLabel: String,
    onSourceChange: (String) -> Unit,
    dialogTitle: String = "$label source",
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
            toggleContentDescription = "Use $label",
            onContainerClick = openPicker,
            containerClickLabel = "Choose $label source",
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
                        contentDescription = "Choose $label source",
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            secondaryLabel = {
                Text(
                    text = if (enabled) secondaryLabel else "Off",
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

private fun recordingElevationSourceLabel(source: String): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> "Off"
        SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM -> "DEM"
        SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO -> "Smart"
        else -> "GPS"
    }

private fun recordingHeartRateSourceLabel(source: String): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> "Off"
        SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP -> "Strap"
        else -> "Watch"
    }

private fun recordingSensorSourceLabel(
    source: String,
    isBikeProfile: Boolean,
): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> "Off"
        SettingsRepository.RECORDING_SENSOR_SOURCE_POD -> if (isBikeProfile) "Bike sensor" else "Foot pod"
        else -> "Watch/GPS"
    }

private fun recordingMetricSourceSecondaryLabel(
    source: String,
    podLabel: String,
): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> "Off"
        SettingsRepository.RECORDING_SENSOR_SOURCE_POD -> podLabel
        else -> "Watch/GPS"
    }

private fun recordingHeartRateSourceSecondaryLabel(
    source: String,
    hasLinkedStrap: Boolean,
): String =
    when (source) {
        SettingsRepository.RECORDING_SOURCE_DISABLED -> "Off"
        SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP ->
            if (hasLinkedStrap) {
                "Linked strap"
            } else {
                "Link strap first"
            }
        else -> "Watch sensor"
    }
