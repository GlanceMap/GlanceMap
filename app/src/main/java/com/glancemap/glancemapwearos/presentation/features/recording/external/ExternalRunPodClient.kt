package com.glancemap.glancemapwearos.presentation.features.recording.external

import android.bluetooth.BluetoothGattService
import android.content.Context
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import java.util.UUID

enum class ExternalRunPodMeasurementSource { RUNNING, CYCLING_SPEED_CADENCE, CYCLING_POWER }

data class ExternalRunPodMeasurement(
    val speedMps: Float?,
    val cadenceSpm: Int?,
    val rawTotalDistanceUnits: Long?,
    val totalDistanceMeters: Double?,
    val powerWatts: Int? = null,
    val batteryLevelPercent: Int? = null,
    val timeMillis: Long,
    val source: ExternalRunPodMeasurementSource = ExternalRunPodMeasurementSource.RUNNING,
    val wheelIntervalSeconds: Double? = null,
)

class ExternalRunPodClient(
    private val context: Context,
    private val address: String,
    private val wheelCircumferenceMeters: Double = SettingsRepository.DEFAULT_CYCLING_WHEEL_CIRCUMFERENCE_METERS.toDouble(),
    private val onMeasurement: (ExternalRunPodMeasurement) -> Unit,
    private val onUnavailable: () -> Unit = {},
    private val autoReconnect: Boolean = false,
) {
    private val cscDecoder = CyclingSpeedCadenceDecoder(wheelCircumferenceMeters)
    private var telemetrySampleCount = 0
    private var telemetryPowerSampleCount = 0
    private val reconnectController =
        ExternalSensorReconnectController(
            logTag = "ExternalRunPod",
            autoReconnect = autoReconnect,
            connect = { client.connect() },
        )
    private val client: ExternalBleGattClient by lazy(LazyThreadSafetyMode.NONE) {
        ExternalBleGattClient(
            context = context,
            address = address,
            logTag = "ExternalRunPod",
            serviceUuid = RUNNING_SPEED_CADENCE_SERVICE_UUID,
            measurementUuid = RSC_MEASUREMENT_UUID,
            extraNotifyCharacteristics =
                listOf(
                    BleCharacteristicRef(CYCLING_SPEED_CADENCE_SERVICE_UUID, CSC_MEASUREMENT_UUID),
                    BleCharacteristicRef(CYCLING_POWER_SERVICE_UUID, CYCLING_POWER_MEASUREMENT_UUID),
                    BleCharacteristicRef(STRYD_CUSTOM_SERVICE_PRIMARY_UUID, STRYD_CUSTOM_NOTIFY_PRIMARY_UUID),
                    BleCharacteristicRef(STRYD_CUSTOM_SERVICE_SECONDARY_UUID, STRYD_CUSTOM_NOTIFY_SECONDARY_UUID),
                ),
            readyMeasurementUuids =
                setOf(
                    RSC_MEASUREMENT_UUID,
                    CSC_MEASUREMENT_UUID,
                    CYCLING_POWER_MEASUREMENT_UUID,
                ),
            readCharacteristics =
                listOf(
                    BleCharacteristicRef(CYCLING_POWER_SERVICE_UUID, CYCLING_POWER_FEATURE_UUID),
                    BleCharacteristicRef(CYCLING_POWER_SERVICE_UUID, SENSOR_LOCATION_UUID),
                    BleCharacteristicRef(CYCLING_SPEED_CADENCE_SERVICE_UUID, CSC_FEATURE_UUID),
                    BleCharacteristicRef(CYCLING_SPEED_CADENCE_SERVICE_UUID, SENSOR_LOCATION_UUID),
                    BleCharacteristicRef(RUNNING_SPEED_CADENCE_SERVICE_UUID, RSC_FEATURE_UUID),
                    BleCharacteristicRef(BATTERY_SERVICE_UUID, BATTERY_LEVEL_UUID),
                    BleCharacteristicRef(DEVICE_INFORMATION_SERVICE_UUID, MANUFACTURER_NAME_UUID),
                    BleCharacteristicRef(DEVICE_INFORMATION_SERVICE_UUID, MODEL_NUMBER_UUID),
                    BleCharacteristicRef(DEVICE_INFORMATION_SERVICE_UUID, FIRMWARE_REVISION_UUID),
                    BleCharacteristicRef(DEVICE_INFORMATION_SERVICE_UUID, SOFTWARE_REVISION_UUID),
                ),
            onConnecting = {
                ExternalSensorConnectionStatus.markConnecting(address)
            },
            onServicesReady = { gatt -> logGattTable(gatt.services) },
            onCharacteristicRead = ::handleRead,
            onConnectionChanged = { connected ->
                ExternalSensorConnectionStatus.update(address, connected)
                if (connected) {
                    reconnectController.markReady()
                } else {
                    ExternalRunPodRuntimeStatus.markDisconnected(address)
                }
            },
            onDisconnected = { event ->
                onUnavailable()
                reconnectController.onDisconnected(event)
            },
            onMeasurement = ::handleMeasurement,
        )
    }

    fun connect() {
        reconnectController.start()
        client.connect()
    }

    fun disconnect() {
        reconnectController.stop()
        client.disconnect()
    }

    private fun logGattTable(services: List<BluetoothGattService>) {
        if (!DebugTelemetry.isEnabled()) return
        DebugTelemetry.log("ExternalRunPod", "event=gatt_table serviceCount=${services.size}")
        services.take(MAX_GATT_SERVICES_TO_LOG).forEach { service ->
            DebugTelemetry.log(
                "ExternalRunPod",
                "event=gatt_service uuid=${service.uuid} characteristicCount=${service.characteristics.size}",
            )
            service.characteristics.take(MAX_GATT_CHARACTERISTICS_PER_SERVICE_TO_LOG).forEach { characteristic ->
                DebugTelemetry.log(
                    "ExternalRunPod",
                    "event=gatt_characteristic service=${service.uuid} uuid=${characteristic.uuid} " +
                        "properties=${characteristic.properties}",
                )
            }
        }
    }

    private fun handleMeasurement(
        characteristicUuid: UUID,
        value: ByteArray,
    ) {
        when (characteristicUuid) {
            RSC_MEASUREMENT_UUID -> {
                val measurement = decodeRscMeasurement(value) ?: return
                onMeasurement(measurement)
                logMeasurementSample(measurement, source = "rsc")
            }
            CSC_MEASUREMENT_UUID -> {
                val measurement =
                    cscDecoder.decode(value, timeMillis = System.currentTimeMillis())
                        ?: return
                onMeasurement(measurement)
                logMeasurementSample(measurement, source = "csc", raw = value)
            }
            CYCLING_POWER_MEASUREMENT_UUID -> {
                val measurement = decodeCyclingPowerMeasurement(value) ?: return
                onMeasurement(measurement)
                ExternalRunPodRuntimeStatus.updatePower(address, measurement.powerWatts, measurement.timeMillis)
                if (DebugTelemetry.isEnabled()) {
                    telemetryPowerSampleCount += 1
                    if (shouldLogExternalSensorSample(telemetryPowerSampleCount)) {
                        DebugTelemetry.log(
                            "ExternalRunPod",
                            "event=power_sample count=$telemetryPowerSampleCount " +
                                "powerWatts=${measurement.powerWatts ?: -1} raw=${value.toHexSnippet()}",
                        )
                    }
                }
            }
            STRYD_CUSTOM_NOTIFY_PRIMARY_UUID,
            STRYD_CUSTOM_NOTIFY_SECONDARY_UUID,
            -> logCustomNotification(characteristicUuid, value)
        }
    }

    private fun logMeasurementSample(
        measurement: ExternalRunPodMeasurement,
        source: String,
        raw: ByteArray? = null,
    ) {
        if (!DebugTelemetry.isEnabled()) return
        telemetrySampleCount += 1
        if (!shouldLogExternalSensorSample(telemetrySampleCount)) return
        val sourceDetails =
            if (source == "csc") {
                " wheelCircumferenceM=${formatTelemetryDouble(wheelCircumferenceMeters)} " +
                    "raw=${raw?.toHexSnippet() ?: "na"}"
            } else {
                ""
            }
        DebugTelemetry.log(
            "ExternalRunPod",
            "event=sample count=$telemetrySampleCount " +
                "speedMps=${measurement.speedMps?.let { formatTelemetryFloat(it) } ?: "na"} " +
                "cadenceSpm=${measurement.cadenceSpm ?: -1} " +
                "rawDistanceUnits=${measurement.rawTotalDistanceUnits ?: -1} " +
                "distanceMeters=${measurement.totalDistanceMeters?.let { formatTelemetryDouble(it) } ?: "na"} " +
                "source=$source$sourceDetails",
        )
    }

    private fun handleRead(
        characteristicUuid: UUID,
        value: ByteArray,
    ) {
        when (characteristicUuid) {
            CYCLING_POWER_FEATURE_UUID ->
                DebugTelemetry.log("ExternalRunPod", "event=power_feature raw=${value.toHexSnippet()}")
            CSC_FEATURE_UUID ->
                DebugTelemetry.log("ExternalRunPod", "event=csc_feature raw=${value.toHexSnippet()}")
            SENSOR_LOCATION_UUID ->
                DebugTelemetry.log(
                    "ExternalRunPod",
                    "event=sensor_location value=${value.firstOrNull()?.toInt()?.and(0xFF) ?: -1} " +
                        "label=${value
                            .firstOrNull()
                            ?.toInt()
                            ?.and(0xFF)
                            ?.sensorLocationLabel() ?: "na"}",
                )
            RSC_FEATURE_UUID ->
                DebugTelemetry.log("ExternalRunPod", "event=rsc_feature raw=${value.toHexSnippet()}")
            BATTERY_LEVEL_UUID -> {
                val battery =
                    value
                        .firstOrNull()
                        ?.toInt()
                        ?.and(0xFF)
                        ?.coerceIn(0, 100)
                val measurement =
                    ExternalRunPodMeasurement(
                        speedMps = null,
                        cadenceSpm = null,
                        rawTotalDistanceUnits = null,
                        totalDistanceMeters = null,
                        batteryLevelPercent = battery,
                        timeMillis = System.currentTimeMillis(),
                    )
                onMeasurement(measurement)
                ExternalRunPodRuntimeStatus.updateBattery(address, battery, measurement.timeMillis)
                DebugTelemetry.log("ExternalRunPod", "event=battery levelPercent=${battery ?: -1}")
            }
            MANUFACTURER_NAME_UUID,
            MODEL_NUMBER_UUID,
            FIRMWARE_REVISION_UUID,
            SOFTWARE_REVISION_UUID,
            -> {
                val text = value.toUtf8Text()
                ExternalRunPodRuntimeStatus.updateDeviceInfo(address, characteristicUuid.deviceInfoLabel(), text)
                DebugTelemetry.log(
                    "ExternalRunPod",
                    "event=device_info field=${characteristicUuid.deviceInfoLabel()} value=${text.sanitizeTelemetryToken()}",
                )
            }
            else ->
                DebugTelemetry.log("ExternalRunPod", "event=read_unknown uuid=$characteristicUuid raw=${value.toHexSnippet()}")
        }
    }

    private val customNotificationCounts = mutableMapOf<UUID, Int>()

    private fun logCustomNotification(
        characteristicUuid: UUID,
        value: ByteArray,
    ) {
        if (!DebugTelemetry.isEnabled()) return
        val count = (customNotificationCounts[characteristicUuid] ?: 0) + 1
        customNotificationCounts[characteristicUuid] = count
        if (count <= CUSTOM_RAW_LOG_INITIAL_SAMPLES || count % CUSTOM_RAW_LOG_INTERVAL == 0) {
            DebugTelemetry.log(
                "ExternalRunPod",
                "event=custom_raw uuid=$characteristicUuid count=$count size=${value.size} raw=${value.toHexSnippet()}",
            )
        }
    }

    companion object {
        val RUNNING_SPEED_CADENCE_SERVICE_UUID: UUID =
            BluetoothUuid.service16(0x1814)
        val CYCLING_SPEED_CADENCE_SERVICE_UUID: UUID =
            BluetoothUuid.service16(0x1816)
        private val RSC_MEASUREMENT_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A53)
        private val RSC_FEATURE_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A54)
        private val CSC_MEASUREMENT_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A5B)
        private val CSC_FEATURE_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A5C)
        private val CYCLING_POWER_SERVICE_UUID: UUID =
            BluetoothUuid.service16(0x1818)
        private val CYCLING_POWER_MEASUREMENT_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A63)
        private val CYCLING_POWER_FEATURE_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A65)
        private val SENSOR_LOCATION_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A5D)
        private val BATTERY_SERVICE_UUID: UUID =
            BluetoothUuid.service16(0x180F)
        private val BATTERY_LEVEL_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A19)
        private val DEVICE_INFORMATION_SERVICE_UUID: UUID =
            BluetoothUuid.service16(0x180A)
        private val MANUFACTURER_NAME_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A29)
        private val MODEL_NUMBER_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A24)
        private val FIRMWARE_REVISION_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A26)
        private val SOFTWARE_REVISION_UUID: UUID =
            BluetoothUuid.characteristic16(0x2A28)
        private val STRYD_CUSTOM_SERVICE_PRIMARY_UUID: UUID =
            UUID.fromString("3fe7aa12-44e3-cdb5-fc08-7db6a30b0131")
        private val STRYD_CUSTOM_NOTIFY_PRIMARY_UUID: UUID =
            UUID.fromString("3fe7aa13-44e3-cdb5-fc08-7db6a30b0131")
        private val STRYD_CUSTOM_SERVICE_SECONDARY_UUID: UUID =
            UUID.fromString("7e78aa18-72cd-d3b8-a81f-5b7e589bea0f")
        private val STRYD_CUSTOM_NOTIFY_SECONDARY_UUID: UUID =
            UUID.fromString("7e78aa19-72cd-d3b8-a81f-5b7e589bea0f")

        fun decodeRscMeasurement(value: ByteArray): ExternalRunPodMeasurement? {
            if (value.size < 4) return null
            val flags = value[0].toInt() and 0xFF
            var offset = 1
            val speedRaw = value.readUInt16Le(offset) ?: return null
            offset += 2
            val cadenceRaw = value[offset].toInt() and 0xFF
            offset += 1

            val strideLengthPresent = flags and 0x01 != 0
            val totalDistancePresent = flags and 0x02 != 0
            if (strideLengthPresent) {
                if (value.size < offset + 2) return null
                offset += 2
            }
            val rawTotalDistanceUnits =
                if (totalDistancePresent) {
                    value.readUInt32Le(offset) ?: return null
                } else {
                    null
                }
            val totalDistanceMeters = rawTotalDistanceUnits?.toDouble()?.div(RSC_TOTAL_DISTANCE_UNITS_PER_METER)

            return ExternalRunPodMeasurement(
                speedMps = (speedRaw / 256f).takeIf { it.isFinite() && it in 0f..12f },
                cadenceSpm = cadenceRaw.takeIf { it in 1..255 },
                rawTotalDistanceUnits = rawTotalDistanceUnits?.takeIf { it >= 0L },
                totalDistanceMeters = totalDistanceMeters?.takeIf { it.isFinite() && it >= 0.0 },
                timeMillis = System.currentTimeMillis(),
            )
        }

        fun decodeCyclingPowerMeasurement(value: ByteArray): ExternalRunPodMeasurement? {
            if (value.size < 4) return null
            val powerWatts = value.readInt16Le(2) ?: return null
            return ExternalRunPodMeasurement(
                speedMps = null,
                cadenceSpm = null,
                rawTotalDistanceUnits = null,
                totalDistanceMeters = null,
                powerWatts = powerWatts.takeIf { it in 0..2500 },
                source = ExternalRunPodMeasurementSource.CYCLING_POWER,
                timeMillis = System.currentTimeMillis(),
            )
        }
    }
}

internal class CyclingSpeedCadenceDecoder(
    private val wheelCircumferenceMeters: Double,
) {
    private var previousWheelRevolutions: Long? = null
    private var previousWheelEventTime: Int? = null
    private var previousCrankRevolutions: Int? = null
    private var previousCrankEventTime: Int? = null

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    fun decode(
        value: ByteArray,
        timeMillis: Long = System.currentTimeMillis(),
    ): ExternalRunPodMeasurement? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        var offset = 1
        val wheelDataPresent = flags and CSC_FLAG_WHEEL_REVOLUTION_DATA_PRESENT != 0
        val crankDataPresent = flags and CSC_FLAG_CRANK_REVOLUTION_DATA_PRESENT != 0
        var wheelRevolutions: Long? = null
        var wheelEventTime: Int? = null
        var crankRevolutions: Int? = null
        var crankEventTime: Int? = null

        if (wheelDataPresent) {
            wheelRevolutions = value.readUInt32Le(offset) ?: return null
            offset += 4
            wheelEventTime = value.readUInt16Le(offset) ?: return null
            offset += 2
        }
        if (crankDataPresent) {
            crankRevolutions = value.readUInt16Le(offset) ?: return null
            offset += 2
            crankEventTime = value.readUInt16Le(offset) ?: return null
        }
        if (!wheelDataPresent && !crankDataPresent) return null

        val speedMps =
            if (wheelRevolutions != null && wheelEventTime != null) {
                calculateWheelSpeedMps(wheelRevolutions, wheelEventTime)
            } else {
                null
            }
        val wheelIntervalSeconds =
            if (speedMps != null && wheelEventTime != null && previousWheelEventTime != null) {
                unsignedDelta(wheelEventTime.toLong(), previousWheelEventTime!!.toLong(), UINT16_ROLLOVER) /
                    BLE_EVENT_TIME_TICKS_PER_SECOND
            } else {
                null
            }
        val distanceMeters =
            wheelRevolutions
                ?.takeIf { it >= 0L }
                ?.let { it * wheelCircumferenceMeters }
                ?.takeIf { it.isFinite() && it >= 0.0 }
        val cadenceRpm =
            if (crankRevolutions != null && crankEventTime != null) {
                calculateCrankCadenceRpm(crankRevolutions, crankEventTime)
            } else {
                null
            }

        if (wheelRevolutions != null) previousWheelRevolutions = wheelRevolutions
        if (wheelEventTime != null) previousWheelEventTime = wheelEventTime
        if (crankRevolutions != null) previousCrankRevolutions = crankRevolutions
        if (crankEventTime != null) previousCrankEventTime = crankEventTime

        return ExternalRunPodMeasurement(
            speedMps = speedMps?.takeIf { it.isFinite() && it in 0f..CYCLING_SPEED_MAX_MPS },
            cadenceSpm = cadenceRpm?.takeIf { it in 1..CYCLING_CADENCE_MAX_RPM },
            rawTotalDistanceUnits = wheelRevolutions,
            totalDistanceMeters = distanceMeters,
            source = ExternalRunPodMeasurementSource.CYCLING_SPEED_CADENCE,
            wheelIntervalSeconds = wheelIntervalSeconds,
            timeMillis = timeMillis,
        )
    }

    private fun calculateWheelSpeedMps(
        wheelRevolutions: Long,
        wheelEventTime: Int,
    ): Float? {
        val previousRevolutions = previousWheelRevolutions ?: return null
        val previousEventTime = previousWheelEventTime ?: return null
        val deltaRevolutions = unsignedDelta(wheelRevolutions, previousRevolutions, UINT32_ROLLOVER)
        val deltaEventTicks = unsignedDelta(wheelEventTime.toLong(), previousEventTime.toLong(), UINT16_ROLLOVER)
        if (deltaRevolutions <= 0L || deltaEventTicks <= 0L) return null
        val elapsedSeconds = deltaEventTicks / BLE_EVENT_TIME_TICKS_PER_SECOND
        return ((deltaRevolutions * wheelCircumferenceMeters) / elapsedSeconds).toFloat()
    }

    private fun calculateCrankCadenceRpm(
        crankRevolutions: Int,
        crankEventTime: Int,
    ): Int? {
        val previousRevolutions = previousCrankRevolutions ?: return null
        val previousEventTime = previousCrankEventTime ?: return null
        val deltaRevolutions = unsignedDelta(crankRevolutions.toLong(), previousRevolutions.toLong(), UINT16_ROLLOVER)
        val deltaEventTicks = unsignedDelta(crankEventTime.toLong(), previousEventTime.toLong(), UINT16_ROLLOVER)
        if (deltaRevolutions <= 0L || deltaEventTicks <= 0L) return null
        val elapsedMinutes = (deltaEventTicks / BLE_EVENT_TIME_TICKS_PER_SECOND) / 60.0
        return (deltaRevolutions / elapsedMinutes).toInt()
    }
}

private fun ByteArray.readUInt16Le(offset: Int): Int? {
    if (size < offset + 2) return null
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.readInt16Le(offset: Int): Int? {
    val raw = readUInt16Le(offset) ?: return null
    return if (raw and 0x8000 != 0) raw - 0x10000 else raw
}

private fun ByteArray.readUInt32Le(offset: Int): Long? {
    if (size < offset + 4) return null
    return (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)
}

private fun formatTelemetryFloat(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

private fun formatTelemetryDouble(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

private fun ByteArray.toHexSnippet(maxBytes: Int = 20): String =
    take(maxBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        .let { hex -> if (size > maxBytes) "$hex..." else hex }

private fun ByteArray.toUtf8Text(): String =
    toString(Charsets.UTF_8)
        .trim()
        .ifBlank { "na" }
        .take(MAX_DEVICE_INFO_TEXT_LENGTH)

private fun String.sanitizeTelemetryToken(): String =
    replace(' ', '_')
        .replace('|', '_')
        .replace('=', '_')

private fun Int.sensorLocationLabel(): String =
    when (this) {
        0 -> "other"
        1 -> "top_of_shoe"
        2 -> "in_shoe"
        3 -> "hip"
        4 -> "front_wheel"
        5 -> "left_crank"
        6 -> "right_crank"
        7 -> "left_pedal"
        8 -> "right_pedal"
        9 -> "front_hub"
        10 -> "rear_dropout"
        11 -> "chainstay"
        12 -> "rear_wheel"
        13 -> "rear_hub"
        14 -> "chest"
        15 -> "spider"
        16 -> "chain_ring"
        else -> "unknown"
    }

private fun UUID.deviceInfoLabel(): String =
    when (this) {
        BluetoothUuid.characteristic16(0x2A29) -> "manufacturer"
        BluetoothUuid.characteristic16(0x2A24) -> "model"
        BluetoothUuid.characteristic16(0x2A26) -> "firmware"
        BluetoothUuid.characteristic16(0x2A28) -> "software"
        else -> "unknown"
    }

private const val RSC_TOTAL_DISTANCE_UNITS_PER_METER = 10.0
private const val CSC_FLAG_WHEEL_REVOLUTION_DATA_PRESENT = 0x01
private const val CSC_FLAG_CRANK_REVOLUTION_DATA_PRESENT = 0x02
private const val BLE_EVENT_TIME_TICKS_PER_SECOND = 1024.0
private const val UINT16_ROLLOVER = 65_536L
private const val UINT32_ROLLOVER = 4_294_967_296L
private const val CYCLING_SPEED_MAX_MPS = 30f
private const val CYCLING_CADENCE_MAX_RPM = 250
private const val MAX_GATT_SERVICES_TO_LOG = 16
private const val MAX_GATT_CHARACTERISTICS_PER_SERVICE_TO_LOG = 16
private const val CUSTOM_RAW_LOG_INITIAL_SAMPLES = 6
private const val CUSTOM_RAW_LOG_INTERVAL = 60
private const val MAX_DEVICE_INFO_TEXT_LENGTH = 48

private fun unsignedDelta(
    current: Long,
    previous: Long,
    rollover: Long,
): Long =
    if (current >= previous) {
        current - previous
    } else {
        current + rollover - previous
    }
