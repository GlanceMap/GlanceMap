package com.glancemap.glancemapwearos.domain.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import kotlin.math.abs

internal const val HEADING_SENSOR_TYPE = 42
internal const val HEADING_SENSOR_STRING_TYPE = "android.sensor.heading"

internal fun resolveHeadingSensor(sensorManager: SensorManager): Sensor? {
    sensorManager.getDefaultSensor(HEADING_SENSOR_TYPE)?.let { return it }
    runCatching {
        sensorManager.getDefaultSensor(HEADING_SENSOR_TYPE, true)
    }.getOrNull()?.let { return it }

    val allSensors =
        runCatching { sensorManager.getSensorList(Sensor.TYPE_ALL) }
            .getOrDefault(emptyList())
    return allSensors.firstOrNull { sensor ->
        sensor.type == HEADING_SENSOR_TYPE || sensor.stringType == HEADING_SENSOR_STRING_TYPE
    }
}

internal const val TURN_RATE_EMA_ALPHA = 0.30f
internal const val MODERATE_TURN_RATE_DEG_PER_SEC = 20f
internal const val FAST_TURN_RATE_DEG_PER_SEC = 68f
internal const val HEADING_SMOOTHING_WINDOW_SIZE = 5
internal const val HEADING_RELOCK_WINDOW_MS = 900L
internal const val BOOTSTRAP_RAW_SAMPLES_TO_IGNORE_DEFAULT = 1
internal const val BOOTSTRAP_RAW_SAMPLES_TO_IGNORE_ROTATION_VECTOR = 1
internal const val BOOTSTRAP_RAW_SAMPLES_TO_IGNORE_MAG_ACCEL = 1
internal const val BOOTSTRAP_RAW_SAMPLES_TO_IGNORE_HEADING_SENSOR = 1
internal const val STARTUP_STABILIZATION_WINDOW_MS = 1_500L
internal const val STARTUP_BOGUS_SAMPLES_TO_IGNORE_ROTATION_VECTOR = 2
internal const val STARTUP_TRANSIENT_CONFIRM_MAX_DELTA_DEG = 18f
internal const val STARTUP_HEADING_PUBLISH_MASK_WINDOW_MS = 650L
internal const val STARTUP_HEADING_PUBLISH_MASK_MIN_DELTA_DEG = 8f
internal const val STARTUP_HEADING_PUBLISH_MASK_SAMPLES_DEFAULT = 1
internal const val STARTUP_HEADING_PUBLISH_MASK_SAMPLES_ROTATION_VECTOR = 2
internal const val ROTATION_VECTOR_UNCERTAINTY_INDEX = 4
internal const val ROTATION_VECTOR_UNCERTAINTY_EPSILON_DEG = 0.5f
internal const val SENSOR_HEADING_LARGE_JUMP_REJECT_DEG = 75f
internal const val HEADING_LARGE_JUMP_CONFIRM_WINDOW_MS = 600L
internal const val HEADING_LARGE_JUMP_CONFIRM_MAX_DELTA_DEG = 36f
internal const val HEADING_RELOCK_LARGE_JUMP_MIN_CONFIRM_AGE_MS = 200L
internal const val HEADING_LARGE_JUMP_MIN_CONFIRM_AGE_MS = 300L
internal const val HEADING_REANCHOR_MAX_STEP_DEG = 18f
internal const val HEADING_NOISE_GOOD_DEG = 3.0f
internal const val HEADING_NOISE_IMPROVING_DEG = 5.4f
internal const val HEADING_NOISE_POOR_DEG = 8.8f
internal const val DISPLAY_ROTATION_SAMPLE_INTERVAL_MS = 250L
internal const val HEADING_DEBUG_SAMPLE_MS = 10_000L
internal const val DECLINATION_REFRESH_MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
internal const val DECLINATION_REFRESH_MIN_DISTANCE_M = 10_000f
internal const val MAX_DECLINATION_SEED_LOCATION_AGE_MS = 48 * 60 * 60 * 1000L
internal const val MAX_DECLINATION_SEED_LOCATION_ACCURACY_M = 5_000f
internal const val MAX_DECLINATION_CACHE_AGE_MS = 72 * 60 * 60 * 1000L
internal const val COMPASS_TELEMETRY_TAG = "CompassTelemetry"
internal const val COMPASS_SENSOR_THREAD_NAME = "CompassSensorThread"
internal const val DECLINATION_CACHE_PREFS_NAME = "compass_declination_cache"
internal const val PREF_KEY_DECLINATION_DEG = "declination_deg"
internal const val PREF_KEY_LAT_BITS = "lat_bits"
internal const val PREF_KEY_LON_BITS = "lon_bits"
internal const val PREF_KEY_SOURCE_TIMESTAMP_MS = "source_timestamp_ms"
internal const val MAG_FIELD_MIN_VALID_UT = 15f
internal const val MAG_FIELD_MAX_VALID_UT = 85f
internal const val MAG_FIELD_SPIKE_THRESHOLD_UT = 18f
internal const val MAG_INTERFERENCE_STARTUP_GRACE_MS = 1_200L
internal const val MAG_INTERFERENCE_HOLD_MS = 3_000L
internal const val MAG_FIELD_EMA_ALPHA = 0.22f
internal const val DEADBAND_CONVERGENCE_ALPHA = 0.08f
internal const val DEADBAND_CONVERGENCE_EPSILON_DEG = 0.04f

enum class HeadingSource(
    val telemetryToken: String,
) {
    NONE("none"),
    FUSED_ORIENTATION("google_fused"),
    HEADING_SENSOR("heading_sensor"),
    ROTATION_VECTOR("rotation_vector"),
    MAG_ACCEL_FALLBACK("mag_accel_fallback"),
}

data class HeadingSourceStatus(
    val requestedMode: CompassHeadingSourceMode,
    val activeSource: HeadingSource,
    val headingSensorAvailable: Boolean,
    val rotationVectorAvailable: Boolean,
    val magAccelFallbackAvailable: Boolean,
)

data class NorthReferenceStatus(
    val requestedMode: NorthReferenceMode,
    val effectiveMode: NorthReferenceMode,
    val declinationAvailable: Boolean,
    val waitingForDeclination: Boolean,
    val pipeline: HeadingPipeline,
    val automaticByProvider: Boolean = false,
)

enum class HeadingPipeline {
    NONE,
    HEADING_SENSOR,
    ROTATION_VECTOR,
    MAG_ACCEL_FALLBACK,
}

internal fun resolveHeadingPipeline(
    mode: CompassHeadingSourceMode,
    headingSensorAvailable: Boolean,
    rotationVectorAvailable: Boolean,
    magAccelFallbackAvailable: Boolean,
): HeadingPipeline =
    when (mode) {
        CompassHeadingSourceMode.AUTO ->
            when {
                rotationVectorAvailable -> HeadingPipeline.ROTATION_VECTOR
                headingSensorAvailable -> HeadingPipeline.HEADING_SENSOR
                magAccelFallbackAvailable -> HeadingPipeline.MAG_ACCEL_FALLBACK
                else -> HeadingPipeline.NONE
            }
        CompassHeadingSourceMode.TYPE_HEADING ->
            if (headingSensorAvailable) HeadingPipeline.HEADING_SENSOR else HeadingPipeline.NONE
        CompassHeadingSourceMode.ROTATION_VECTOR ->
            if (rotationVectorAvailable) HeadingPipeline.ROTATION_VECTOR else HeadingPipeline.NONE
        CompassHeadingSourceMode.MAGNETOMETER ->
            if (magAccelFallbackAvailable) HeadingPipeline.MAG_ACCEL_FALLBACK else HeadingPipeline.NONE
    }

internal fun resolveNorthReferenceStatus(
    requestedMode: NorthReferenceMode,
    pipeline: HeadingPipeline,
    declinationAvailable: Boolean,
): NorthReferenceStatus {
    val effectiveMode =
        when (pipeline) {
            HeadingPipeline.HEADING_SENSOR ->
                when (requestedMode) {
                    NorthReferenceMode.TRUE -> NorthReferenceMode.TRUE
                    NorthReferenceMode.MAGNETIC ->
                        if (declinationAvailable) NorthReferenceMode.MAGNETIC else NorthReferenceMode.TRUE
                }
            HeadingPipeline.ROTATION_VECTOR,
            HeadingPipeline.MAG_ACCEL_FALLBACK,
            ->
                when (requestedMode) {
                    NorthReferenceMode.TRUE ->
                        if (declinationAvailable) NorthReferenceMode.TRUE else NorthReferenceMode.MAGNETIC
                    NorthReferenceMode.MAGNETIC -> NorthReferenceMode.MAGNETIC
                }
            HeadingPipeline.NONE ->
                when (requestedMode) {
                    NorthReferenceMode.TRUE ->
                        if (declinationAvailable) NorthReferenceMode.TRUE else NorthReferenceMode.MAGNETIC
                    NorthReferenceMode.MAGNETIC -> NorthReferenceMode.MAGNETIC
                }
        }
    return NorthReferenceStatus(
        requestedMode = requestedMode,
        effectiveMode = effectiveMode,
        declinationAvailable = declinationAvailable,
        waitingForDeclination = effectiveMode != requestedMode,
        pipeline = pipeline,
    )
}

internal enum class StartupTransientAction(
    val telemetryToken: String,
) {
    IGNORE_AWAIT_CONFIRMATION("await_confirmation"),
    IGNORE_REPLACE_CANDIDATE("replace_candidate"),
    ACCEPT_CONFIRMED("confirmed"),
}

internal data class StartupTransientDecision(
    val action: StartupTransientAction,
    val nextCandidateHeadingDeg: Float?,
    val nextRemainingSamplesToIgnore: Int,
    val acceptedHeadingDeg: Float?,
)

internal fun resolveStartupTransientAction(
    rawDeg: Float,
    candidateHeadingDeg: Float?,
    remainingSamplesToIgnore: Int,
    withinStartupWindow: Boolean,
    usingRotationVector: Boolean,
    hasInit: Boolean,
): StartupTransientDecision? {
    if (!withinStartupWindow || !usingRotationVector || hasInit || remainingSamplesToIgnore <= 0) {
        return null
    }
    if (candidateHeadingDeg == null) {
        return StartupTransientDecision(
            action = StartupTransientAction.IGNORE_AWAIT_CONFIRMATION,
            nextCandidateHeadingDeg = rawDeg,
            nextRemainingSamplesToIgnore = remainingSamplesToIgnore - 1,
            acceptedHeadingDeg = null,
        )
    }
    val deltaDeg = abs(shortestAngleDiffDeg(target = rawDeg, current = candidateHeadingDeg))
    if (deltaDeg <= STARTUP_TRANSIENT_CONFIRM_MAX_DELTA_DEG) {
        return StartupTransientDecision(
            action = StartupTransientAction.ACCEPT_CONFIRMED,
            nextCandidateHeadingDeg = null,
            nextRemainingSamplesToIgnore = 0,
            acceptedHeadingDeg = rawDeg,
        )
    }
    return StartupTransientDecision(
        action = StartupTransientAction.IGNORE_REPLACE_CANDIDATE,
        nextCandidateHeadingDeg = rawDeg,
        // Keep requiring a coherent pair until the bounded startup window expires. A fixed
        // sample budget could otherwise force an arbitrary third reading into the UI.
        nextRemainingSamplesToIgnore = (remainingSamplesToIgnore - 1).coerceAtLeast(1),
        acceptedHeadingDeg = null,
    )
}

internal fun shouldMaskStartupHeadingPublish(
    candidateHeadingDeg: Float,
    displayedHeadingDeg: Float,
    remainingPublishesToMask: Int,
    withinMaskWindow: Boolean,
): Boolean {
    if (!withinMaskWindow || remainingPublishesToMask <= 0) return false
    val deltaDeg = abs(shortestAngleDiffDeg(target = candidateHeadingDeg, current = displayedHeadingDeg))
    return deltaDeg >= STARTUP_HEADING_PUBLISH_MASK_MIN_DELTA_DEG
}

internal fun shortestAngleDiffDeg(
    target: Float,
    current: Float,
): Float {
    var diff = (target - current + 540f) % 360f - 180f
    if (abs(diff + 180f) < 1e-3f) {
        val rawSigned = target - current
        diff = if (rawSigned >= 0f) 180f else -180f
    }
    return diff
}

internal fun normalize360Deg(deg: Float): Float = (deg % 360f + 360f) % 360f

internal fun headingWithNorthReference(
    azimuthDeg: Float,
    declinationDeg: Float?,
    northReferenceMode: NorthReferenceMode,
): Float {
    val correction =
        when (northReferenceMode) {
            NorthReferenceMode.TRUE -> declinationDeg ?: 0f
            NorthReferenceMode.MAGNETIC -> 0f
        }
    return normalize360Deg(azimuthDeg + correction)
}

internal fun remapHeadingForNorthReferenceSwitch(
    currentHeadingDeg: Float,
    fromMode: NorthReferenceMode,
    toMode: NorthReferenceMode,
    declinationDeg: Float?,
): Float {
    if (!currentHeadingDeg.isFinite()) return currentHeadingDeg
    if (fromMode == toMode) return normalize360Deg(currentHeadingDeg)
    val correction = declinationDeg ?: 0f
    return when {
        fromMode == NorthReferenceMode.MAGNETIC && toMode == NorthReferenceMode.TRUE ->
            normalize360Deg(currentHeadingDeg + correction)
        fromMode == NorthReferenceMode.TRUE && toMode == NorthReferenceMode.MAGNETIC ->
            normalize360Deg(currentHeadingDeg - correction)
        else -> normalize360Deg(currentHeadingDeg)
    }
}

internal fun deadbandConvergenceAlpha(
    diffDeg: Float,
    minDeltaDeg: Float,
    isFastTurn: Boolean,
    isModerateTurn: Boolean,
    isNoisy: Boolean,
): Float {
    if (!diffDeg.isFinite() || !minDeltaDeg.isFinite()) return 0f
    if (isFastTurn || isModerateTurn || isNoisy) return 0f
    val absDiff = abs(diffDeg)
    if (absDiff < DEADBAND_CONVERGENCE_EPSILON_DEG) return 0f
    if (absDiff >= minDeltaDeg) return 0f
    return DEADBAND_CONVERGENCE_ALPHA
}

internal fun resolveHeadingSmoothingMinDelta(
    isFastTurn: Boolean,
    isModerateTurn: Boolean,
    isNoisy: Boolean,
): Float =
    when {
        isFastTurn -> 0.35f
        isModerateTurn -> 0.60f
        isNoisy -> 1.5f
        else -> 0.4f
    }

internal fun resolveHeadingSmoothingAlpha(
    diffDeg: Float,
    isFastTurn: Boolean,
    isModerateTurn: Boolean,
    isNoisy: Boolean,
): Float {
    val absDiff = abs(diffDeg)
    return when {
        isFastTurn -> if (isNoisy) 0.48f else 0.54f
        isModerateTurn -> if (absDiff > 20f) 0.36f else 0.28f
        isNoisy -> if (absDiff > 25f) 0.18f else 0.08f
        else -> if (absDiff > 25f) 0.34f else 0.19f
    }
}

internal enum class LargeJumpAction {
    NONE,
    ACCEPT_IMMEDIATE,
    ACCEPT_CONFIRMED,
    REJECT_PENDING,
}

internal data class SensorLargeJumpInput(
    val jumpDeg: Float,
    val inRelock: Boolean,
    val hasPendingLargeJump: Boolean,
    val pendingDeltaDeg: Float,
    val pendingAgeMs: Long,
)

internal fun resolveSensorLargeJumpAction(input: SensorLargeJumpInput): LargeJumpAction {
    val requiredConfirmationAgeMs =
        if (input.inRelock) {
            HEADING_RELOCK_LARGE_JUMP_MIN_CONFIRM_AGE_MS
        } else {
            HEADING_LARGE_JUMP_MIN_CONFIRM_AGE_MS
        }
    val coherentPendingSample =
        input.hasPendingLargeJump &&
            input.pendingDeltaDeg.isFinite() &&
            input.pendingDeltaDeg <= HEADING_LARGE_JUMP_CONFIRM_MAX_DELTA_DEG
    val confirmationReady =
        coherentPendingSample && input.pendingAgeMs >= requiredConfirmationAgeMs
    return when {
        input.jumpDeg <= SENSOR_HEADING_LARGE_JUMP_REJECT_DEG -> LargeJumpAction.NONE
        confirmationReady -> LargeJumpAction.ACCEPT_CONFIRMED
        else -> LargeJumpAction.REJECT_PENDING
    }
}

internal fun stepHeadingTowardConfirmedReanchor(
    currentHeadingDeg: Float,
    targetHeadingDeg: Float,
    maxStepDeg: Float = HEADING_REANCHOR_MAX_STEP_DEG,
): Float {
    val diffDeg = shortestAngleDiffDeg(target = targetHeadingDeg, current = currentHeadingDeg)
    val boundedStepDeg = diffDeg.coerceIn(-maxStepDeg, maxStepDeg)
    return normalize360Deg(currentHeadingDeg + boundedStepDeg)
}

internal data class MagneticInterferenceState(
    val strengthUt: Float,
    val emaUt: Float,
    val holdUntilElapsedMs: Long,
    val detected: Boolean,
)

internal data class MagneticInterferenceStep(
    val state: MagneticInterferenceState,
    val reason: String,
    val smoothedStrengthUt: Float,
    val deltaUt: Float,
)

internal fun stepMagneticInterferenceState(
    state: MagneticInterferenceState,
    strengthUt: Float,
    nowElapsedMs: Long,
    startupGraceUntilElapsedMs: Long,
): MagneticInterferenceStep {
    if (nowElapsedMs < startupGraceUntilElapsedMs) {
        return MagneticInterferenceStep(
            state =
                state.copy(
                    strengthUt = Float.NaN,
                    emaUt = Float.NaN,
                    holdUntilElapsedMs = 0L,
                    detected = false,
                ),
            reason = "startup_grace",
            smoothedStrengthUt = Float.NaN,
            deltaUt = 0f,
        )
    }

    val previousStrengthUt = state.strengthUt
    val emaUt =
        if (!state.emaUt.isFinite()) {
            strengthUt
        } else {
            MAG_FIELD_EMA_ALPHA * strengthUt + (1f - MAG_FIELD_EMA_ALPHA) * state.emaUt
        }
    val outOfRange = emaUt < MAG_FIELD_MIN_VALID_UT || emaUt > MAG_FIELD_MAX_VALID_UT
    val deltaUt =
        if (previousStrengthUt.isFinite()) {
            abs(strengthUt - previousStrengthUt)
        } else {
            0f
        }
    val spike = previousStrengthUt.isFinite() && deltaUt >= MAG_FIELD_SPIKE_THRESHOLD_UT
    val holdUntilElapsedMs =
        if (outOfRange || spike) {
            nowElapsedMs + MAG_INTERFERENCE_HOLD_MS
        } else {
            state.holdUntilElapsedMs
        }
    val detected = nowElapsedMs < holdUntilElapsedMs
    val reason =
        when {
            outOfRange && spike -> "range+spike"
            outOfRange -> "range"
            spike -> "spike"
            else -> "hold_expired"
        }

    return MagneticInterferenceStep(
        state =
            MagneticInterferenceState(
                strengthUt = strengthUt,
                emaUt = emaUt,
                holdUntilElapsedMs = holdUntilElapsedMs,
                detected = detected,
            ),
        reason = reason,
        smoothedStrengthUt = emaUt,
        deltaUt = deltaUt,
    )
}

internal fun inferHeadingAccuracy(
    noiseDeg: Float,
    turnRateDegPerSec: Float,
): Int {
    if (!noiseDeg.isFinite()) return SensorManager.SENSOR_STATUS_UNRELIABLE
    return when {
        turnRateDegPerSec >= FAST_TURN_RATE_DEG_PER_SEC && noiseDeg > HEADING_NOISE_IMPROVING_DEG ->
            SensorManager.SENSOR_STATUS_ACCURACY_LOW
        turnRateDegPerSec >= FAST_TURN_RATE_DEG_PER_SEC ->
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        noiseDeg <= HEADING_NOISE_GOOD_DEG -> SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        noiseDeg <= HEADING_NOISE_IMPROVING_DEG -> SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        noiseDeg <= HEADING_NOISE_POOR_DEG -> SensorManager.SENSOR_STATUS_ACCURACY_LOW
        else -> SensorManager.SENSOR_STATUS_UNRELIABLE
    }
}

internal fun combineCompassAccuracy(
    sensorAccuracy: Int,
    inferredAccuracy: Int,
    usingRotationVector: Boolean,
    hasMagneticInterference: Boolean = false,
): Int {
    val sensorRank = accuracyRank(sensorAccuracy)
    val inferredRank = accuracyRank(inferredAccuracy)
    var combinedRank =
        when {
            sensorRank == 0 && inferredRank == 0 -> 0
            sensorRank == 0 && usingRotationVector -> 1
            inferredRank == 0 && sensorRank >= 1 -> 1
            sensorRank == 0 -> 0
            inferredRank == 0 -> 0
            else -> minOf(sensorRank, inferredRank)
        }
    if (hasMagneticInterference) {
        combinedRank = minOf(combinedRank, 1)
    }
    return accuracyFromRank(combinedRank)
}

internal fun headingAccuracyFromUncertainty(uncertaintyDeg: Float): Int {
    if (!uncertaintyDeg.isFinite() || uncertaintyDeg < 0f) {
        return SensorManager.SENSOR_STATUS_UNRELIABLE
    }
    return when {
        uncertaintyDeg <= 8f -> SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        uncertaintyDeg <= 15f -> SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        uncertaintyDeg <= 30f -> SensorManager.SENSOR_STATUS_ACCURACY_LOW
        else -> SensorManager.SENSOR_STATUS_UNRELIABLE
    }
}

private fun accuracyRank(accuracy: Int): Int =
    when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 3
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 2
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 1
        else -> 0
    }

private fun accuracyFromRank(rank: Int): Int =
    when (rank.coerceIn(0, 3)) {
        3 -> SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        2 -> SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        1 -> SensorManager.SENSOR_STATUS_ACCURACY_LOW
        else -> SensorManager.SENSOR_STATUS_UNRELIABLE
    }

internal fun distanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Float {
    if (!lat1.isFinite() || !lon1.isFinite()) return Float.POSITIVE_INFINITY
    val result = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, result)
    return result[0]
}

internal fun Float.format(digits: Int): String = "%.${digits}f".format(this)

internal fun Float?.formatOrNA(digits: Int): String = this?.let { "%.${digits}f".format(it) } ?: "n/a"

internal fun Double.format(digits: Int): String = "%.${digits}f".format(this)
