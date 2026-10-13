package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import com.glancemap.glancemapwearos.core.service.diagnostics.RecordingScreenOffDiagnostics
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.data.repository.isRecordingDashboardMetricAllowedForProfile
import com.glancemap.glancemapwearos.presentation.features.gpx.FileSig
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.buildProfile
import com.glancemap.glancemapwearos.presentation.features.gpx.totalAscent
import com.glancemap.glancemapwearos.presentation.features.gpx.totalDescent
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.fastestContinuousSegmentSpeedMps
import com.glancemap.glancemapwearos.presentation.features.recording.latLong
import com.glancemap.glancemapwearos.presentation.features.recording.recordingDisplayDistanceMeters
import com.glancemap.glancemapwearos.presentation.formatting.UnitFormatter
import java.text.DecimalFormat
import kotlin.math.roundToInt
import com.glancemap.trailcore.recording.RecordingCalorieEstimate as SharedRecordingCalorieEstimate
import com.glancemap.trailcore.recording.estimateRecordingCalories as sharedEstimateRecordingCalories

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

typealias RecordingCalorieEstimate = SharedRecordingCalorieEstimate

internal data class RecordingDashboardStatistics(
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val hasElevationData: Boolean,
    val fastestRecordedSpeedMps: Double?,
    val calorieEstimate: RecordingCalorieEstimate,
    val averageHeartRateBpm: Int?,
    val maxRecordedHeartRateBpm: Int?,
    val averageCadenceSpm: Int?,
    val maxRecordedCadenceSpm: Int?,
    val averagePowerWatts: Int?,
    val maxRecordedPowerWatts: Int?,
    val hasRecordedPowerData: Boolean,
)

private data class RecordingDashboardStatisticsCacheKey(
    val points: List<RecordedTracePoint>,
    val userWeightKg: Float,
    val backpackWeightKg: Float,
    val bikeWeightKg: Float,
    val activityProfile: String,
)

internal class RecordingDashboardStatisticsCache {
    private var cachedKey: RecordingDashboardStatisticsCacheKey? = null
    private var cachedStatistics: RecordingDashboardStatistics? = null

    fun getOrBuild(
        points: List<RecordedTracePoint>,
        userWeightKg: Float,
        backpackWeightKg: Float,
        bikeWeightKg: Float,
        activityProfile: String,
    ): RecordingDashboardStatistics {
        val key =
            RecordingDashboardStatisticsCacheKey(
                points = points,
                userWeightKg = userWeightKg,
                backpackWeightKg = backpackWeightKg,
                bikeWeightKg = bikeWeightKg,
                activityProfile = activityProfile,
            )
        if (key == cachedKey) return checkNotNull(cachedStatistics)
        return buildRecordingDashboardStatistics(
            points = points,
            userWeightKg = userWeightKg,
            backpackWeightKg = backpackWeightKg,
            bikeWeightKg = bikeWeightKg,
            activityProfile = activityProfile,
        ).also {
            cachedKey = key
            cachedStatistics = it
        }
    }
}

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
    recordedStatistics: RecordingDashboardStatistics =
        buildRecordingDashboardStatistics(
            points = state.points,
            userWeightKg = userWeightKg,
            backpackWeightKg = backpackWeightKg,
            bikeWeightKg = bikeWeightKg,
            activityProfile = activityProfile,
        ),
): RecordingDashboardSnapshot {
    RecordingScreenOffDiagnostics.recordDashboardSnapshotBuild()
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
    val lastRecordedPoint = state.points.lastOrNull()
    val livePoint =
        state.latestLivePoint
            ?.takeIf { livePoint -> livePoint.timeMillis.isFreshLivePointTime(nowMillis) }
    val currentPoint = livePoint ?: lastRecordedPoint
    val displayDistanceMeters = recordingDisplayDistanceMeters(state, nowMillis)
    val displayCurrentSpeedMps =
        when (state.speedSource) {
            SettingsRepository.RECORDING_SENSOR_SOURCE_POD -> state.externalSpeedMps
            else -> currentPoint?.speedMps ?: lastRecordedPoint?.speedMps
        }
    return RecordingDashboardSnapshot(
        activityProfile = activityProfile,
        durationSeconds = activeDurationSeconds,
        totalDurationSeconds = totalDurationSeconds,
        distanceMeters = displayDistanceMeters,
        elevationGainMeters = recordedStatistics.elevationGainMeters,
        elevationLossMeters = recordedStatistics.elevationLossMeters,
        hasElevationData = recordedStatistics.hasElevationData,
        currentElevationMeters = (lastRecordedPoint ?: livePoint)?.elevationMeters,
        currentSpeedMps = displayCurrentSpeedMps,
        externalSpeedMps = state.externalSpeedMps,
        averageSpeedMps =
            if (activeDurationSeconds > 0.0) {
                displayDistanceMeters / activeDurationSeconds
            } else {
                null
            },
        fastestSpeedMps = recordedStatistics.fastestRecordedSpeedMps,
        externalDistanceMeters = state.externalDistanceMeters,
        gpsAccuracyMeters = currentPoint?.accuracyMeters ?: lastRecordedPoint?.accuracyMeters,
        pointCount = state.points.size,
        gpsActiveDurationSeconds = state.gpsActiveDurationMillis / 1000.0,
        recordingGapCount = state.recordingGapCount,
        recordingMaxGapSeconds = state.recordingMaxGapMillis / 1000.0,
        userWeightKg = userWeightKg,
        backpackWeightKg = backpackWeightKg,
        calorieEstimate = recordedStatistics.calorieEstimate,
        heartRateBpm = state.heartRateBpm,
        heartRateFromBluetooth = state.heartRateFromBluetooth,
        averageHeartRateBpm = recordedStatistics.averageHeartRateBpm,
        maxHeartRateBpm = recordedStatistics.maxRecordedHeartRateBpm ?: state.heartRateBpm?.takeIf { it > 0 },
        stepCount = state.stepCount,
        stepCountFromBluetooth = state.stepCountFromBluetooth,
        cadenceSpm = state.cadenceSpm,
        averageCadenceSpm = recordedStatistics.averageCadenceSpm,
        maxCadenceSpm = recordedStatistics.maxRecordedCadenceSpm ?: state.cadenceSpm?.takeIf { it > 0 },
        cadenceFromBluetooth = state.cadenceFromBluetooth,
        powerWatts = state.externalPowerWatts,
        averagePowerWatts = recordedStatistics.averagePowerWatts,
        maxPowerWatts = recordedStatistics.maxRecordedPowerWatts ?: state.externalPowerWatts?.takeIf { it >= 0 },
        powerFromBluetooth =
            state.externalPowerFromBluetooth ||
                recordedStatistics.hasRecordedPowerData,
        barometricPressureHpa = state.barometricPressureHpa,
        lastLiveFixAgeMillis = state.latestLivePoint?.timeMillis?.ageMillisAt(nowMillis),
        lastRecordedPointAgeMillis = lastRecordedPoint?.timeMillis?.ageMillisAt(nowMillis),
        speedSource = state.speedSource,
        distanceSource = state.distanceSource,
        cadenceSource = state.cadenceSource,
        stepsSource = state.stepsSource,
    )
}

internal fun buildRecordingDashboardStatistics(
    points: List<RecordedTracePoint>,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
): RecordingDashboardStatistics {
    RecordingScreenOffDiagnostics.recordDashboardAggregateBuild(points.size)
    val canonicalProfile = buildRecordingCanonicalProfile(points)
    val fastestRecordedSpeedMps = points.fastestContinuousSegmentSpeedMps()
    return RecordingDashboardStatistics(
        elevationGainMeters = canonicalProfile?.totalAscent ?: 0.0,
        elevationLossMeters = canonicalProfile?.totalDescent ?: 0.0,
        hasElevationData = points.any { it.elevationMeters?.isFinite() == true },
        fastestRecordedSpeedMps = fastestRecordedSpeedMps,
        calorieEstimate =
            estimateRecordingCalories(
                points = points,
                userWeightKg = userWeightKg,
                backpackWeightKg = backpackWeightKg,
                bikeWeightKg = bikeWeightKg,
                activityProfile = activityProfile,
            ),
        averageHeartRateBpm = points.averageHeartRateBpm(),
        maxRecordedHeartRateBpm = points.maxHeartRateBpm(),
        averageCadenceSpm = points.averageCadenceSpm(),
        maxRecordedCadenceSpm = points.maxCadenceSpm(),
        averagePowerWatts = points.averagePowerWatts(),
        maxRecordedPowerWatts = points.maxPowerWatts(),
        hasRecordedPowerData = points.any { point -> point.powerWatts != null },
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

@Suppress("LongParameterList")
internal fun estimateRecordingCalories(
    points: List<RecordedTracePoint>,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float = SettingsRepository.DEFAULT_BIKE_WEIGHT_KG,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    terrainFactor: Double = 1.0,
): RecordingCalorieEstimate =
    sharedEstimateRecordingCalories(
        points = points,
        userWeightKg = userWeightKg,
        backpackWeightKg = backpackWeightKg,
        bikeWeightKg = bikeWeightKg,
        activityProfile = activityProfile,
        terrainFactor = terrainFactor,
    )

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
                effectiveAccuracyMeters = it.effectiveAccuracyMeters,
                accuracyInterpretation = it.accuracyInterpretation,
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
private const val LIVE_POINT_MAX_AGE_MS = 15_000L
private val RECORDING_DISTANCE_FORMAT = DecimalFormat("0.00")
