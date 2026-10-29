package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalHeartRateSensorBridge
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalRunPodRuntimeStatus
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalRunPodSensorBridge
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalSensorConnectionStatus
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalSensorDevice
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalSensorKind
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalSensorScanStatus
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalSensorScanner
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalSensorSimulation
import com.glancemap.glancemapwearos.presentation.ui.WearActionButtonRole
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec

@Composable
fun RecordingExternalSensorsScreen(
    viewModel: SettingsViewModel,
    onOpenRecordingSettings: () -> Unit,
    connectLinkedSensors: Boolean = true,
) {
    val listTokens = rememberSettingsListTokens()
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember(context) { ExternalSensorScanner(context.applicationContext) }
    val scannedDevices by scanner.devices.collectAsState()
    val simulationEnabled by ExternalSensorSimulation.enabled.collectAsState()
    val devices =
        remember(scannedDevices, simulationEnabled, resources) {
            (
                ExternalSensorSimulation.devices.map { device ->
                    device.copy(
                        name =
                            resources.getString(
                                if (ExternalSensorKind.HEART_RATE in
                                    device.kinds
                                ) {
                                    R.string.recording_simulated_hr_strap
                                } else {
                                    R.string.recording_simulated_run_pod
                                },
                            ),
                    )
                } +
                    scannedDevices
            ).distinctBy(ExternalSensorDevice::address)
        }
    val status by scanner.status.collectAsState()
    val linkedHeartRateAddress by viewModel.recordingExternalHeartRateAddress.collectAsState()
    val linkedHeartRateName by viewModel.recordingExternalHeartRateName.collectAsState()
    val linkedRunPodAddress by viewModel.recordingExternalRunPodAddress.collectAsState()
    val linkedRunPodName by viewModel.recordingExternalRunPodName.collectAsState()
    val runPodRuntimeInfos by ExternalRunPodRuntimeStatus.infos.collectAsState()
    val connectedAddresses by ExternalSensorConnectionStatus.connectedAddresses.collectAsState()
    val connectingAddresses by ExternalSensorConnectionStatus.connectingAddresses.collectAsState()
    val externalSensorBatteryLevels by ExternalSensorConnectionStatus.batteryLevels.collectAsState()
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var unsupportedSensorMessage by remember { mutableStateOf<String?>(null) }
    var unsupportedSensorDetail by remember { mutableStateOf<String?>(null) }
    var pendingSensorRemoval by remember { mutableStateOf<PendingExternalSensorRemoval?>(null) }
    var pendingSensorLink by remember { mutableStateOf<PendingExternalSensorLink?>(null) }
    LaunchedEffect(linkedHeartRateAddress, linkedRunPodAddress, pendingSensorLink) {
        val pending = pendingSensorLink ?: return@LaunchedEffect
        val persistedAddress =
            when (pending.type) {
                LinkedExternalSensorType.HEART_RATE -> linkedHeartRateAddress
                LinkedExternalSensorType.RUN_POD -> linkedRunPodAddress
            }
        if (persistedAddress.normalizedBluetoothAddressOrNull() == pending.address.normalizedBluetoothAddressOrNull()) {
            pendingSensorLink = null
        }
    }
    val hasPermissions =
        remember(context, permissionRefresh) {
            ExternalSensorScanner.hasRequiredPermissions(context)
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionRefresh += 1
            if (ExternalSensorScanner.hasRequiredPermissions(context)) {
                scanner.startScan()
            }
        }

    ExternalHeartRateSensorBridge(
        active = connectLinkedSensors && !linkedHeartRateAddress.isNullOrBlank(),
        paused = false,
        address = linkedHeartRateAddress,
        onHeartRate = { _, _ -> },
    )
    ExternalRunPodSensorBridge(
        active = connectLinkedSensors && !linkedRunPodAddress.isNullOrBlank(),
        paused = false,
        address = linkedRunPodAddress,
        onMeasurement = {},
    )

    DisposableEffect(scanner) {
        onDispose { scanner.stopScan() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    permissionRefresh += 1
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            RecordingSettingsShortcutChip(
                onClick = onOpenRecordingSettings,
            )
        }
        item {
            ExternalSensorScanChip(
                hasPermissions = hasPermissions,
                status = status,
                onRequestPermissions = {
                    permissionLauncher.launch(ExternalSensorScanner.requiredPermissions().toTypedArray())
                },
                onStartScan = scanner::startScan,
                onStopScan = scanner::stopScan,
            )
        }
        unsupportedSensorMessage?.let { message ->
            item {
                ExternalSensorInfo(
                    primaryText = message,
                    secondaryText = unsupportedSensorDetail,
                )
            }
        }
        val scannedAddresses = devices.mapNotNullTo(mutableSetOf()) { it.address.normalizedBluetoothAddressOrNull() }
        linkedHeartRateAddress
            ?.takeIf { it.isNotBlank() && it.normalizedBluetoothAddressOrNull() !in scannedAddresses }
            ?.let { address ->
                item {
                    val connected = address.isConnectedIn(connectedAddresses)
                    LinkedExternalSensorChip(
                        name =
                            linkedHeartRateName.orLinkedSensorFallback(
                                resources.getString(R.string.recording_heart_strap),
                            ),
                        address = address,
                        connected = connected,
                        reconnecting = !connected && address.isConnectedIn(connectingAddresses),
                        batteryLevelPercent = externalSensorBatteryLevels[address.normalizedBluetoothAddress()],
                        onForget = {
                            pendingSensorRemoval =
                                PendingExternalSensorRemoval(
                                    type = LinkedExternalSensorType.HEART_RATE,
                                    name =
                                        linkedHeartRateName.orLinkedSensorFallback(
                                            resources.getString(R.string.recording_heart_strap),
                                        ),
                                    address = address,
                                )
                        },
                    )
                }
            }
        linkedRunPodAddress
            ?.takeIf { it.isNotBlank() && it.normalizedBluetoothAddressOrNull() !in scannedAddresses }
            ?.let { address ->
                item {
                    val normalizedAddress = address.normalizedBluetoothAddress()
                    val connected = address.isConnectedIn(connectedAddresses)
                    LinkedExternalSensorChip(
                        name =
                            linkedRunPodName.orLinkedSensorFallback(
                                resources.getString(R.string.recording_external_sensor_2),
                            ),
                        address = address,
                        connected = connected,
                        reconnecting = !connected && address.isConnectedIn(connectingAddresses),
                        batteryLevelPercent =
                            runPodRuntimeInfos[normalizedAddress]?.batteryLevelPercent
                                ?: externalSensorBatteryLevels[normalizedAddress],
                        onForget = {
                            pendingSensorRemoval =
                                PendingExternalSensorRemoval(
                                    type = LinkedExternalSensorType.RUN_POD,
                                    name =
                                        linkedRunPodName.orLinkedSensorFallback(
                                            resources.getString(R.string.recording_external_sensor_2),
                                        ),
                                    address = address,
                                )
                        },
                    )
                }
            }
        val hasLinkedDevice = !linkedHeartRateAddress.isNullOrBlank() || !linkedRunPodAddress.isNullOrBlank()
        if (devices.isEmpty() && !hasLinkedDevice) {
            item {
                ExternalSensorInfo(
                    primaryText =
                        if (status == ExternalSensorScanStatus.SCANNING) {
                            resources.getString(R.string.recording_searching_nearby_sensors)
                        } else {
                            resources.getString(R.string.recording_no_devices_found_yet)
                        },
                )
            }
        } else {
            devices.forEach { device ->
                item {
                    val heartRateSelected =
                        linkedHeartRateAddress.normalizedBluetoothAddressOrNull() ==
                            device.address.normalizedBluetoothAddressOrNull() ||
                            pendingSensorLink.matches(device.address, LinkedExternalSensorType.HEART_RATE)
                    val runPodSelected =
                        linkedRunPodAddress.normalizedBluetoothAddressOrNull() ==
                            device.address.normalizedBluetoothAddressOrNull() ||
                            pendingSensorLink.matches(device.address, LinkedExternalSensorType.RUN_POD)
                    if (heartRateSelected || runPodSelected) {
                        val selectedType =
                            if (heartRateSelected) {
                                LinkedExternalSensorType.HEART_RATE
                            } else {
                                LinkedExternalSensorType.RUN_POD
                            }
                        val connected = device.address.isConnectedIn(connectedAddresses)
                        val reconnecting =
                            !connected &&
                                (
                                    pendingSensorLink.matches(device.address, selectedType) ||
                                        device.address.isConnectedIn(connectingAddresses)
                                )
                        val normalizedAddress = device.address.normalizedBluetoothAddress()
                        LinkedExternalSensorChip(
                            name =
                                if (heartRateSelected) {
                                    pendingSensorLink
                                        .takeIf {
                                            it.matches(device.address, LinkedExternalSensorType.HEART_RATE)
                                        }?.name
                                        ?: linkedHeartRateName.orLinkedSensorFallback(device.name)
                                } else {
                                    pendingSensorLink
                                        .takeIf {
                                            it.matches(device.address, LinkedExternalSensorType.RUN_POD)
                                        }?.name
                                        ?: linkedRunPodName.orLinkedSensorFallback(device.name)
                                },
                            address = device.address,
                            connected = connected,
                            reconnecting = reconnecting,
                            batteryLevelPercent =
                                if (heartRateSelected) {
                                    externalSensorBatteryLevels[
                                        normalizedAddress,
                                    ]
                                } else {
                                    runPodRuntimeInfos[normalizedAddress]?.batteryLevelPercent
                                        ?: externalSensorBatteryLevels[
                                            normalizedAddress,
                                        ]
                                },
                            onForget = {
                                pendingSensorRemoval =
                                    PendingExternalSensorRemoval(
                                        type =
                                            if (heartRateSelected) {
                                                LinkedExternalSensorType.HEART_RATE
                                            } else {
                                                LinkedExternalSensorType.RUN_POD
                                            },
                                        name =
                                            if (heartRateSelected) {
                                                linkedHeartRateName.orLinkedSensorFallback(device.name)
                                            } else {
                                                linkedRunPodName.orLinkedSensorFallback(device.name)
                                            },
                                        address = device.address,
                                    )
                            },
                        )
                    } else {
                        ExternalSensorDeviceChip(
                            device = device,
                            onClick = {
                                if (device.canLinkHeartRate()) {
                                    scanner.stopScan()
                                    unsupportedSensorMessage = null
                                    unsupportedSensorDetail = null
                                    pendingSensorLink =
                                        PendingExternalSensorLink(
                                            type = LinkedExternalSensorType.HEART_RATE,
                                            name = device.name,
                                            address = device.address,
                                        )
                                    ExternalSensorConnectionStatus.markConnecting(device.address)
                                    viewModel.setRecordingExternalHeartRateDevice(device.address, device.name)
                                } else if (device.canLinkRunPod()) {
                                    scanner.stopScan()
                                    unsupportedSensorMessage = null
                                    unsupportedSensorDetail = null
                                    pendingSensorLink =
                                        PendingExternalSensorLink(
                                            type = LinkedExternalSensorType.RUN_POD,
                                            name = device.name,
                                            address = device.address,
                                        )
                                    ExternalSensorConnectionStatus.markConnecting(device.address)
                                    viewModel.setRecordingExternalRunPodDevice(device.address, device.name)
                                } else {
                                    unsupportedSensorMessage =
                                        resources.getString(R.string.settings_is_not_supported_yet, device.name)
                                    unsupportedSensorDetail =
                                        resources.getString(R.string.recording_sensor_services_unsupported)
                                    DebugTelemetry.log(
                                        "ExternalSensors",
                                        "event=device_tap_unsupported name=${device.name.sanitizeTelemetryToken()} " +
                                            "kinds=${device.kinds.joinToString { it.name }.sanitizeTelemetryToken()}",
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingSensorRemoval?.let { pending ->
        WearActionDialog(
            visible = true,
            title = stringResource(R.string.settings_remove_device),
            message = pending.name,
            confirmText = stringResource(R.string.common_yes),
            onConfirm = {
                pendingSensorRemoval = null
                unsupportedSensorMessage = null
                unsupportedSensorDetail = null
                when (pending.type) {
                    LinkedExternalSensorType.HEART_RATE ->
                        if (
                            linkedHeartRateAddress.normalizedBluetoothAddressOrNull() ==
                            pending.address.normalizedBluetoothAddressOrNull()
                        ) {
                            viewModel.setRecordingExternalHeartRateDevice(null, null)
                        }
                    LinkedExternalSensorType.RUN_POD ->
                        if (
                            linkedRunPodAddress.normalizedBluetoothAddressOrNull() ==
                            pending.address.normalizedBluetoothAddressOrNull()
                        ) {
                            viewModel.setRecordingExternalRunPodDevice(null, null)
                        }
                }
            },
            onDismissRequest = { pendingSensorRemoval = null },
            dismissText = stringResource(R.string.common_no),
            onDismiss = { pendingSensorRemoval = null },
            confirmRole = WearActionButtonRole.Destructive,
        )
    }
}

private enum class LinkedExternalSensorType {
    HEART_RATE,
    RUN_POD,
}

private data class PendingExternalSensorRemoval(
    val type: LinkedExternalSensorType,
    val name: String,
    val address: String,
)

private data class PendingExternalSensorLink(
    val type: LinkedExternalSensorType,
    val name: String,
    val address: String,
)

private fun PendingExternalSensorLink?.matches(
    address: String,
    type: LinkedExternalSensorType,
): Boolean =
    this?.address.normalizedBluetoothAddressOrNull() == address.normalizedBluetoothAddressOrNull() &&
        this?.type == type

@Composable
private fun LinkedExternalSensorChip(
    name: String,
    address: String,
    connected: Boolean,
    reconnecting: Boolean,
    batteryLevelPercent: Int? = null,
    onForget: () -> Unit,
) {
    val adaptive = rememberWearAdaptiveSpec()
    val minHeight =
        when {
            adaptive.fontScale >= 1.45f -> 84.dp
            adaptive.fontScale >= 1.25f -> 76.dp
            else -> 60.dp
        }
    LinkedExternalSensorChipContent(
        name = name,
        address = address,
        connected = connected,
        reconnecting = reconnecting,
        batteryLevelPercent = batteryLevelPercent,
        minHeight = minHeight,
        onForget = onForget,
    )
}

@Composable
private fun LinkedExternalSensorChipContent(
    name: String,
    address: String,
    connected: Boolean,
    reconnecting: Boolean,
    batteryLevelPercent: Int?,
    minHeight: androidx.compose.ui.unit.Dp,
    onForget: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val backgroundColor =
        when {
            connected -> Color(0xFF254336)
            reconnecting -> Color(0xFF3D3520)
            else -> Color(0xFF2B2F36)
        }
    val contentColor =
        when {
            connected -> Color(0xFFF1FFF5)
            reconnecting -> Color(0xFFFFF1C2)
            else -> Color(0xFFF1F5FB)
        }
    val secondaryColor =
        when {
            connected -> Color(0xFFB7DCC4)
            reconnecting -> Color(0xFFE2C978)
            else -> Color(0xFFBAC5D4)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(backgroundColor, RoundedCornerShape(24.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onForget()
                    },
                ).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                when {
                    connected -> Icons.Default.CheckCircle
                    reconnecting -> Icons.AutoMirrored.Filled.BluetoothSearching
                    else -> Icons.Default.Bluetooth
                },
            contentDescription = null,
            modifier = Modifier.size(ChipDefaults.IconSize),
            tint =
                when {
                    connected -> Color(0xFF8FF0A4)
                    reconnecting -> Color(0xFFF6C453)
                    else -> Color(0xFF9FB2C9)
                },
        )
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (connected && batteryLevelPercent in 0..100) {
                    Text(
                        text = stringResource(R.string.common_percent, batteryLevelPercent.toString()),
                        modifier = Modifier.padding(start = 5.dp),
                        color = contentColor,
                        maxLines = 1,
                    )
                } else if (!connected && reconnecting) {
                    Text(
                        text = stringResource(R.string.settings_connecting),
                        modifier = Modifier.padding(start = 5.dp),
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = address,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun String.normalizedBluetoothAddress(): String = trim().uppercase()

private fun String?.normalizedBluetoothAddressOrNull(): String? = this?.trim()?.uppercase()?.takeIf(String::isNotBlank)

private fun String.isConnectedIn(connectedAddresses: Set<String>): Boolean = normalizedBluetoothAddress() in connectedAddresses

@Composable
private fun ExternalSensorScanChip(
    hasPermissions: Boolean,
    status: ExternalSensorScanStatus,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    when {
        !hasPermissions ->
            SettingsPickerChip(
                label = stringResource(R.string.settings_allow_bluetooth),
                secondaryLabel = stringResource(R.string.settings_needed_to_find_sensors),
                iconImageVector = Icons.Default.Bluetooth,
                onClick = onRequestPermissions,
            )
        status == ExternalSensorScanStatus.SCANNING ->
            SettingsPickerChip(
                label = stringResource(R.string.settings_stop_scan),
                secondaryLabel = stringResource(R.string.settings_searching_nearby_ble_devices),
                iconImageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                onClick = onStopScan,
            )
        else ->
            SettingsPickerChip(
                label = stringResource(R.string.settings_scan_sensors),
                secondaryLabel = scanStatusLabel(status, resources = LocalResources.current),
                iconImageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                onClick = onStartScan,
            )
    }
}

@Composable
private fun ExternalSensorDeviceChip(
    device: ExternalSensorDevice,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val icon =
        when {
            ExternalSensorKind.HEART_RATE in device.kinds -> Icons.Default.Favorite
            device.kinds.isNotEmpty() -> Icons.Default.Sensors
            else -> Icons.Default.Bluetooth
        }
    SettingsPickerChip(
        label = device.name,
        secondaryLabel =
            buildString {
                append(device.supportedLabel(resources))
                device.rssi?.let { append(stringResource(R.string.settings_dbm, it)) }
            },
        iconImageVector = icon,
        onClick = onClick,
    )
}

@Composable
private fun ExternalSensorInfo(
    primaryText: String,
    secondaryText: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = primaryText,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        secondaryText?.let { text ->
            Text(
                text = text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
        }
    }
}

private fun scanStatusLabel(
    status: ExternalSensorScanStatus,
    resources: Resources,
): String =
    when (status) {
        ExternalSensorScanStatus.IDLE -> resources.getString(R.string.settings_heart_straps_and_sensors)
        ExternalSensorScanStatus.SCANNING -> resources.getString(R.string.settings_searching)
        ExternalSensorScanStatus.BLUETOOTH_UNAVAILABLE -> resources.getString(R.string.settings_bluetooth_unavailable)
        ExternalSensorScanStatus.BLUETOOTH_OFF -> resources.getString(R.string.settings_turn_bluetooth_on)
        ExternalSensorScanStatus.PERMISSION_MISSING -> resources.getString(R.string.settings_permission_needed)
        ExternalSensorScanStatus.SCAN_FAILED -> resources.getString(R.string.settings_scan_failed_try_again)
    }

private fun ExternalSensorDevice.canLinkHeartRate(): Boolean = kinds.isEmpty() || ExternalSensorKind.HEART_RATE in kinds

private fun ExternalSensorDevice.canLinkRunPod(): Boolean =
    ExternalSensorKind.RUNNING_SPEED_CADENCE in kinds ||
        ExternalSensorKind.CYCLING_SPEED_CADENCE in kinds ||
        ExternalSensorKind.CYCLING_POWER in kinds

private fun String.sanitizeTelemetryToken(): String =
    replace(' ', '_')
        .replace('|', '_')
        .replace('=', '_')

private fun String?.orLinkedSensorFallback(fallback: String): String =
    this
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: fallback
