package com.glancemap.glancemapwearos.presentation.features.recording.external

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExternalSensorScanner(
    private val context: Context,
) {
    private val devicesByAddress = linkedMapOf<String, ExternalSensorDevice>()
    private val loggedDeviceAddresses = mutableSetOf<String>()
    private val _devices = MutableStateFlow<List<ExternalSensorDevice>>(emptyList())
    private val _status = MutableStateFlow(ExternalSensorScanStatus.IDLE)
    private var scanStartedAtElapsedMs = 0L
    private var scanResultCount = 0
    private var scanActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopScan(outcome = "timeout") }

    val devices: StateFlow<List<ExternalSensorDevice>> = _devices.asStateFlow()
    val status: StateFlow<ExternalSensorScanStatus> = _status.asStateFlow()

    private val callback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                scanResultCount += 1
                mergeResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                scanResultCount += results.size
                results.forEach(::mergeResult)
            }

            override fun onScanFailed(errorCode: Int) {
                scanActive = false
                handler.removeCallbacks(scanTimeoutRunnable)
                _status.value = ExternalSensorScanStatus.SCAN_FAILED
                DebugTelemetry.log("ExternalSensors", "event=scan_failed code=$errorCode")
                logScanSummary(outcome = "failed")
            }
        }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanActive) return
        if (!hasRequiredPermissions(context)) {
            _status.value = ExternalSensorScanStatus.PERMISSION_MISSING
            DebugTelemetry.log("ExternalSensors", "event=scan_unavailable reason=permission_missing")
            return
        }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
        if (adapter == null) {
            _status.value = ExternalSensorScanStatus.BLUETOOTH_UNAVAILABLE
            DebugTelemetry.log("ExternalSensors", "event=scan_unavailable reason=adapter_unavailable")
            return
        }
        if (!adapter.isEnabled) {
            _status.value = ExternalSensorScanStatus.BLUETOOTH_OFF
            DebugTelemetry.log("ExternalSensors", "event=scan_unavailable reason=bluetooth_off")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _status.value = ExternalSensorScanStatus.BLUETOOTH_UNAVAILABLE
            DebugTelemetry.log("ExternalSensors", "event=scan_unavailable reason=scanner_unavailable")
            return
        }

        scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
        scanResultCount = 0
        runCatching {
            scanner.startScan(
                null,
                ScanSettings
                    .Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
        }.onSuccess {
            scanActive = true
            _status.value = ExternalSensorScanStatus.SCANNING
            handler.removeCallbacks(scanTimeoutRunnable)
            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
            DebugTelemetry.log("ExternalSensors", "event=scan_started")
        }.onFailure { error ->
            _status.value = ExternalSensorScanStatus.SCAN_FAILED
            DebugTelemetry.log("ExternalSensors", "event=scan_start_failed error=${error.javaClass.simpleName}")
            logScanSummary(outcome = "start_failed")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        stopScan(outcome = "stopped")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(outcome: String) {
        if (!scanActive) return
        scanActive = false
        handler.removeCallbacks(scanTimeoutRunnable)
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
        val scanner = adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(callback) }
        if (_status.value == ExternalSensorScanStatus.SCANNING) {
            _status.value = ExternalSensorScanStatus.IDLE
        }
        logScanSummary(outcome = outcome)
    }

    private fun mergeResult(result: ScanResult) {
        val address = safeAddress(result) ?: return
        val serviceUuids =
            result.scanRecord
                ?.serviceUuids
                .orEmpty()
                .map { it.uuid }
                .toSet()
        val kinds = ExternalSensorKind.entries.filter { it.serviceUuid in serviceUuids }.toSet()
        val name =
            safeName(
                result,
            ).ifBlank { context.getString(R.string.recording_ble_device_name, address.takeLast(5)) }
        if (loggedDeviceAddresses.add(address)) {
            val scanRecord = result.scanRecord
            val manufacturerIds =
                scanRecord
                    ?.manufacturerSpecificData
                    ?.let { data -> (0 until data.size()).map(data::keyAt) }
                    .orEmpty()
            val serviceDataUuids = scanRecord?.serviceData?.keys.orEmpty()
            DebugTelemetry.log(
                "ExternalSensors",
                "event=device_seen name=${name.sanitizeTelemetryToken()} " +
                    "addressSuffix=${address.takeLast(5)} " +
                    "rssi=${result.rssi} txPower=${scanRecord?.txPowerLevel ?: Int.MIN_VALUE} " +
                    "kinds=${kinds.joinToString(
                        "|",
                    ) { it.telemetryLabel.sanitizeTelemetryToken() }.ifBlank { "unknown" }} " +
                    "services=${serviceUuids.joinToString("|") { it.toString() }.ifBlank { "none" }} " +
                    "serviceData=${serviceDataUuids.joinToString("|") { it.toString() }.ifBlank { "none" }} " +
                    "manufacturerIds=${manufacturerIds.joinToString("|").ifBlank { "none" }}",
            )
        }
        devicesByAddress[address] =
            ExternalSensorDevice(
                name = name,
                address = address,
                rssi = result.rssi,
                kinds = kinds,
                lastSeenAtMillis = System.currentTimeMillis(),
            )
        _devices.value =
            devicesByAddress.values
                .sortedWith(
                    compareByDescending<ExternalSensorDevice> { it.kinds.isNotEmpty() }
                        .thenByDescending { it.rssi ?: Int.MIN_VALUE },
                )
    }

    private fun logScanSummary(outcome: String) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val durationMs =
            scanStartedAtElapsedMs
                .takeIf { it > 0L }
                ?.let { startedAt -> (nowElapsedMs - startedAt).coerceAtLeast(0L) }
                ?: 0L
        val devices = devicesByAddress.values
        val supportedCount = devices.count { it.kinds.isNotEmpty() }
        val heartRateCount = devices.count { ExternalSensorKind.HEART_RATE in it.kinds }
        val runPodCount = devices.count { ExternalSensorKind.RUNNING_SPEED_CADENCE in it.kinds }
        val cyclingCount = devices.count { ExternalSensorKind.CYCLING_SPEED_CADENCE in it.kinds }
        val cyclingPowerCount = devices.count { ExternalSensorKind.CYCLING_POWER in it.kinds }
        DebugTelemetry.log(
            "ExternalSensors",
            "event=scan_summary outcome=$outcome durationMs=$durationMs resultCount=$scanResultCount " +
                "devices=${devices.size} supported=$supportedCount unknown=${devices.size - supportedCount} " +
                "heartRate=$heartRateCount runPod=$runPodCount cyclingSpeedCadence=$cyclingCount " +
                "cyclingPower=$cyclingPowerCount",
        )
    }

    @SuppressLint("MissingPermission")
    private fun safeName(result: ScanResult): String =
        result.scanRecord?.deviceName
            ?: if (hasConnectPermission(context)) {
                runCatching { result.device.name }.getOrNull()
            } else {
                null
            }
            ?: ""

    private fun safeAddress(result: ScanResult): String? = runCatching { result.device.address }.getOrNull()

    companion object {
        private const val SCAN_TIMEOUT_MS = 10_000L

        fun requiredPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasRequiredPermissions(context: Context): Boolean =
            requiredPermissions().all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
    }
}

private fun String.sanitizeTelemetryToken(): String =
    replace(' ', '_')
        .replace('|', '_')
        .replace('=', '_')
