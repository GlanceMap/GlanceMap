package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalHeartRateSensorBridge
import com.glancemap.glancemapwearos.presentation.features.recording.external.ExternalRunPodSensorBridge
import com.glancemap.glancemapwearos.presentation.features.recording.usesHybridRecordingElevation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

data class RecordingSensorMetrics(
    val heartRateBpm: Int? = null,
    val heartRateUpdatedAtMillis: Long = 0L,
    val heartRateSensorEventCount: Long = 0L,
    val stepCount: Int? = null,
    val stepCountUpdatedAtMillis: Long = 0L,
    val stepCountFromBluetooth: Boolean = false,
    val cadenceSpm: Int? = null,
    val cadenceUpdatedAtMillis: Long = 0L,
    val cadenceFromBluetooth: Boolean = false,
    val externalSpeedMps: Float? = null,
    val externalSpeedUpdatedAtMillis: Long = 0L,
    val externalDistanceRawUnits: Long? = null,
    val externalDistanceMeters: Double? = null,
    val externalDistanceUpdatedAtMillis: Long = 0L,
    val externalPowerWatts: Int? = null,
    val externalPowerUpdatedAtMillis: Long = 0L,
    val externalBatteryLevelPercent: Int? = null,
    val externalBatteryUpdatedAtMillis: Long = 0L,
    val barometricPressureHpa: Double? = null,
    val barometricPressureUpdatedAtMillis: Long = 0L,
    val barometricPressureSensorEventCount: Long = 0L,
    val heartRateFromBluetooth: Boolean = false,
)

internal fun RecordingSensorMetrics.withExternalRunPodUnavailable(
    clearCadence: Boolean,
    clearPower: Boolean,
): RecordingSensorMetrics =
    copy(
        cadenceSpm = if (clearCadence) null else cadenceSpm,
        cadenceUpdatedAtMillis = if (clearCadence) 0L else cadenceUpdatedAtMillis,
        cadenceFromBluetooth = if (clearCadence) true else cadenceFromBluetooth,
        externalSpeedMps = null,
        externalSpeedUpdatedAtMillis = 0L,
        externalDistanceRawUnits = null,
        externalDistanceMeters = null,
        externalDistanceUpdatedAtMillis = 0L,
        externalPowerWatts = if (clearPower) null else externalPowerWatts,
        externalPowerUpdatedAtMillis = if (clearPower) 0L else externalPowerUpdatedAtMillis,
    )

internal data class StepCounterReading(
    val steps: Int,
    val cadenceSpm: Int?,
)

internal class RecordingSensorRuntimeState {
    private var stepCountOffset = 0
    private var stepCounterBase: Float? = null
    private var lastStepCounterValue: Float? = null
    private var lastStepCounterTimeMs = 0L
    private val stepDetectorEventTimes = ArrayDeque<Long>()

    @Synchronized
    fun reset() {
        stepCountOffset = 0
        stepCounterBase = null
        lastStepCounterValue = null
        lastStepCounterTimeMs = 0L
        stepDetectorEventTimes.clear()
    }

    @Synchronized
    fun prepareRecoveredStepCount(stepCount: Int?): Boolean {
        val recovered = stepCount?.coerceAtLeast(0)
        val shouldUpdate =
            stepCounterBase == null &&
                recovered != null &&
                recovered > stepCountOffset
        if (shouldUpdate) {
            stepCountOffset = checkNotNull(recovered)
        }
        return shouldUpdate
    }

    @Synchronized
    fun updateStepCounter(
        value: Float,
        nowMillis: Long,
    ): StepCounterReading {
        val base = stepCounterBase ?: value.also { stepCounterBase = it }
        val steps = stepCountOffset + (value - base).roundToInt().coerceAtLeast(0)
        val previousValue = lastStepCounterValue
        val previousTimeMs = lastStepCounterTimeMs
        val cadence =
            if (previousValue != null && previousTimeMs > 0L && nowMillis > previousTimeMs) {
                val deltaSteps = (value - previousValue).coerceAtLeast(0f)
                val deltaMinutes = (nowMillis - previousTimeMs) / 60_000.0
                (deltaSteps / deltaMinutes).roundToInt().takeIf { it > 0 }
            } else {
                null
            }
        lastStepCounterValue = value
        lastStepCounterTimeMs = nowMillis
        return StepCounterReading(steps = steps, cadenceSpm = cadence)
    }

    @Synchronized
    fun updateStepDetector(nowMillis: Long): Int? {
        stepDetectorEventTimes.addLast(nowMillis)
        while (stepDetectorEventTimes.firstOrNull()?.let { nowMillis - it > CADENCE_WINDOW_MS } == true) {
            stepDetectorEventTimes.removeFirst()
        }
        return ((stepDetectorEventTimes.size * 60_000.0) / CADENCE_WINDOW_MS)
            .roundToInt()
            .takeIf { it > 0 }
    }
}

// This effect coordinator keeps all sensor bridges under one Compose lifecycle and shared metrics snapshot.
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun RecordingSensorBridge(
    active: Boolean,
    paused: Boolean,
    selectedMetricIds: List<String>,
    elevationSource: String,
    heartRateSource: String,
    cadenceSource: String,
    speedSource: String,
    distanceSource: String,
    stepsSource: String,
    externalHeartRateAddress: String?,
    externalRunPodAddress: String?,
    cyclingWheelCircumferenceMeters: Float,
    activityProfile: String,
    initialStepCount: Int?,
    onMetrics: (RecordingSensorMetrics) -> Unit,
) {
    val context = LocalContext.current
    val externalHeartRateLinked = !externalHeartRateAddress.isNullOrBlank()
    val useExternalHeartRate =
        externalHeartRateLinked &&
            heartRateSource == SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP
    val useWatchHeartRate =
        heartRateSource == SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH
    val externalRunPodLinked = !externalRunPodAddress.isNullOrBlank()
    val isBikeProfile = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    val useExternalCadence =
        externalRunPodLinked &&
            cadenceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD
    val useInternalCadence =
        !isBikeProfile &&
            cadenceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS
    val useInternalSteps =
        !isBikeProfile &&
            stepsSource == SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS
    val useExternalSpeed =
        externalRunPodLinked && speedSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD
    val useExternalDistance =
        externalRunPodLinked && distanceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD
    val powerMetricSelected =
        SettingsRepository.RECORDING_METRIC_POWER in selectedMetricIds ||
            SettingsRepository.RECORDING_METRIC_AVERAGE_POWER in selectedMetricIds ||
            SettingsRepository.RECORDING_METRIC_MAX_POWER in selectedMetricIds
    val externalRunPodSelected =
        useExternalCadence || useExternalSpeed || useExternalDistance || powerMetricSelected
    val useExternalPower =
        externalRunPodLinked &&
            externalRunPodSelected
    val useExternalRunPod =
        useExternalCadence || useExternalSpeed || useExternalDistance || useExternalPower
    val collectBarometricPressure =
        shouldCollectRecordingBarometricPressure(
            active = active,
            elevationSource = elevationSource,
            selectedMetricIds = selectedMetricIds,
        )
    val collectInternalSteps = active && useInternalSteps
    val sensorMetricIds =
        remember(selectedMetricIds, useWatchHeartRate, useInternalCadence, useInternalSteps, collectInternalSteps, collectBarometricPressure) {
            val filteredMetricIds =
                selectedMetricIds
                    .filter { it in recordingSensorMetricIds }
                    .filterNot { !useWatchHeartRate && it in heartRateSensorMetricIds }
                    .filterNot { !useInternalCadence && it in cadenceSensorMetricIds }
                    .filterNot { !useInternalSteps && it == SettingsRepository.RECORDING_METRIC_STEPS }
            val withActivityDetailMetrics =
                if (collectInternalSteps) {
                    (filteredMetricIds + SettingsRepository.RECORDING_METRIC_STEPS).distinct()
                } else {
                    filteredMetricIds
                }
            if (collectBarometricPressure) {
                (withActivityDetailMetrics + SettingsRepository.RECORDING_METRIC_BAROMETRIC_PRESSURE).distinct()
            } else {
                withActivityDetailMetrics
            }
        }
    var permissionResultVersion by remember { mutableIntStateOf(0) }
    val permissionsToRequest =
        remember(context, sensorMetricIds, permissionResultVersion) {
            recordingSensorPermissionsToRequest(context, sensorMetricIds)
        }
    val permissionsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionResultVersion += 1
            DebugTelemetry.log(
                "TraceRecordingSensors",
                "event=permissions_result " +
                    "body=${result[Manifest.permission.BODY_SENSORS] ?: true} " +
                    "activity=${result[activityRecognitionPermission()] ?: true}",
            )
        }
    var metrics by remember { mutableStateOf(RecordingSensorMetrics()) }
    val sensorRuntimeState = remember { RecordingSensorRuntimeState() }
    val heartRateSensorEventCount = remember { AtomicLong(0L) }
    val pressureSensorEventCount = remember { AtomicLong(0L) }
    if (active && sensorRuntimeState.prepareRecoveredStepCount(initialStepCount)) {
        DebugTelemetry.log(
            "TraceRecordingSensors",
            "event=step_offset_recovered steps=${initialStepCount ?: 0}",
        )
    }

    ExternalHeartRateSensorBridge(
        active = active && useExternalHeartRate,
        paused = paused,
        address = externalHeartRateAddress,
        onHeartRate = { bpm, timeMillis ->
            metrics =
                metrics.copy(
                    heartRateBpm = bpm,
                    heartRateUpdatedAtMillis = timeMillis,
                    heartRateFromBluetooth = true,
                )
            onMetrics(metrics)
        },
        onUnavailable = {
            DebugTelemetry.log("TraceRecordingSensors", "event=external_heart_rate_unavailable")
            metrics =
                metrics.copy(
                    heartRateBpm = null,
                    heartRateUpdatedAtMillis = 0L,
                    heartRateFromBluetooth = true,
                )
            onMetrics(metrics)
        },
    )
    ExternalRunPodSensorBridge(
        active = active && useExternalRunPod,
        paused = paused,
        address = externalRunPodAddress,
        wheelCircumferenceMeters = cyclingWheelCircumferenceMeters,
        onMeasurement = { measurement ->
            metrics =
                metrics.copy(
                    cadenceSpm =
                        if (useExternalCadence) {
                            measurement.cadenceSpm ?: metrics.cadenceSpm
                        } else {
                            metrics.cadenceSpm
                        },
                    cadenceUpdatedAtMillis =
                        if (useExternalCadence && measurement.cadenceSpm != null) {
                            measurement.timeMillis
                        } else {
                            metrics.cadenceUpdatedAtMillis
                        },
                    cadenceFromBluetooth =
                        if (useExternalCadence && measurement.cadenceSpm != null) {
                            true
                        } else {
                            metrics.cadenceFromBluetooth
                        },
                    externalSpeedMps = measurement.speedMps ?: metrics.externalSpeedMps,
                    externalSpeedUpdatedAtMillis =
                        if (measurement.speedMps != null) {
                            measurement.timeMillis
                        } else {
                            metrics.externalSpeedUpdatedAtMillis
                        },
                    externalDistanceMeters = measurement.totalDistanceMeters ?: metrics.externalDistanceMeters,
                    externalDistanceRawUnits =
                        measurement.rawTotalDistanceUnits ?: metrics.externalDistanceRawUnits,
                    externalDistanceUpdatedAtMillis =
                        if (measurement.totalDistanceMeters != null) {
                            measurement.timeMillis
                        } else {
                            metrics.externalDistanceUpdatedAtMillis
                        },
                    externalPowerWatts =
                        if (useExternalPower) {
                            measurement.powerWatts ?: metrics.externalPowerWatts
                        } else {
                            metrics.externalPowerWatts
                        },
                    externalPowerUpdatedAtMillis =
                        if (useExternalPower && measurement.powerWatts != null) {
                            measurement.timeMillis
                        } else {
                            metrics.externalPowerUpdatedAtMillis
                        },
                    externalBatteryLevelPercent = measurement.batteryLevelPercent ?: metrics.externalBatteryLevelPercent,
                    externalBatteryUpdatedAtMillis =
                        if (measurement.batteryLevelPercent != null) {
                            measurement.timeMillis
                        } else {
                            metrics.externalBatteryUpdatedAtMillis
                        },
                )
            onMetrics(metrics)
        },
        onUnavailable = {
            DebugTelemetry.log("TraceRecordingSensors", "event=external_run_pod_unavailable")
            metrics =
                metrics.withExternalRunPodUnavailable(
                    clearCadence = useExternalCadence,
                    clearPower = useExternalPower,
                )
            onMetrics(metrics)
        },
    )

    LaunchedEffect(active) {
        if (!active) {
            metrics = RecordingSensorMetrics()
            sensorRuntimeState.reset()
            heartRateSensorEventCount.set(0L)
            pressureSensorEventCount.set(0L)
            onMetrics(metrics)
        }
    }

    LaunchedEffect(active, sensorMetricIds, permissionsToRequest) {
        if (active && sensorMetricIds.isNotEmpty() && permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    LaunchedEffect(
        context,
        active,
        paused,
        sensorMetricIds,
        permissionsToRequest,
        heartRateSource,
        cadenceSource,
        speedSource,
        distanceSource,
        stepsSource,
        activityProfile,
        useExternalHeartRate,
        useExternalCadence,
        useExternalPower,
        useExternalRunPod,
    ) {
        if (!active) return@LaunchedEffect
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        while (isActive) {
            logRecordingSensorStatus(
                context = context,
                sensorManager = sensorManager,
                selectedMetricIds = sensorMetricIds,
                heartRateSource = heartRateSource,
                externalHeartRateLinked = externalHeartRateLinked,
                useExternalHeartRate = useExternalHeartRate,
                useWatchHeartRate = useWatchHeartRate,
                externalRunPodLinked = externalRunPodLinked,
                externalRunPodSelected = externalRunPodSelected,
                cadenceSource = cadenceSource,
                speedSource = speedSource,
                distanceSource = distanceSource,
                stepsSource = stepsSource,
                activityProfile = activityProfile,
                useExternalCadence = useExternalCadence,
                externalPowerMetricSelected = powerMetricSelected,
                useExternalPower = useExternalPower,
                useExternalRunPod = useExternalRunPod,
                paused = paused,
                event = "status",
            )
            delay(RECORDING_SENSOR_STATUS_INTERVAL_MS)
        }
    }

    DisposableEffect(
        context,
        active,
        paused,
        sensorMetricIds,
        permissionsToRequest,
        heartRateSource,
        cadenceSource,
        speedSource,
        distanceSource,
        stepsSource,
        activityProfile,
        useExternalHeartRate,
        useExternalCadence,
        useExternalRunPod,
    ) {
        if (!active || paused || sensorMetricIds.isEmpty()) {
            return@DisposableEffect onDispose {}
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val mainHandler = Handler(Looper.getMainLooper())
        val sensorThread = HandlerThread(RECORDING_SENSOR_THREAD_NAME).apply { start() }
        val sensorHandler = Handler(sensorThread.looper)
        val disposed = AtomicBoolean(false)
        var lastHeartRatePublishedAtElapsedMs = 0L
        var lastHeartRatePublishedBpm: Int? = null
        var lastPressurePublishedAtElapsedMs = 0L
        var lastPressurePublishedHpa: Double? = null

        fun publishSensorUpdate(update: (RecordingSensorMetrics) -> RecordingSensorMetrics) {
            mainHandler.post {
                if (disposed.get()) return@post
                val updatedMetrics = update(metrics)
                metrics = updatedMetrics
                onMetrics(updatedMetrics)
            }
        }

        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_HEART_RATE -> {
                            val bpm =
                                event.values
                                    .firstOrNull()
                                    ?.roundToInt()
                                    ?.takeIf { it > 0 }
                            val rawEventCount = heartRateSensorEventCount.incrementAndGet()
                            val now = System.currentTimeMillis()
                            val nowElapsed = SystemClock.elapsedRealtime()
                            if (
                                !RecordingSensorPublishPolicy.shouldPublishWatchHeartRate(
                                    nowElapsedMs = nowElapsed,
                                    lastPublishedAtElapsedMs = lastHeartRatePublishedAtElapsedMs,
                                    bpm = bpm,
                                    lastPublishedBpm = lastHeartRatePublishedBpm,
                                )
                            ) {
                                return
                            }
                            lastHeartRatePublishedAtElapsedMs = nowElapsed
                            lastHeartRatePublishedBpm = bpm
                            publishSensorUpdate { current ->
                                current.copy(
                                    heartRateBpm = bpm,
                                    heartRateUpdatedAtMillis =
                                        if (bpm != null) now else current.heartRateUpdatedAtMillis,
                                    heartRateSensorEventCount = rawEventCount,
                                    heartRateFromBluetooth = false,
                                )
                            }
                        }
                        Sensor.TYPE_STEP_COUNTER -> {
                            val value = event.values.firstOrNull() ?: return
                            val now = System.currentTimeMillis()
                            val reading = sensorRuntimeState.updateStepCounter(value = value, nowMillis = now)
                            publishSensorUpdate { current ->
                                current.copy(
                                    stepCount = reading.steps,
                                    stepCountUpdatedAtMillis = now,
                                    stepCountFromBluetooth = false,
                                    cadenceSpm =
                                        if (useInternalCadence) {
                                            reading.cadenceSpm ?: current.cadenceSpm
                                        } else {
                                            current.cadenceSpm
                                        },
                                    cadenceUpdatedAtMillis =
                                        if (useInternalCadence && reading.cadenceSpm != null) {
                                            now
                                        } else {
                                            current.cadenceUpdatedAtMillis
                                        },
                                    cadenceFromBluetooth =
                                        if (useInternalCadence && reading.cadenceSpm != null) {
                                            false
                                        } else {
                                            current.cadenceFromBluetooth
                                        },
                                )
                            }
                        }
                        Sensor.TYPE_STEP_DETECTOR -> {
                            val now = System.currentTimeMillis()
                            val cadence = sensorRuntimeState.updateStepDetector(nowMillis = now)
                            publishSensorUpdate { current ->
                                current.copy(
                                    cadenceSpm =
                                        if (useInternalCadence) {
                                            cadence ?: current.cadenceSpm
                                        } else {
                                            current.cadenceSpm
                                        },
                                    cadenceUpdatedAtMillis =
                                        if (useInternalCadence && cadence != null) {
                                            now
                                        } else {
                                            current.cadenceUpdatedAtMillis
                                        },
                                    cadenceFromBluetooth =
                                        if (useInternalCadence && cadence != null) {
                                            false
                                        } else {
                                            current.cadenceFromBluetooth
                                        },
                                )
                            }
                        }
                        Sensor.TYPE_PRESSURE -> {
                            val pressure =
                                event.values
                                    .firstOrNull()
                                    ?.toDouble()
                                    ?.takeIf { it > 0.0 }
                                    ?: return
                            val rawEventCount = pressureSensorEventCount.incrementAndGet()
                            val now = System.currentTimeMillis()
                            val nowElapsed = SystemClock.elapsedRealtime()
                            val elapsedSincePublish = nowElapsed - lastPressurePublishedAtElapsedMs
                            val meaningfullyChanged =
                                lastPressurePublishedHpa
                                    ?.let { abs(pressure - it) >= PRESSURE_MEANINGFUL_CHANGE_HPA }
                                    ?: true
                            val shouldPublish =
                                lastPressurePublishedAtElapsedMs == 0L ||
                                    elapsedSincePublish >= PRESSURE_UI_PUBLISH_INTERVAL_MS ||
                                    (
                                        meaningfullyChanged &&
                                            elapsedSincePublish >= PRESSURE_MEANINGFUL_CHANGE_MIN_INTERVAL_MS
                                    )
                            if (!shouldPublish) return
                            lastPressurePublishedAtElapsedMs = nowElapsed
                            lastPressurePublishedHpa = pressure
                            publishSensorUpdate { current ->
                                current.copy(
                                    barometricPressureHpa = pressure,
                                    barometricPressureUpdatedAtMillis = now,
                                    barometricPressureSensorEventCount = rawEventCount,
                                )
                            }
                        }
                    }
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }

        val registered =
            registerRecordingSensors(
                sensorManager = sensorManager,
                listener = listener,
                selectedMetricIds = sensorMetricIds,
                context = context,
                handler = sensorHandler,
            )
        EnergyDiagnostics.recordRecordingSensorsRegistered(registered)
        logRecordingSensorStatus(
            context = context,
            sensorManager = sensorManager,
            selectedMetricIds = sensorMetricIds,
            heartRateSource = heartRateSource,
            externalHeartRateLinked = externalHeartRateLinked,
            useExternalHeartRate = useExternalHeartRate,
            useWatchHeartRate = useWatchHeartRate,
            externalRunPodLinked = externalRunPodLinked,
            externalRunPodSelected = externalRunPodSelected,
            cadenceSource = cadenceSource,
            speedSource = speedSource,
            distanceSource = distanceSource,
            stepsSource = stepsSource,
            activityProfile = activityProfile,
            useExternalCadence = useExternalCadence,
            externalPowerMetricSelected = powerMetricSelected,
            useExternalPower = useExternalPower,
            useExternalRunPod = useExternalRunPod,
            registered = registered,
            paused = paused,
            event = "register",
        )

        onDispose {
            disposed.set(true)
            sensorManager.unregisterListener(listener)
            EnergyDiagnostics.recordRecordingSensorsUnregistered(registered)
            sensorThread.quitSafely()
            DebugTelemetry.log("TraceRecordingSensors", "event=unregister")
        }
    }
}

@Suppress("LongParameterList")
private fun logRecordingSensorStatus(
    context: Context,
    sensorManager: SensorManager,
    selectedMetricIds: List<String>,
    heartRateSource: String,
    externalHeartRateLinked: Boolean,
    useExternalHeartRate: Boolean,
    useWatchHeartRate: Boolean,
    externalRunPodLinked: Boolean,
    externalRunPodSelected: Boolean,
    cadenceSource: String,
    speedSource: String,
    distanceSource: String,
    stepsSource: String,
    activityProfile: String,
    useExternalCadence: Boolean,
    externalPowerMetricSelected: Boolean,
    useExternalPower: Boolean,
    useExternalRunPod: Boolean,
    registered: List<String>? = null,
    paused: Boolean,
    event: String,
) {
    val available = availableRecordingSensors(sensorManager)
    val bodySensorsGranted = hasPermission(context, Manifest.permission.BODY_SENSORS)
    val activityRecognitionGranted = hasActivityRecognitionPermission(context)
    val externalHeartRateBridgeRequested = useExternalHeartRate && !paused
    val externalPowerBridgeRequested = useExternalPower && !paused
    val externalRunPodBridgeRequested = useExternalRunPod && !paused
    val requested = selectedMetricIds.joinToString("|").ifBlank { "none" }
    val registeredText = registered?.joinToString("|")?.ifBlank { "none" } ?: "unknown"
    DebugTelemetry.log(
        "TraceRecordingSensors",
        "event=$event requested=$requested " +
            "registered=$registeredText " +
            "available=${available.joinToString("|").ifBlank { "none" }} " +
            "activityProfile=$activityProfile " +
            "heartRateSource=$heartRateSource " +
            "externalHeartRateLinked=$externalHeartRateLinked " +
            "externalHeartRateSelected=$useExternalHeartRate " +
            "externalHeartRateBridgeRequested=$externalHeartRateBridgeRequested " +
            "watchHeartRateActive=$useWatchHeartRate " +
            "externalRunPodLinked=$externalRunPodLinked " +
            "externalRunPodSelected=$externalRunPodSelected " +
            "cadenceSource=$cadenceSource " +
            "speedSource=$speedSource " +
            "distanceSource=$distanceSource " +
            "stepsSource=$stepsSource " +
            "externalCadenceSelected=$useExternalCadence " +
            "externalPowerMetricSelected=$externalPowerMetricSelected " +
            "externalPowerBridgeRequested=$externalPowerBridgeRequested " +
            "externalRunPodBridgeRequested=$externalRunPodBridgeRequested " +
            "paused=$paused " +
            "bodySensorsGranted=$bodySensorsGranted " +
            "activityRecognitionGranted=$activityRecognitionGranted",
    )
}

private fun availableRecordingSensors(sensorManager: SensorManager): List<String> =
    buildList {
        if (sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null) {
            add("heart_rate")
        }
        if (sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) {
            add("step_counter")
        }
        if (sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null) {
            add("step_detector")
        }
        if (sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) {
            add("pressure")
        }
    }

fun recordingSensorMetricsSelected(metricIds: List<String>): Boolean = metricIds.any { it in recordingSensorMetricIds }

private fun registerRecordingSensors(
    sensorManager: SensorManager,
    listener: SensorEventListener,
    selectedMetricIds: List<String>,
    context: Context,
    handler: Handler,
): List<String> {
    val registered = mutableListOf<String>()

    fun register(
        type: Int,
        token: String,
    ) {
        val sensor = sensorManager.getDefaultSensor(type) ?: return
        val samplingPeriodUs =
            if (type == Sensor.TYPE_HEART_RATE || type == Sensor.TYPE_PRESSURE) {
                RECORDING_CONTINUOUS_SENSOR_PERIOD_US
            } else {
                SensorManager.SENSOR_DELAY_NORMAL
            }
        val maxReportLatencyUs =
            if (type == Sensor.TYPE_HEART_RATE || type == Sensor.TYPE_PRESSURE) {
                RECORDING_CONTINUOUS_SENSOR_MAX_REPORT_LATENCY_US
            } else {
                0
            }
        if (
            sensorManager.registerListener(
                listener,
                sensor,
                samplingPeriodUs,
                maxReportLatencyUs,
                handler,
            )
        ) {
            registered += token
        }
    }

    if (
        selectedMetricIds.any { it in heartRateSensorMetricIds } &&
        hasPermission(context, Manifest.permission.BODY_SENSORS)
    ) {
        register(Sensor.TYPE_HEART_RATE, "heart_rate")
    }
    if (
        (
            SettingsRepository.RECORDING_METRIC_STEPS in selectedMetricIds ||
                selectedMetricIds.any { it in cadenceSensorMetricIds }
        ) &&
        hasActivityRecognitionPermission(context)
    ) {
        register(Sensor.TYPE_STEP_COUNTER, "step_counter")
        register(Sensor.TYPE_STEP_DETECTOR, "step_detector")
    }
    if (SettingsRepository.RECORDING_METRIC_BAROMETRIC_PRESSURE in selectedMetricIds) {
        register(Sensor.TYPE_PRESSURE, "pressure")
    }
    return registered
}

private fun recordingSensorPermissionsToRequest(
    context: Context,
    selectedMetricIds: List<String>,
): List<String> =
    buildList {
        if (
            selectedMetricIds.any { it in heartRateSensorMetricIds } &&
            !hasPermission(context, Manifest.permission.BODY_SENSORS)
        ) {
            add(Manifest.permission.BODY_SENSORS)
        }
        if (
            (
                SettingsRepository.RECORDING_METRIC_STEPS in selectedMetricIds ||
                    selectedMetricIds.any { it in cadenceSensorMetricIds }
            ) &&
            !hasActivityRecognitionPermission(context)
        ) {
            add(activityRecognitionPermission())
        }
    }

private fun hasActivityRecognitionPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)

private fun activityRecognitionPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Manifest.permission.ACTIVITY_RECOGNITION
    } else {
        ""
    }

private fun hasPermission(
    context: Context,
    permission: String,
): Boolean =
    permission.isBlank() ||
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun shouldCollectRecordingBarometricPressure(
    active: Boolean,
    elevationSource: String,
    selectedMetricIds: List<String>,
): Boolean =
    active &&
        (
            elevationSource.usesHybridRecordingElevation() ||
                SettingsRepository.RECORDING_METRIC_BAROMETRIC_PRESSURE in selectedMetricIds
        )

private val recordingSensorMetricIds =
    setOf(
        SettingsRepository.RECORDING_METRIC_HEART_RATE,
        SettingsRepository.RECORDING_METRIC_MAX_HEART_RATE,
        SettingsRepository.RECORDING_METRIC_STEPS,
        SettingsRepository.RECORDING_METRIC_CADENCE,
        SettingsRepository.RECORDING_METRIC_AVERAGE_CADENCE,
        SettingsRepository.RECORDING_METRIC_MAX_CADENCE,
        SettingsRepository.RECORDING_METRIC_BAROMETRIC_PRESSURE,
    )

private val heartRateSensorMetricIds =
    setOf(
        SettingsRepository.RECORDING_METRIC_HEART_RATE,
        SettingsRepository.RECORDING_METRIC_MAX_HEART_RATE,
    )

private val cadenceSensorMetricIds =
    setOf(
        SettingsRepository.RECORDING_METRIC_CADENCE,
        SettingsRepository.RECORDING_METRIC_AVERAGE_CADENCE,
        SettingsRepository.RECORDING_METRIC_MAX_CADENCE,
    )

private const val CADENCE_WINDOW_MS = 30_000L
private const val RECORDING_SENSOR_STATUS_INTERVAL_MS = 60_000L
private const val RECORDING_SENSOR_THREAD_NAME = "RecordingSensorThread"
private const val RECORDING_CONTINUOUS_SENSOR_PERIOD_US = 1_000_000
private const val RECORDING_CONTINUOUS_SENSOR_MAX_REPORT_LATENCY_US = 1_000_000
private const val HEART_RATE_UI_PUBLISH_INTERVAL_MS = 1_000L
private const val HEART_RATE_MEANINGFUL_CHANGE_MIN_INTERVAL_MS = 250L
private const val HEART_RATE_MEANINGFUL_CHANGE_BPM = 5
private const val PRESSURE_UI_PUBLISH_INTERVAL_MS = 1_000L
private const val PRESSURE_MEANINGFUL_CHANGE_MIN_INTERVAL_MS = 250L
private const val PRESSURE_MEANINGFUL_CHANGE_HPA = 0.5

internal object RecordingSensorPublishPolicy {
    fun shouldPublishWatchHeartRate(
        nowElapsedMs: Long,
        lastPublishedAtElapsedMs: Long,
        bpm: Int?,
        lastPublishedBpm: Int?,
    ): Boolean {
        if (lastPublishedAtElapsedMs == 0L) return true
        val elapsedSincePublish = nowElapsedMs - lastPublishedAtElapsedMs
        val meaningfullyChanged =
            bpm != null &&
                lastPublishedBpm != null &&
                abs(bpm - lastPublishedBpm) >= HEART_RATE_MEANINGFUL_CHANGE_BPM
        val minimumPublishIntervalMs =
            if (meaningfullyChanged) {
                HEART_RATE_MEANINGFUL_CHANGE_MIN_INTERVAL_MS
            } else {
                HEART_RATE_UI_PUBLISH_INTERVAL_MS
            }
        return elapsedSincePublish >= minimumPublishIntervalMs
    }
}
