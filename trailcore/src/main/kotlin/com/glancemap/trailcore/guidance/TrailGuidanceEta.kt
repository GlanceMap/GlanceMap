package com.glancemap.trailcore.guidance

import com.glancemap.trailcore.profile.TrailPoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

private const val ACTIVITY_PROFILE_BIKE = "BIKE"
private const val MIN_SEGMENT_METERS_FOR_GRADE = 1.0
private const val MAX_ABS_GRADE = 0.60
private const val ADVANCED_RATE_EFFECT_START_GRADE = 0.01
private const val ADVANCED_RATE_EFFECT_FULL_GRADE = 0.10
private const val ADVANCED_RATE_MIN_MULTIPLIER = 0.1
private const val ADVANCED_RATE_MAX_MULTIPLIER = 1.5
private const val STAMINA_ONE_HOUR_MULTIPLIER = 1.0
private const val STAMINA_THREE_HOUR_MULTIPLIER = 0.97
private const val STAMINA_SIX_HOUR_MULTIPLIER = 0.93
private const val STAMINA_TEN_HOUR_MULTIPLIER = 0.88
private const val STAMINA_LONG_ROUTE_MULTIPLIER = 0.85
private const val BIKE_GRADE_EFFECT_THRESHOLD = 0.01
private const val BIKE_MIN_RIDER_POWER_W = 90.0
private const val BIKE_DOWNHILL_RIDER_POWER_W = 40.0
private const val BIKE_DRIVETRAIN_EFFICIENCY = 0.96
private const val BIKE_AIR_DENSITY_KG_PER_M3 = 1.225
private const val BIKE_UPRIGHT_CDA_M2 = 0.55
private const val BIKE_UNKNOWN_SURFACE_CRR = 0.008
private const val BIKE_GRAVITY_MPS2 = 9.80665
private const val BIKE_MIN_SPEED_MPS = 3.0 / 3.6
private const val BIKE_DOWNHILL_SPEED_CAP_MPS = 30.0 / 3.6

data class TrailGuidanceEtaConfig(
    val flatSpeedMps: Double,
    val advancedVerticalRateEnabled: Boolean = false,
    val staminaAdjustmentEnabled: Boolean = false,
    val activityProfile: String = "HIKE",
    val userWeightKg: Double = 75.0,
    val backpackWeightKg: Double = 0.0,
    val bikeWeightKg: Double = 12.0,
    val uphillVerticalMetersPerHour: Double = 0.0,
    val downhillVerticalMetersPerHour: Double = 0.0,
)

data class TrailGuidanceEtaProjection(
    val cumulativeSeconds: DoubleArray,
) {
    val totalSeconds: Double? get() = cumulativeSeconds.lastOrNull()

    fun secondsAtPointIndex(index: Int): Double? = if (cumulativeSeconds.isEmpty()) null else cumulativeSeconds[index.coerceIn(0, cumulativeSeconds.lastIndex)]

    fun secondsAtRoutePosition(
        segmentIndex: Int,
        fraction: Double,
    ): Double? {
        if (cumulativeSeconds.size < 2) return cumulativeSeconds.singleOrNull()
        val index = segmentIndex.coerceIn(0, cumulativeSeconds.lastIndex - 1)
        val t = fraction.coerceIn(0.0, 1.0)
        return cumulativeSeconds[index] + t * (cumulativeSeconds[index + 1] - cumulativeSeconds[index])
    }

    fun remainingSecondsAtDistance(
        cumulativeDistancesMeters: List<Double>,
        distanceFromStartMeters: Double?,
    ): Long? {
        if (distanceFromStartMeters == null || cumulativeDistancesMeters.size < 2) return null
        val total = totalSeconds ?: return null
        val distance = distanceFromStartMeters.coerceIn(0.0, cumulativeDistancesMeters.last())
        val segmentIndex =
            cumulativeDistancesMeters.binarySearch(distance).let { index ->
                if (index >= 0) {
                    index.coerceAtMost(cumulativeDistancesMeters.lastIndex - 1)
                } else {
                    (-index - 2).coerceIn(0, cumulativeDistancesMeters.lastIndex - 1)
                }
            }
        val start = cumulativeDistancesMeters[segmentIndex]
        val end = cumulativeDistancesMeters[segmentIndex + 1]
        val fraction = if (end > start) ((distance - start) / (end - start)).coerceIn(0.0, 1.0) else 0.0
        val elapsed = secondsAtRoutePosition(segmentIndex, fraction) ?: return null
        return (total - elapsed).toLong().coerceAtLeast(0L)
    }
}

fun buildTrailGuidanceEtaProjection(
    points: List<TrailPoint>,
    cumulativeDistancesMeters: List<Double>,
    config: TrailGuidanceEtaConfig,
): TrailGuidanceEtaProjection? {
    val configuredFlatSpeedMps = config.flatSpeedMps
    if (!configuredFlatSpeedMps.isFinite() || configuredFlatSpeedMps <= 0.0) return null
    if (points.size != cumulativeDistancesMeters.size) return null
    val n = points.size
    if (n == 0) return TrailGuidanceEtaProjection(DoubleArray(0))
    if (n == 1) return TrailGuidanceEtaProjection(DoubleArray(1))

    val cumulative = DoubleArray(n)
    val totalDistance = cumulativeDistancesMeters.lastOrNull() ?: 0.0
    val flatSpeedMps =
        applyStaminaFlatSpeedAdjustment(
            flatSpeedMps = configuredFlatSpeedMps,
            totalDistanceMeters = totalDistance,
            enabled = config.staminaAdjustmentEnabled,
        )
    val minSpeed = (flatSpeedMps * 0.08).coerceAtLeast(0.05)
    for (i in 0 until n - 1) {
        val distanceMeters =
            (cumulativeDistancesMeters[i + 1] - cumulativeDistancesMeters[i]).coerceAtLeast(0.0)
        if (distanceMeters <= 0.0) {
            cumulative[i + 1] = cumulative[i]
            continue
        }
        val e0 = points[i].elevationMeters
        val e1 = points[i + 1].elevationMeters
        val elevationDeltaMeters = if (e0 != null && e1 != null) e1 - e0 else null
        val segmentSpeed =
            if (config.activityProfile == ACTIVITY_PROFILE_BIKE &&
                elevationDeltaMeters != null &&
                distanceMeters >= MIN_SEGMENT_METERS_FOR_GRADE
            ) {
                applyBikePhysicsSpeedAdjustment(
                    flatSpeedMps = flatSpeedMps,
                    grade = (elevationDeltaMeters / distanceMeters).coerceIn(-MAX_ABS_GRADE, MAX_ABS_GRADE),
                    config = config,
                )
            } else {
                val factor =
                    if (elevationDeltaMeters == null || distanceMeters < MIN_SEGMENT_METERS_FOR_GRADE) {
                        1.0
                    } else {
                        val grade = (elevationDeltaMeters / distanceMeters).coerceIn(-MAX_ABS_GRADE, MAX_ABS_GRADE)
                        exp(-3.5 * (abs(grade + 0.05) - 0.05))
                    }
                applyAdvancedVerticalRateAdjustment(
                    candidateSpeedMps = (flatSpeedMps * factor).coerceAtLeast(minSpeed),
                    elevationDeltaMeters = elevationDeltaMeters,
                    distanceMeters = distanceMeters,
                    minSpeedMps = minSpeed,
                    config = config,
                )
            }
        cumulative[i + 1] = cumulative[i] + distanceMeters / segmentSpeed
    }
    return TrailGuidanceEtaProjection(cumulative)
}

fun applyStaminaFlatSpeedAdjustment(
    flatSpeedMps: Double,
    totalDistanceMeters: Double,
    enabled: Boolean,
): Double {
    if (!enabled) return flatSpeedMps
    if (!flatSpeedMps.isFinite() || flatSpeedMps <= 0.0) return flatSpeedMps
    if (!totalDistanceMeters.isFinite() || totalDistanceMeters <= 0.0) return flatSpeedMps

    val estimatedFlatHours = totalDistanceMeters / flatSpeedMps / 3600.0
    val multiplier =
        when {
            estimatedFlatHours <= 1.0 -> STAMINA_ONE_HOUR_MULTIPLIER
            estimatedFlatHours <= 3.0 ->
                interpolateStaminaMultiplier(
                    hours = estimatedFlatHours,
                    startHours = 1.0,
                    endHours = 3.0,
                    startMultiplier = STAMINA_ONE_HOUR_MULTIPLIER,
                    endMultiplier = STAMINA_THREE_HOUR_MULTIPLIER,
                )
            estimatedFlatHours <= 6.0 ->
                interpolateStaminaMultiplier(
                    hours = estimatedFlatHours,
                    startHours = 3.0,
                    endHours = 6.0,
                    startMultiplier = STAMINA_THREE_HOUR_MULTIPLIER,
                    endMultiplier = STAMINA_SIX_HOUR_MULTIPLIER,
                )
            estimatedFlatHours <= 10.0 ->
                interpolateStaminaMultiplier(
                    hours = estimatedFlatHours,
                    startHours = 6.0,
                    endHours = 10.0,
                    startMultiplier = STAMINA_SIX_HOUR_MULTIPLIER,
                    endMultiplier = STAMINA_TEN_HOUR_MULTIPLIER,
                )
            else -> STAMINA_LONG_ROUTE_MULTIPLIER
        }
    return flatSpeedMps * multiplier
}

private fun interpolateStaminaMultiplier(
    hours: Double,
    startHours: Double,
    endHours: Double,
    startMultiplier: Double,
    endMultiplier: Double,
): Double {
    val fraction = ((hours - startHours) / (endHours - startHours)).coerceIn(0.0, 1.0)
    return startMultiplier + (endMultiplier - startMultiplier) * fraction
}

fun applyBikePhysicsSpeedAdjustment(
    flatSpeedMps: Double,
    grade: Double,
    config: TrailGuidanceEtaConfig,
): Double {
    if (!flatSpeedMps.isFinite() || flatSpeedMps <= 0.0) return flatSpeedMps
    if (!grade.isFinite() || abs(grade) < BIKE_GRADE_EFFECT_THRESHOLD) return flatSpeedMps

    val totalMassKg =
        (
            config.userWeightKg.coerceAtLeast(1.0) +
                config.backpackWeightKg.coerceAtLeast(0.0) +
                config.bikeWeightKg.coerceAtLeast(0.0)
        ).coerceAtLeast(1.0)
    val calibratedFlatWheelPower =
        cyclingWheelPowerForSpeed(
            speedMps = flatSpeedMps,
            grade = 0.0,
            totalMassKg = totalMassKg,
        ).coerceAtLeast(0.0)
    val sustainableWheelPower =
        maxOf(
            calibratedFlatWheelPower,
            BIKE_MIN_RIDER_POWER_W * BIKE_DRIVETRAIN_EFFICIENCY,
        )
    val targetWheelPower =
        if (grade < -BIKE_GRADE_EFFECT_THRESHOLD) {
            minOf(sustainableWheelPower, BIKE_DOWNHILL_RIDER_POWER_W * BIKE_DRIVETRAIN_EFFICIENCY)
        } else {
            sustainableWheelPower
        }

    val minSpeedMps = minOf(BIKE_MIN_SPEED_MPS, flatSpeedMps).coerceAtLeast(0.3)
    val maxSpeedMps =
        if (grade > BIKE_GRADE_EFFECT_THRESHOLD) {
            flatSpeedMps
        } else {
            maxOf(flatSpeedMps, BIKE_DOWNHILL_SPEED_CAP_MPS)
        }
    val solvedSpeedMps =
        solveCyclingSpeedForWheelPower(
            targetWheelPower = targetWheelPower,
            grade = grade,
            totalMassKg = totalMassKg,
            minSpeedMps = minSpeedMps,
            maxSpeedMps = maxSpeedMps,
        )

    return if (grade > BIKE_GRADE_EFFECT_THRESHOLD) {
        solvedSpeedMps.coerceIn(minSpeedMps, flatSpeedMps)
    } else {
        solvedSpeedMps.coerceIn(flatSpeedMps, maxSpeedMps)
    }
}

private fun solveCyclingSpeedForWheelPower(
    targetWheelPower: Double,
    grade: Double,
    totalMassKg: Double,
    minSpeedMps: Double,
    maxSpeedMps: Double,
): Double {
    if (maxSpeedMps <= minSpeedMps) return minSpeedMps
    if (cyclingWheelPowerForSpeed(minSpeedMps, grade, totalMassKg) >= targetWheelPower) {
        return minSpeedMps
    }
    if (cyclingWheelPowerForSpeed(maxSpeedMps, grade, totalMassKg) <= targetWheelPower) {
        return maxSpeedMps
    }

    var low = minSpeedMps
    var high = maxSpeedMps
    repeat(48) {
        val mid = (low + high) / 2.0
        if (cyclingWheelPowerForSpeed(mid, grade, totalMassKg) < targetWheelPower) {
            low = mid
        } else {
            high = mid
        }
    }
    return (low + high) / 2.0
}

private fun cyclingWheelPowerForSpeed(
    speedMps: Double,
    grade: Double,
    totalMassKg: Double,
): Double {
    val cosTheta = 1.0 / sqrt(1.0 + grade * grade)
    val gravityForce = totalMassKg * BIKE_GRAVITY_MPS2 * grade
    val rollingForce = totalMassKg * BIKE_GRAVITY_MPS2 * BIKE_UNKNOWN_SURFACE_CRR * cosTheta
    val aerodynamicForce = 0.5 * BIKE_AIR_DENSITY_KG_PER_M3 * BIKE_UPRIGHT_CDA_M2 * speedMps * speedMps
    return (gravityForce + rollingForce + aerodynamicForce) * speedMps
}

private fun applyAdvancedVerticalRateAdjustment(
    candidateSpeedMps: Double,
    elevationDeltaMeters: Double?,
    distanceMeters: Double,
    minSpeedMps: Double,
    config: TrailGuidanceEtaConfig,
): Double {
    if (!config.advancedVerticalRateEnabled) return candidateSpeedMps
    val verticalDelta = elevationDeltaMeters ?: return candidateSpeedMps
    if (distanceMeters < MIN_SEGMENT_METERS_FOR_GRADE) return candidateSpeedMps

    val absGrade =
        (abs(verticalDelta) / distanceMeters)
            .coerceIn(0.0, MAX_ABS_GRADE)
    if (absGrade <= ADVANCED_RATE_EFFECT_START_GRADE) return candidateSpeedMps

    val verticalRateMps =
        when {
            verticalDelta > 0.0 -> config.uphillVerticalMetersPerHour / 3600.0
            verticalDelta < 0.0 -> config.downhillVerticalMetersPerHour / 3600.0
            else -> return candidateSpeedMps
        }
    if (!verticalRateMps.isFinite() || verticalRateMps <= 0.0) return candidateSpeedMps

    val baselineVerticalRateMps = (candidateSpeedMps * absGrade).coerceAtLeast(1e-6)
    val rawMultiplier = verticalRateMps / baselineVerticalRateMps
    if (!rawMultiplier.isFinite() || rawMultiplier <= 0.0) return candidateSpeedMps

    val gradeWeight =
        (
            (absGrade - ADVANCED_RATE_EFFECT_START_GRADE) /
                (ADVANCED_RATE_EFFECT_FULL_GRADE - ADVANCED_RATE_EFFECT_START_GRADE)
        ).coerceIn(0.0, 1.0)
    val adjustedMultiplier =
        exp(ln(rawMultiplier) * gradeWeight)
            .coerceIn(ADVANCED_RATE_MIN_MULTIPLIER, ADVANCED_RATE_MAX_MULTIPLIER)

    return (candidateSpeedMps * adjustedMultiplier).coerceAtLeast(minSpeedMps)
}
