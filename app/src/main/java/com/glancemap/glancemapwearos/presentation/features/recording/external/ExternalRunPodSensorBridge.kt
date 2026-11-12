@file:Suppress("FunctionNaming")

package com.glancemap.glancemapwearos.presentation.features.recording.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
@Suppress("LongMethod", "LongParameterList")
fun ExternalRunPodSensorBridge(
    active: Boolean,
    paused: Boolean,
    address: String?,
    wheelCircumferenceMeters: Float = SettingsRepository.DEFAULT_CYCLING_WHEEL_CIRCUMFERENCE_METERS,
    onMeasurement: (ExternalRunPodMeasurement) -> Unit,
    onUnavailable: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentMeasurement by rememberUpdatedState(onMeasurement)
    val currentUnavailable by rememberUpdatedState(onUnavailable)
    val simulated = ExternalSensorSimulation.isSimulatedAddress(address)

    LaunchedEffect(active, paused, address, simulated) {
        val linkedAddress = address?.takeIf(String::isNotBlank)
        if (!active || paused || linkedAddress == null || !simulated) return@LaunchedEffect
        ExternalSensorConnectionStatus.update(linkedAddress, true)
        ExternalSensorConnectionStatus.updateBattery(linkedAddress, SIMULATED_RUN_POD_BATTERY_PERCENT)
        ExternalRunPodRuntimeStatus.updateBattery(
            address = linkedAddress,
            batteryLevelPercent = SIMULATED_RUN_POD_BATTERY_PERCENT,
            timeMillis = System.currentTimeMillis(),
        )
        DebugTelemetry.log("ExternalRunPod", "event=simulation_start")
        var rawDistanceUnits = 0L
        var sampleIndex = 0
        while (isActive) {
            val speedMps = SIMULATED_RUN_POD_BASE_SPEED_MPS + (sampleIndex % 4) * 0.05f
            rawDistanceUnits += (speedMps * RSC_SIMULATED_DISTANCE_UNITS_PER_METER).toLong()
            val now = System.currentTimeMillis()
            val powerWatts = SIMULATED_RUN_POD_BASE_POWER_WATTS + (sampleIndex % 7)
            currentMeasurement(
                ExternalRunPodMeasurement(
                    speedMps = speedMps,
                    cadenceSpm = SIMULATED_RUN_POD_BASE_CADENCE_SPM + (sampleIndex % 5),
                    rawTotalDistanceUnits = rawDistanceUnits,
                    totalDistanceMeters = rawDistanceUnits / RSC_SIMULATED_DISTANCE_UNITS_PER_METER,
                    powerWatts = powerWatts,
                    batteryLevelPercent = SIMULATED_RUN_POD_BATTERY_PERCENT,
                    timeMillis = now,
                ),
            )
            ExternalRunPodRuntimeStatus.updatePower(linkedAddress, powerWatts, now)
            sampleIndex += 1
            delay(SIMULATED_SENSOR_SAMPLE_INTERVAL_MS)
        }
    }

    DisposableEffect(context, active, paused, address, wheelCircumferenceMeters) {
        val linkedAddress = address?.takeIf(String::isNotBlank)
        if (!active || paused || linkedAddress == null) {
            return@DisposableEffect onDispose {}
        }
        if (ExternalSensorSimulation.isSimulatedAddress(linkedAddress)) {
            return@DisposableEffect onDispose {
                ExternalSensorConnectionStatus.update(linkedAddress, false)
                ExternalRunPodRuntimeStatus.markDisconnected(linkedAddress)
                DebugTelemetry.log("ExternalRunPod", "event=simulation_stop")
            }
        }
        val client =
            ExternalRunPodClient(
                context = context.applicationContext,
                address = linkedAddress,
                wheelCircumferenceMeters = wheelCircumferenceMeters.toDouble(),
                onMeasurement = { currentMeasurement(it) },
                onUnavailable = { currentUnavailable() },
                autoReconnect = active && !paused,
            )
        DebugTelemetry.log("ExternalRunPod", "event=bridge_start address=${linkedAddress.takeLast(5)}")
        client.connect()
        onDispose {
            client.disconnect()
            DebugTelemetry.log("ExternalRunPod", "event=bridge_stop")
        }
    }
}

private const val SIMULATED_RUN_POD_BASE_SPEED_MPS = 2.8f
private const val SIMULATED_RUN_POD_BASE_CADENCE_SPM = 168
private const val SIMULATED_RUN_POD_BASE_POWER_WATTS = 235
private const val SIMULATED_RUN_POD_BATTERY_PERCENT = 74
private const val RSC_SIMULATED_DISTANCE_UNITS_PER_METER = 10.0
private const val SIMULATED_SENSOR_SAMPLE_INTERVAL_MS = 1_000L
