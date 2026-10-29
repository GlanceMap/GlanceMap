package com.glancemap.glancemapwearos.presentation.features.recording.external

import android.content.res.Resources
import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import java.util.UUID

enum class ExternalSensorKind(
    @StringRes val labelRes: Int,
    val serviceUuid: UUID,
    val telemetryLabel: String,
) {
    HEART_RATE(
        telemetryLabel = "Heart rate",
        labelRes = R.string.common_heart_rate,
        serviceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
    ),
    RUNNING_SPEED_CADENCE(
        telemetryLabel = "Run pod",
        labelRes = R.string.recording_run_pod,
        serviceUuid = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb"),
    ),
    CYCLING_SPEED_CADENCE(
        telemetryLabel = "Cycling speed/cadence",
        labelRes = R.string.recording_cycling_speed_cadence,
        serviceUuid = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb"),
    ),
    CYCLING_POWER(
        telemetryLabel = "Cycling power",
        labelRes = R.string.recording_cycling_power,
        serviceUuid = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb"),
    ),
}

data class ExternalSensorDevice(
    val name: String,
    val address: String,
    val rssi: Int?,
    val kinds: Set<ExternalSensorKind>,
    val lastSeenAtMillis: Long,
) {
    fun supportedLabel(resources: Resources): String =
        kinds
            .joinToString(separator = ", ") { resources.getString(it.labelRes) }
            .ifBlank { resources.getString(R.string.recording_unknown_ble_device) }
}

enum class ExternalSensorScanStatus {
    IDLE,
    SCANNING,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    SCAN_FAILED,
}
