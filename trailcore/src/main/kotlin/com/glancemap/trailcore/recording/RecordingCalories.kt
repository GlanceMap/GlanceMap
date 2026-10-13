package com.glancemap.trailcore.recording

import kotlin.math.max
import kotlin.math.pow

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

fun estimateRecordingCalories(
    points: List<RecordingPoint>,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float = RecordingSettings.DEFAULT_BIKE_WEIGHT_KG,
    activityProfile: String = RecordingSettings.DEFAULT_ACTIVITY_PROFILE,
    terrainFactor: Double = DEFAULT_TERRAIN_FACTOR,
): RecordingCalorieEstimate {
    if (points.size < 2) return RecordingCalorieEstimate()
    val bodyWeightKg = RecordingSettings.normalizeUserWeightKg(userWeightKg)
    val loadWeightKg = RecordingSettings.normalizeBackpackWeightKg(backpackWeightKg)
    val equipmentWeightKg = RecordingSettings.normalizeBikeWeightKg(bikeWeightKg)

    if (activityProfile == RecordingSettings.ACTIVITY_PROFILE_BIKE) {
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
        val distanceMeters = recordingHaversineMeters(start.location, end.location).coerceAtLeast(0.0)
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
    points: List<RecordingPoint>,
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
        val distanceMeters = recordingHaversineMeters(start.location, end.location).coerceAtLeast(0.0)
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

private fun smoothedCalorieElevations(points: List<RecordingPoint>): List<Double?> {
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
