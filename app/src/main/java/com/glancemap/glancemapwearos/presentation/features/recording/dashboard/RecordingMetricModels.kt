package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.isRecordingDashboardMetricAllowedForProfile
import com.glancemap.glancemapwearos.presentation.features.gpx.FileSig
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.buildProfile
import com.glancemap.glancemapwearos.presentation.features.gpx.totalAscent
import com.glancemap.glancemapwearos.presentation.features.gpx.totalDescent
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.haversineMeters
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import java.text.DecimalFormat
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class RecordingMetricSource {
    INTERNAL_GPS,
    INTERNAL_SENSOR,
    EXTERNAL,
}

internal data class RecordingMetricDefinition(
    val id: String,
    val label: String,
    val source: RecordingMetricSource = RecordingMetricSource.INTERNAL_GPS,
)

internal data class RecordingMetricValue(
    val label: String,
    val value: String,
    val unit: String? = null,
    val bluetooth: Boolean = false,
)

data class RecordingCalorieEstimate(
    val grossKcal: Double = 0.0,
    val activeKcal: Double = 0.0,
    val restingKcal: Double = 0.0,
    val model: String = CALORIE_MODEL_HIKE_PANDOLF_SANTEE,
    val pandolfBaseGrossKcal: Double = 0.0,
    val pandolfBaseActiveKcal: Double = 0.0,
    val pandolfBaseRestingKcal: Double = 0.0,
    val lcdaGrossKcal: Double = 0.0,
    val lcdaActiveKcal: Double = 0.0,
    val lcdaRestingKcal: Double = 0.0,
    val cyclingMechanicalKj: Double = 0.0,
    val cyclingPowerSampleSegments: Int = 0,
    val cyclingPhysicsSegments: Int = 0,
)

data class RecordingDashboardSnapshot(
    val activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    val durationSeconds: Double,
    val totalDurationSeconds: Double = durationSeconds,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val currentElevationMeters: Double?,
    val currentSpeedMps: Float?,
    val externalSpeedMps: Float? = null,
    val averageSpeedMps: Double?,
    val fastestSpeedMps: Double? = null,
    val externalDistanceMeters: Double? = null,
    val gpsAccuracyMeters: Float?,
    val pointCount: Int,
    val gpsActiveDurationSeconds: Double,
    val recordingGapCount: Int,
    val recordingMaxGapSeconds: Double,
    val userWeightKg: Float = SettingsRepository.DEFAULT_USER_WEIGHT_KG,
    val backpackWeightKg: Float = SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG,
    val calorieEstimate: RecordingCalorieEstimate = RecordingCalorieEstimate(),
    val heartRateBpm: Int? = null,
    val heartRateFromBluetooth: Boolean = false,
    val averageHeartRateBpm: Int? = null,
    val stepCount: Int? = null,
    val stepCountFromBluetooth: Boolean = false,
    val cadenceSpm: Int? = null,
    val averageCadenceSpm: Int? = null,
    val maxCadenceSpm: Int? = null,
    val cadenceFromBluetooth: Boolean = false,
    val powerWatts: Int? = null,
    val averagePowerWatts: Int? = null,
    val maxPowerWatts: Int? = null,
    val powerFromBluetooth: Boolean = false,
    val maxHeartRateBpm: Int? = null,
    val barometricPressureHpa: Double? = null,
    val lastLiveFixAgeMillis: Long? = null,
    val lastRecordedPointAgeMillis: Long? = null,
    val speedSource: String = SettingsRepository.DEFAULT_RECORDING_SPEED_SOURCE,
    val distanceSource: String = SettingsRepository.DEFAULT_RECORDING_DISTANCE_SOURCE,
    val cadenceSource: String = SettingsRepository.DEFAULT_RECORDING_CADENCE_SOURCE,
    val stepsSource: String = SettingsRepository.DEFAULT_RECORDING_STEPS_SOURCE,
    val hasElevationData: Boolean = true,
)

internal val recordingMetricDefinitions =
    listOf(
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_DISTANCE, "Distance"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_TOTAL_TIME, "Time (Total)"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_DURATION, "Time (Active)"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_ELEVATION_GAIN, "Elev +"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_ELEVATION_LOSS, "Elev -"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_CURRENT_ELEVATION, "Altitude"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_CURRENT_SPEED, "Speed"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_AVERAGE_SPEED, "Speed (Avg)"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_MAX_SPEED, "Speed (Max)"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_CURRENT_PACE, "Pace"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_AVERAGE_PACE, "Pace (Avg)"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_MAX_PACE, "Pace (Max)"),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_HEART_RATE,
            "Heart rate",
            RecordingMetricSource.INTERNAL_SENSOR,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_MAX_HEART_RATE,
            "Heart rate (Max)",
            RecordingMetricSource.INTERNAL_SENSOR,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_STEPS,
            "Steps",
            RecordingMetricSource.INTERNAL_SENSOR,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_CADENCE,
            "Cadence",
            RecordingMetricSource.INTERNAL_SENSOR,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_AVERAGE_CADENCE,
            "Cadence (Avg)",
            RecordingMetricSource.INTERNAL_SENSOR,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_MAX_CADENCE,
            "Cadence (Max)",
            RecordingMetricSource.INTERNAL_SENSOR,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_POWER,
            "Power",
            RecordingMetricSource.EXTERNAL,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_AVERAGE_POWER,
            "Power (Avg)",
            RecordingMetricSource.EXTERNAL,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_MAX_POWER,
            "Power (Max)",
            RecordingMetricSource.EXTERNAL,
        ),
        RecordingMetricDefinition(
            SettingsRepository.RECORDING_METRIC_BAROMETRIC_PRESSURE,
            "Pressure (hPa)",
            RecordingMetricSource.INTERNAL_SENSOR,
        ),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_CALORIES, "Cal (Total)"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_ACTIVE_CALORIES, "Cal (Active)"),
        RecordingMetricDefinition(SettingsRepository.RECORDING_METRIC_RESTING_CALORIES, "Cal (Rest)"),
    )

internal fun recordingMetricPickerOptionsForProfile(profile: String): List<Pair<String, String>> =
    recordingMetricDefinitions
        .filter { definition -> isRecordingDashboardMetricAllowedForProfile(profile, definition.id) }
        .sortedBy { it.label.lowercase() }
        .map { it.id to it.label }

internal fun metricDefinitionFor(id: String): RecordingMetricDefinition =
    recordingMetricDefinitions.firstOrNull { it.id == id }
        ?: recordingMetricDefinitions.first()

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun buildRecordingDashboardSnapshot(
    state: TraceRecordingUiState,
    nowMillis: Long,
    userWeightKg: Float = SettingsRepository.DEFAULT_USER_WEIGHT_KG,
    backpackWeightKg: Float = SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG,
    bikeWeightKg: Float = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG,
    activityProfile: String = state.activityProfile,
): RecordingDashboardSnapshot {
    val startedAt = state.startedAtMillis ?: nowMillis
    val currentPausedMillis =
        if (state.paused) {
            state.pausedAtMillis?.let { nowMillis - it }?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
    val activeDurationMillis =
        (nowMillis - startedAt - state.accumulatedPausedMillis - currentPausedMillis).coerceAtLeast(0L)
    val activeDurationSeconds = activeDurationMillis / 1000.0
    val totalDurationSeconds = ((nowMillis - startedAt).coerceAtLeast(0L)) / 1000.0
    val canonicalProfile = buildRecordingCanonicalProfile(state.points)
    val lastRecordedPoint = state.points.lastOrNull()
    val livePoint =
        state.latestLivePoint
            ?.takeIf { livePoint -> livePoint.timeMillis.isFreshLivePointTime(nowMillis) }
    val currentPoint = livePoint ?: lastRecordedPoint
    val displayDistanceMeters =
        when (state.distanceSource) {
            SettingsRepository.RECORDING_SENSOR_SOURCE_POD ->
                state.externalDistanceMeters ?: state.distanceMeters
            else -> state.distanceMeters
        }
    val displayCurrentSpeedMps =
        when (state.speedSource) {
            SettingsRepository.RECORDING_SENSOR_SOURCE_POD -> state.externalSpeedMps
            else -> currentPoint?.speedMps ?: lastRecordedPoint?.speedMps
        }
    val calorieEstimate =
        estimateRecordingCalories(
            points = state.points,
            userWeightKg = userWeightKg,
            backpackWeightKg = backpackWeightKg,
            bikeWeightKg = bikeWeightKg,
            activityProfile = activityProfile,
        )
    val hasElevationData = state.points.any { it.elevationMeters?.isFinite() == true }
    return RecordingDashboardSnapshot(
        activityProfile = activityProfile,
        durationSeconds = activeDurationSeconds,
        totalDurationSeconds = totalDurationSeconds,
        distanceMeters = displayDistanceMeters,
        elevationGainMeters = canonicalProfile?.totalAscent ?: 0.0,
        elevationLossMeters = canonicalProfile?.totalDescent ?: 0.0,
        hasElevationData = hasElevationData,
        currentElevationMeters = currentPoint?.elevationMeters ?: lastRecordedPoint?.elevationMeters,
        currentSpeedMps = displayCurrentSpeedMps,
        externalSpeedMps = state.externalSpeedMps,
        averageSpeedMps =
            if (activeDurationSeconds > 0.0) {
                displayDistanceMeters / activeDurationSeconds
            } else {
                null
            },
        fastestSpeedMps =
            state.points
                .mapNotNull { point -> point.speedMps?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } }
                .maxOrNull()
                ?: displayCurrentSpeedMps?.toDouble()?.takeIf { it.isFinite() && it > 0.0 },
        externalDistanceMeters = state.externalDistanceMeters,
        gpsAccuracyMeters = currentPoint?.accuracyMeters ?: lastRecordedPoint?.accuracyMeters,
        pointCount = state.points.size,
        gpsActiveDurationSeconds = state.gpsActiveDurationMillis / 1000.0,
        recordingGapCount = state.recordingGapCount,
        recordingMaxGapSeconds = state.recordingMaxGapMillis / 1000.0,
        userWeightKg = userWeightKg,
        backpackWeightKg = backpackWeightKg,
        calorieEstimate = calorieEstimate,
        heartRateBpm = state.heartRateBpm,
        heartRateFromBluetooth = state.heartRateFromBluetooth,
        averageHeartRateBpm = state.points.averageHeartRateBpm(),
        maxHeartRateBpm = state.points.maxHeartRateBpm() ?: state.heartRateBpm?.takeIf { it > 0 },
        stepCount = state.stepCount,
        stepCountFromBluetooth = state.stepCountFromBluetooth,
        cadenceSpm = state.cadenceSpm,
        averageCadenceSpm = state.points.averageCadenceSpm(),
        maxCadenceSpm = state.points.maxCadenceSpm() ?: state.cadenceSpm?.takeIf { it > 0 },
        cadenceFromBluetooth = state.cadenceFromBluetooth,
        powerWatts = state.externalPowerWatts,
        averagePowerWatts = state.points.averagePowerWatts(),
        maxPowerWatts = state.points.maxPowerWatts() ?: state.externalPowerWatts?.takeIf { it >= 0 },
        powerFromBluetooth =
            state.externalPowerFromBluetooth ||
                state.points.any { point -> point.powerWatts != null },
        barometricPressureHpa = state.barometricPressureHpa,
        lastLiveFixAgeMillis = state.latestLivePoint?.timeMillis?.ageMillisAt(nowMillis),
        lastRecordedPointAgeMillis = lastRecordedPoint?.timeMillis?.ageMillisAt(nowMillis),
        speedSource = state.speedSource,
        distanceSource = state.distanceSource,
        cadenceSource = state.cadenceSource,
        stepsSource = state.stepsSource,
    )
}

internal fun formattedRecordingMetric(
    metricId: String,
    snapshot: RecordingDashboardSnapshot,
    isMetric: Boolean,
): RecordingMetricValue {
    val definition = metricDefinitionFor(metricId)
    return when (definition.id) {
        SettingsRepository.RECORDING_METRIC_DISTANCE -> {
            if (snapshot.distanceSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                return RecordingMetricValue(definition.label, "--")
            }
            val (value, unit) = formatRecordingDistance(snapshot.distanceMeters, isMetric)
            RecordingMetricValue(
                label = definition.label,
                value = value,
                unit = unit,
                bluetooth = snapshot.distanceFromBluetooth(),
            )
        }
        SettingsRepository.RECORDING_METRIC_DURATION ->
            RecordingMetricValue(definition.label, formatRecordingDurationClock(snapshot.durationSeconds))
        SettingsRepository.RECORDING_METRIC_TOTAL_TIME ->
            RecordingMetricValue(definition.label, formatRecordingDurationClock(snapshot.totalDurationSeconds))
        SettingsRepository.RECORDING_METRIC_ELEVATION_GAIN -> {
            if (!snapshot.hasElevationData) return RecordingMetricValue(definition.label, "--")
            val (value, unit) = UnitFormatter.formatElevation(snapshot.elevationGainMeters, isMetric)
            RecordingMetricValue(definition.label, value, unit)
        }
        SettingsRepository.RECORDING_METRIC_ELEVATION_LOSS -> {
            if (!snapshot.hasElevationData) return RecordingMetricValue(definition.label, "--")
            val (value, unit) = UnitFormatter.formatElevation(snapshot.elevationLossMeters, isMetric)
            RecordingMetricValue(definition.label, value, unit)
        }
        SettingsRepository.RECORDING_METRIC_CURRENT_ELEVATION -> {
            val elevation = snapshot.currentElevationMeters
            if (elevation == null) {
                RecordingMetricValue(definition.label, "--")
            } else {
                val (value, unit) = UnitFormatter.formatElevation(elevation, isMetric)
                RecordingMetricValue(definition.label, value, unit)
            }
        }
        SettingsRepository.RECORDING_METRIC_CURRENT_SPEED ->
            if (snapshot.speedSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                speedMetricValue(
                    definition.label,
                    snapshot.currentSpeedMps?.toDouble(),
                    isMetric,
                    bluetooth = snapshot.speedFromBluetooth(),
                )
            }
        SettingsRepository.RECORDING_METRIC_AVERAGE_SPEED ->
            if (snapshot.distanceSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                speedMetricValue(
                    definition.label,
                    snapshot.averageSpeedMps,
                    isMetric,
                    bluetooth = snapshot.distanceFromBluetooth(),
                )
            }
        SettingsRepository.RECORDING_METRIC_MAX_SPEED ->
            if (snapshot.speedSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                speedMetricValue(
                    definition.label,
                    snapshot.fastestSpeedMps,
                    isMetric,
                    bluetooth = snapshot.speedFromBluetooth(),
                )
            }
        SettingsRepository.RECORDING_METRIC_CURRENT_PACE ->
            if (snapshot.speedSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                paceMetricValue(
                    definition.label,
                    snapshot.currentSpeedMps?.toDouble(),
                    isMetric,
                    bluetooth = snapshot.speedFromBluetooth(),
                )
            }
        SettingsRepository.RECORDING_METRIC_AVERAGE_PACE ->
            if (snapshot.distanceSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                paceMetricValue(
                    definition.label,
                    snapshot.averageSpeedMps,
                    isMetric,
                    bluetooth = snapshot.distanceFromBluetooth(),
                )
            }
        SettingsRepository.RECORDING_METRIC_MAX_PACE ->
            if (snapshot.speedSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                paceMetricValue(
                    definition.label,
                    snapshot.fastestSpeedMps,
                    isMetric,
                    bluetooth = snapshot.speedFromBluetooth(),
                )
            }
        SettingsRepository.RECORDING_METRIC_HEART_RATE ->
            sensorIntegerMetricValue(
                label = definition.label,
                value = snapshot.heartRateBpm,
                unit = "bpm",
                bluetooth = snapshot.heartRateFromBluetooth,
            )
        SettingsRepository.RECORDING_METRIC_MAX_HEART_RATE ->
            sensorIntegerMetricValue(
                label = definition.label,
                value = snapshot.maxHeartRateBpm,
                unit = "bpm",
                bluetooth = snapshot.heartRateFromBluetooth,
            )
        SettingsRepository.RECORDING_METRIC_STEPS ->
            if (snapshot.stepsSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                sensorIntegerMetricValue(
                    definition.label,
                    snapshot.stepCount,
                    null,
                    bluetooth = snapshot.stepCountFromBluetooth,
                )
            }
        SettingsRepository.RECORDING_METRIC_CADENCE ->
            if (snapshot.cadenceSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                sensorIntegerMetricValue(
                    definition.label,
                    snapshot.cadenceSpm,
                    snapshot.cadenceUnitLabel(),
                    bluetooth = snapshot.cadenceFromBluetooth,
                )
            }
        SettingsRepository.RECORDING_METRIC_AVERAGE_CADENCE ->
            if (snapshot.cadenceSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                sensorIntegerMetricValue(
                    definition.label,
                    snapshot.averageCadenceSpm,
                    snapshot.cadenceUnitLabel(),
                    bluetooth = snapshot.cadenceFromBluetooth,
                )
            }
        SettingsRepository.RECORDING_METRIC_MAX_CADENCE ->
            if (snapshot.cadenceSource == SettingsRepository.RECORDING_SOURCE_DISABLED) {
                RecordingMetricValue(definition.label, "--")
            } else {
                sensorIntegerMetricValue(
                    definition.label,
                    snapshot.maxCadenceSpm,
                    snapshot.cadenceUnitLabel(),
                    bluetooth = snapshot.cadenceFromBluetooth,
                )
            }
        SettingsRepository.RECORDING_METRIC_POWER ->
            sensorIntegerMetricValue(
                definition.label,
                snapshot.powerWatts,
                "W",
                bluetooth = snapshot.powerFromBluetooth,
            )
        SettingsRepository.RECORDING_METRIC_AVERAGE_POWER ->
            sensorIntegerMetricValue(
                definition.label,
                snapshot.averagePowerWatts,
                "W",
                bluetooth = snapshot.powerFromBluetooth,
            )
        SettingsRepository.RECORDING_METRIC_MAX_POWER ->
            sensorIntegerMetricValue(
                definition.label,
                snapshot.maxPowerWatts,
                "W",
                bluetooth = snapshot.powerFromBluetooth,
            )
        SettingsRepository.RECORDING_METRIC_BAROMETRIC_PRESSURE ->
            pressureMetricValue(definition.label, snapshot.barometricPressureHpa)
        SettingsRepository.RECORDING_METRIC_CALORIES ->
            caloriesMetricValue(definition.label, snapshot.calorieEstimate.grossKcal)
        SettingsRepository.RECORDING_METRIC_ACTIVE_CALORIES ->
            caloriesMetricValue(definition.label, snapshot.calorieEstimate.activeKcal)
        SettingsRepository.RECORDING_METRIC_RESTING_CALORIES ->
            caloriesMetricValue(definition.label, snapshot.calorieEstimate.restingKcal)
        else -> RecordingMetricValue(definition.label, "--")
    }
}

private fun RecordingDashboardSnapshot.cadenceUnitLabel(): String =
    when (activityProfile) {
        SettingsRepository.ACTIVITY_PROFILE_BIKE -> "rpm"
        else -> "spm"
    }

private fun formatRecordingDistance(
    meters: Double,
    isMetric: Boolean,
): Pair<String, String> =
    if (isMetric) {
        RECORDING_DISTANCE_FORMAT.format(meters / 1000.0) to "km"
    } else {
        RECORDING_DISTANCE_FORMAT.format(meters * METERS_TO_MILES) to "mi"
    }

private fun formatRecordingDurationClock(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0.0) return "00:00:00"
    val totalSeconds = seconds.roundToInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return "${hours.twoDigits()}:${minutes.twoDigits()}:${secs.twoDigits()}"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

internal fun speedMetricValue(
    label: String,
    speedMps: Double?,
    isMetric: Boolean,
    bluetooth: Boolean = false,
): RecordingMetricValue {
    if (speedMps == null || !speedMps.isFinite() || speedMps <= 0.0) {
        return RecordingMetricValue(label, "--")
    }
    val value =
        if (isMetric) {
            speedMps * 3.6
        } else {
            speedMps * 2.2369362920544
        }
    return RecordingMetricValue(
        label = label,
        value = (value * 10.0).roundToInt().let { (it / 10.0).toString() },
        unit = if (isMetric) "km/h" else "mph",
        bluetooth = bluetooth,
    )
}

private fun sensorIntegerMetricValue(
    label: String,
    value: Int?,
    unit: String?,
    bluetooth: Boolean = false,
): RecordingMetricValue =
    if (value == null || value < 0) {
        RecordingMetricValue(label, "--", unit, bluetooth = false)
    } else {
        RecordingMetricValue(label, value.toString(), unit, bluetooth = bluetooth)
    }

private fun pressureMetricValue(
    label: String,
    pressureHpa: Double?,
): RecordingMetricValue =
    if (pressureHpa == null || !pressureHpa.isFinite() || pressureHpa <= 0.0) {
        RecordingMetricValue(label, "--", "hPa")
    } else {
        RecordingMetricValue(
            label = label,
            value = (pressureHpa * 10.0).roundToInt().let { (it / 10.0).toString() },
            unit = "hPa",
        )
    }

private fun caloriesMetricValue(
    label: String,
    calories: Double,
): RecordingMetricValue =
    RecordingMetricValue(
        label = label,
        value =
            if (!calories.isFinite() || calories <= 0.0) {
                "0"
            } else {
                calories.roundToInt().toString()
            },
        unit = "kcal",
    )

internal fun estimateRecordingCalories(
    points: List<RecordedTracePoint>,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    terrainFactor: Double = DEFAULT_TERRAIN_FACTOR,
): RecordingCalorieEstimate {
    if (points.size < 2) return RecordingCalorieEstimate()
    val bodyWeightKg =
        userWeightKg
            .takeIf { it.isFinite() }
            ?.coerceIn(SettingsRepository.MIN_USER_WEIGHT_KG, SettingsRepository.MAX_USER_WEIGHT_KG)
            ?: SettingsRepository.DEFAULT_USER_WEIGHT_KG
    val loadWeightKg =
        backpackWeightKg
            .takeIf { it.isFinite() }
            ?.coerceIn(SettingsRepository.MIN_BACKPACK_WEIGHT_KG, SettingsRepository.MAX_BACKPACK_WEIGHT_KG)
            ?: SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG
    val equipmentWeightKg =
        bikeWeightKg
            .takeIf { it.isFinite() }
            ?.coerceIn(SettingsRepository.MIN_BIKE_WEIGHT_KG, SettingsRepository.MAX_BIKE_WEIGHT_KG)
            ?: SettingsRepository.DEFAULT_BIKE_WEIGHT_KG

    if (activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
        return estimateCyclingCalories(
            points = points,
            bodyWeightKg = bodyWeightKg.toDouble(),
            loadWeightKg = loadWeightKg.toDouble(),
            bikeWeightKg = equipmentWeightKg.toDouble(),
        )
    }

    var grossKcal = 0.0
    var pandolfBaseGrossKcal = 0.0
    var lcdaGrossKcal = 0.0
    var modeledDurationSeconds = 0.0
    val calorieElevations = smoothedCalorieElevations(points)
    points.zipWithNext().forEachIndexed { index, (start, end) ->
        if (end.startsNewSegment) return@forEachIndexed
        val segmentDurationSeconds =
            ((end.timeMillis - start.timeMillis) / 1000.0)
                .takeIf { it.isFinite() && it > 0.0 }
                ?.coerceAtMost(MAX_CALORIE_SEGMENT_DURATION_SECONDS)
                ?: return@forEachIndexed
        val distanceMeters = haversineMeters(start.latLong, end.latLong).coerceAtLeast(0.0)
        val speedMetersPerSecond =
            (distanceMeters / segmentDurationSeconds)
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, MAX_PANDOLF_SPEED_MPS)
                ?: 0.0
        val elevationDeltaMeters =
            if (calorieElevations[index] != null && calorieElevations[index + 1] != null) {
                calorieElevations[index + 1]!! - calorieElevations[index]!!
            } else {
                0.0
            }
        val gradePercent =
            if (distanceMeters >= MIN_DISTANCE_METERS_FOR_GRADE) {
                ((elevationDeltaMeters / distanceMeters) * 100.0)
                    .coerceIn(-MAX_PANDOLF_GRADE_PERCENT, MAX_PANDOLF_GRADE_PERCENT)
            } else {
                0.0
            }
        val watts =
            pandolfSanteeWatts(
                bodyWeightKg = bodyWeightKg.toDouble(),
                loadWeightKg = loadWeightKg.toDouble(),
                speedMetersPerSecond = speedMetersPerSecond,
                gradePercent = gradePercent,
                terrainFactor = terrainFactor,
            )
        grossKcal += watts * segmentDurationSeconds / JOULES_PER_KILOCALORIE
        pandolfBaseGrossKcal +=
            pandolfWatts(
                bodyWeightKg = bodyWeightKg.toDouble(),
                loadWeightKg = loadWeightKg.toDouble(),
                speedMetersPerSecond = speedMetersPerSecond,
                gradePercent = gradePercent,
                terrainFactor = terrainFactor,
            ).coerceAtLeast(0.0) * segmentDurationSeconds / JOULES_PER_KILOCALORIE
        lcdaGrossKcal +=
            lcda2024WeightedLoadWatts(
                bodyWeightKg = bodyWeightKg.toDouble(),
                loadWeightKg = loadWeightKg.toDouble(),
                speedMetersPerSecond = speedMetersPerSecond,
                gradePercent = gradePercent,
                terrainFactor = terrainFactor,
            ) * segmentDurationSeconds / JOULES_PER_KILOCALORIE
        modeledDurationSeconds += segmentDurationSeconds
    }

    val restingKcal = bodyWeightKg * (modeledDurationSeconds / SECONDS_PER_HOUR) * RESTING_MET
    val activeKcal = (grossKcal - restingKcal).coerceAtLeast(0.0)
    val pandolfBaseRestingKcal = restingKcal
    val pandolfBaseActiveKcal = (pandolfBaseGrossKcal - pandolfBaseRestingKcal).coerceAtLeast(0.0)
    val lcdaRestingKcal = restingKcal
    val lcdaActiveKcal = (lcdaGrossKcal - lcdaRestingKcal).coerceAtLeast(0.0)
    return RecordingCalorieEstimate(
        grossKcal = grossKcal,
        activeKcal = activeKcal,
        restingKcal = restingKcal,
        model = CALORIE_MODEL_HIKE_PANDOLF_SANTEE,
        pandolfBaseGrossKcal = pandolfBaseGrossKcal,
        pandolfBaseActiveKcal = pandolfBaseActiveKcal,
        pandolfBaseRestingKcal = pandolfBaseRestingKcal,
        lcdaGrossKcal = lcdaGrossKcal,
        lcdaActiveKcal = lcdaActiveKcal,
        lcdaRestingKcal = lcdaRestingKcal,
    )
}

private fun estimateCyclingCalories(
    points: List<RecordedTracePoint>,
    bodyWeightKg: Double,
    loadWeightKg: Double,
    bikeWeightKg: Double,
): RecordingCalorieEstimate {
    var mechanicalJoules = 0.0
    var modeledDurationSeconds = 0.0
    var powerSampleSegments = 0
    var physicsSegments = 0
    val calorieElevations = smoothedCalorieElevations(points)

    points.zipWithNext().forEachIndexed { index, (start, end) ->
        if (end.startsNewSegment) {
            return@forEachIndexed
        }
        val segmentDurationSeconds =
            ((end.timeMillis - start.timeMillis) / 1000.0)
                .takeIf { it.isFinite() && it > 0.0 }
                ?.coerceAtMost(MAX_CALORIE_SEGMENT_DURATION_SECONDS)
                ?: return@forEachIndexed
        val distanceMeters = haversineMeters(start.latLong, end.latLong).coerceAtLeast(0.0)
        val speedMetersPerSecond =
            (distanceMeters / segmentDurationSeconds)
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, MAX_CYCLING_SPEED_MPS)
                ?: 0.0
        val powerWatts = end.powerWatts?.takeIf { it in CYCLING_POWER_WATTS_RANGE }
        val estimatedPowerWatts =
            if (powerWatts != null) {
                powerSampleSegments += 1
                powerWatts.toDouble()
            } else {
                physicsSegments += 1
                val elevationDeltaMeters =
                    if (calorieElevations[index] != null && calorieElevations[index + 1] != null) {
                        calorieElevations[index + 1]!! - calorieElevations[index]!!
                    } else {
                        0.0
                    }
                val gradeFraction =
                    if (distanceMeters >= MIN_DISTANCE_METERS_FOR_GRADE) {
                        (elevationDeltaMeters / distanceMeters)
                            .coerceIn(-MAX_CYCLING_GRADE_FRACTION, MAX_CYCLING_GRADE_FRACTION)
                    } else {
                        0.0
                    }
                cyclingPhysicsPowerWatts(
                    bodyWeightKg = bodyWeightKg,
                    loadWeightKg = loadWeightKg,
                    bikeWeightKg = bikeWeightKg,
                    speedMetersPerSecond = speedMetersPerSecond,
                    gradeFraction = gradeFraction,
                )
            }

        mechanicalJoules += estimatedPowerWatts * segmentDurationSeconds
        modeledDurationSeconds += segmentDurationSeconds
    }

    val activeKcal =
        (mechanicalJoules / JOULES_PER_KILOCALORIE / CYCLING_DEFAULT_GROSS_EFFICIENCY)
            .takeIf { it.isFinite() }
            ?.coerceAtLeast(0.0)
            ?: 0.0
    val restingKcal = bodyWeightKg * (modeledDurationSeconds / SECONDS_PER_HOUR) * RESTING_MET
    val model =
        if (powerSampleSegments > 0) {
            CALORIE_MODEL_BIKE_POWER
        } else {
            CALORIE_MODEL_BIKE_PHYSICS
        }
    return RecordingCalorieEstimate(
        grossKcal = activeKcal + restingKcal,
        activeKcal = activeKcal,
        restingKcal = restingKcal,
        model = model,
        cyclingMechanicalKj = mechanicalJoules / 1_000.0,
        cyclingPowerSampleSegments = powerSampleSegments,
        cyclingPhysicsSegments = physicsSegments,
    )
}

private fun cyclingPhysicsPowerWatts(
    bodyWeightKg: Double,
    loadWeightKg: Double,
    bikeWeightKg: Double,
    speedMetersPerSecond: Double,
    gradeFraction: Double,
): Double {
    val totalMassKg = bodyWeightKg + loadWeightKg + bikeWeightKg
    val rollingPower =
        totalMassKg *
            GRAVITY_METERS_PER_SECOND_SQUARED *
            CYCLING_DEFAULT_ROLLING_RESISTANCE *
            speedMetersPerSecond
    val gravityPower =
        totalMassKg *
            GRAVITY_METERS_PER_SECOND_SQUARED *
            gradeFraction *
            speedMetersPerSecond
    val aeroPower =
        0.5 *
            AIR_DENSITY_KG_PER_CUBIC_METER *
            CYCLING_DEFAULT_CDA *
            speedMetersPerSecond.pow(3.0)

    return (rollingPower + gravityPower + aeroPower)
        .takeIf { it.isFinite() }
        ?.coerceAtLeast(0.0)
        ?: 0.0
}

private fun smoothedCalorieElevations(points: List<RecordedTracePoint>): List<Double?> {
    if (points.size < 3) return points.map { it.elevationMeters?.takeIf { elevation -> elevation.isFinite() } }
    val rawElevations = points.map { it.elevationMeters?.takeIf { elevation -> elevation.isFinite() } }
    return rawElevations.mapIndexed { index, elevation ->
        if (elevation == null) {
            null
        } else {
            val window =
                listOfNotNull(
                    rawElevations.getOrNull(index - 1),
                    elevation,
                    rawElevations.getOrNull(index + 1),
                ).sorted()
            if (window.size >= MIN_ELEVATION_SMOOTHING_WINDOW_SIZE) {
                window[window.size / 2]
            } else {
                elevation
            }
        }
    }
}

private fun pandolfSanteeWatts(
    bodyWeightKg: Double,
    loadWeightKg: Double,
    speedMetersPerSecond: Double,
    gradePercent: Double,
    terrainFactor: Double,
): Double {
    val rawWatts =
        pandolfWatts(
            bodyWeightKg = bodyWeightKg,
            loadWeightKg = loadWeightKg,
            speedMetersPerSecond = speedMetersPerSecond,
            gradePercent = gradePercent,
            terrainFactor = terrainFactor,
        )
    if (gradePercent >= 0.0) return rawWatts

    val correctionWatts =
        santeeDownhillCorrectionWatts(
            bodyWeightKg = bodyWeightKg,
            loadWeightKg = loadWeightKg,
            speedMetersPerSecond = speedMetersPerSecond,
            gradePercent = gradePercent,
            terrainFactor = terrainFactor,
        )
    return max(rawWatts + correctionWatts, 0.0)
}

private fun pandolfWatts(
    bodyWeightKg: Double,
    loadWeightKg: Double,
    speedMetersPerSecond: Double,
    gradePercent: Double,
    terrainFactor: Double,
): Double =
    1.5 * bodyWeightKg +
        2.0 * (bodyWeightKg + loadWeightKg) * (loadWeightKg / bodyWeightKg) * (loadWeightKg / bodyWeightKg) +
        terrainFactor * (bodyWeightKg + loadWeightKg) *
        (
            1.5 * speedMetersPerSecond * speedMetersPerSecond +
                0.35 * speedMetersPerSecond * gradePercent
        )

private fun santeeDownhillCorrectionWatts(
    bodyWeightKg: Double,
    loadWeightKg: Double,
    speedMetersPerSecond: Double,
    gradePercent: Double,
    terrainFactor: Double,
): Double {
    val carriedWeightKg = bodyWeightKg + loadWeightKg
    val bracket =
        (gradePercent * carriedWeightKg * speedMetersPerSecond / SANTEE_SPEED_NORMALIZER) -
            (carriedWeightKg * (gradePercent + SANTEE_GRADE_OFFSET).pow(2.0) / bodyWeightKg) +
            (SANTEE_SPEED_SQUARED_COEFFICIENT * speedMetersPerSecond.pow(2.0))
    return -terrainFactor * bracket
}

private fun lcda2024WeightedLoadWatts(
    bodyWeightKg: Double,
    loadWeightKg: Double,
    speedMetersPerSecond: Double,
    gradePercent: Double,
    terrainFactor: Double,
): Double {
    val gradeFraction =
        (gradePercent / 100.0)
            .coerceIn(-MAX_PANDOLF_GRADE_PERCENT / 100.0, MAX_PANDOLF_GRADE_PERCENT / 100.0)
    val gradeShape =
        1.0 -
            LCDA_GRADE_OUTER_BASE.pow(
                1.0 -
                    LCDA_GRADE_INNER_BASE.pow(
                        LCDA_GRADE_SCALE * gradeFraction + LCDA_GRADE_OFFSET,
                    ),
            )
    val walkWattsPerKg =
        terrainFactor *
            (
                LCDA_SPEED_LINEAR_COEFFICIENT * speedMetersPerSecond.pow(LCDA_SPEED_LINEAR_EXPONENT) +
                    LCDA_SPEED_FAST_COEFFICIENT * speedMetersPerSecond.pow(LCDA_SPEED_FAST_EXPONENT) +
                    LCDA_GRADE_COEFFICIENT * speedMetersPerSecond * gradeFraction * gradeShape
            )
    val backpackLoadRatio =
        (loadWeightKg / bodyWeightKg)
            .takeIf { it.isFinite() }
            ?.coerceAtLeast(0.0)
            ?: 0.0
    val vestLoadRatio = 0.0
    val loadMultiplier =
        1.0 +
            LCDA_BACKPACK_LOAD_COEFFICIENT * backpackLoadRatio.pow(LCDA_BACKPACK_LOAD_EXPONENT) +
            LCDA_VEST_LOAD_COEFFICIENT * vestLoadRatio.pow(LCDA_VEST_LOAD_EXPONENT)
    val wattsPerKg =
        LCDA_RESTING_WATTS_PER_KG +
            (LCDA_STANDING_WATTS_PER_KG + walkWattsPerKg.coerceAtLeast(0.0)) *
            loadMultiplier
    return (wattsPerKg * bodyWeightKg).coerceAtLeast(0.0)
}

internal fun paceMetricValue(
    label: String,
    speedMps: Double?,
    isMetric: Boolean,
    bluetooth: Boolean = false,
): RecordingMetricValue {
    if (speedMps == null || !speedMps.isFinite() || speedMps <= 0.0) {
        return RecordingMetricValue(label, "--", if (isMetric) "min/km" else "min/mi")
    }
    val secondsPerUnit =
        if (isMetric) {
            1_000.0 / speedMps
        } else {
            METERS_PER_MILE / speedMps
        }
    val totalSeconds = secondsPerUnit.roundToInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return RecordingMetricValue(
        label = label,
        value = "$minutes:${seconds.twoDigits()}",
        unit = if (isMetric) "min/km" else "min/mi",
        bluetooth = bluetooth,
    )
}

private fun RecordingDashboardSnapshot.speedFromBluetooth(): Boolean =
    speedSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD &&
        externalSpeedMps != null

private fun RecordingDashboardSnapshot.distanceFromBluetooth(): Boolean =
    distanceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD &&
        externalDistanceMeters != null

private fun buildRecordingCanonicalProfile(points: List<RecordedTracePoint>) =
    points
        .map {
            TrackPoint(
                latLong = it.latLong,
                elevation = it.elevationMeters,
                startsNewSegment = it.startsNewSegment,
                hasTimestamp = true,
                timeMillis = it.timeMillis,
                accuracyMeters = it.accuracyMeters,
                speedMps = it.speedMps,
                heartRateBpm = it.heartRateBpm,
                stepCount = it.stepCount,
                cadenceSpm = it.cadenceSpm,
                powerWatts = it.powerWatts,
                barometricPressureHpa = it.barometricPressureHpa,
            )
        }.takeIf { it.isNotEmpty() }
        ?.let { trackPoints ->
            buildProfile(
                sig = FileSig(lastModified = 0L, length = trackPoints.size.toLong()),
                pts = trackPoints,
            )
        }

private fun List<RecordedTracePoint>.averageHeartRateBpm(): Int? {
    val values = mapNotNull { point -> point.heartRateBpm?.takeIf { it > 0 } }
    if (values.isEmpty()) return null
    return values.average().roundToInt()
}

private fun List<RecordedTracePoint>.maxHeartRateBpm(): Int? =
    mapNotNull { point -> point.heartRateBpm?.takeIf { it > 0 } }
        .maxOrNull()

private fun List<RecordedTracePoint>.averageCadenceSpm(): Int? {
    val values = mapNotNull { point -> point.cadenceSpm?.takeIf { it > 0 } }
    if (values.isEmpty()) return null
    return values.average().roundToInt()
}

private fun List<RecordedTracePoint>.maxCadenceSpm(): Int? =
    mapNotNull { point -> point.cadenceSpm?.takeIf { it > 0 } }
        .maxOrNull()

private fun List<RecordedTracePoint>.averagePowerWatts(): Int? {
    val values = mapNotNull { point -> point.powerWatts?.takeIf { it >= 0 } }
    if (values.isEmpty()) return null
    return values.average().roundToInt()
}

private fun List<RecordedTracePoint>.maxPowerWatts(): Int? =
    mapNotNull { point -> point.powerWatts?.takeIf { it >= 0 } }
        .maxOrNull()

private fun Long.ageMillisAt(nowMillis: Long): Long = (nowMillis - this).coerceAtLeast(0L)

private fun Long.isFreshLivePointTime(nowMillis: Long): Boolean = this > 0L && ageMillisAt(nowMillis) <= LIVE_POINT_MAX_AGE_MS

private const val METERS_TO_MILES = 0.000621371
private const val METERS_PER_MILE = 1_609.344
private const val JOULES_PER_KILOCALORIE = 4_184.0
private const val SECONDS_PER_HOUR = 3_600.0
private const val RESTING_MET = 1.0
private const val DEFAULT_TERRAIN_FACTOR = 1.0
private const val CALORIE_MODEL_HIKE_PANDOLF_SANTEE = "pandolf_santee_segment_v2"
private const val CALORIE_MODEL_BIKE_POWER = "cycling_power_meter_efficiency_v1"
private const val CALORIE_MODEL_BIKE_PHYSICS = "cycling_physics_fallback_v1"
private const val MAX_PANDOLF_SPEED_MPS = 3.0
private const val MAX_PANDOLF_GRADE_PERCENT = 35.0
private const val SANTEE_SPEED_NORMALIZER = 3.5
private const val SANTEE_GRADE_OFFSET = 6.0
private const val SANTEE_SPEED_SQUARED_COEFFICIENT = 25.0
private const val LCDA_RESTING_WATTS_PER_KG = RESTING_MET * JOULES_PER_KILOCALORIE / SECONDS_PER_HOUR
private const val LCDA_STANDING_WATTS_PER_KG = 0.21
private const val LCDA_SPEED_LINEAR_COEFFICIENT = 1.78
private const val LCDA_SPEED_LINEAR_EXPONENT = 0.58
private const val LCDA_SPEED_FAST_COEFFICIENT = 0.27
private const val LCDA_SPEED_FAST_EXPONENT = 4.0
private const val LCDA_GRADE_COEFFICIENT = 34.0
private const val LCDA_GRADE_OUTER_BASE = 1.05
private const val LCDA_GRADE_INNER_BASE = 1.1
private const val LCDA_GRADE_SCALE = 100.0
private const val LCDA_GRADE_OFFSET = 32.0
private const val LCDA_BACKPACK_LOAD_COEFFICIENT = 1.96
private const val LCDA_BACKPACK_LOAD_EXPONENT = 1.36
private const val LCDA_VEST_LOAD_COEFFICIENT = 1.38
private const val LCDA_VEST_LOAD_EXPONENT = 1.21
private const val MIN_DISTANCE_METERS_FOR_GRADE = 1.0
private const val MIN_ELEVATION_SMOOTHING_WINDOW_SIZE = 3
private const val MAX_CALORIE_SEGMENT_DURATION_SECONDS = 600.0
private const val CYCLING_DEFAULT_GROSS_EFFICIENCY = 0.23
private const val CYCLING_DEFAULT_CDA = 0.50
private const val CYCLING_DEFAULT_ROLLING_RESISTANCE = 0.006
private const val MAX_CYCLING_SPEED_MPS = 25.0
private const val MAX_CYCLING_GRADE_FRACTION = 0.35
private const val GRAVITY_METERS_PER_SECOND_SQUARED = 9.80665
private const val AIR_DENSITY_KG_PER_CUBIC_METER = 1.225
private val CYCLING_POWER_WATTS_RANGE = 0..2_500
private const val LIVE_POINT_MAX_AGE_MS = 15_000L
private val RECORDING_DISTANCE_FORMAT = DecimalFormat("0.00")
