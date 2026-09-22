package com.glancemap.glancemapwearos.domain.sensors

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

internal enum class SensorRateMode { HIGH, LOW }

internal data class CompassSensorAvailability(
    val headingSensorAvailable: Boolean,
    val rotationVectorAvailable: Boolean,
    val magAccelFallbackAvailable: Boolean,
)

internal data class CompassPipelineFlags(
    val usingHeadingSensor: Boolean,
    val usingRotationVector: Boolean,
    val usingMagAccelFallback: Boolean,
)

internal data class CompassSensorRegistrationResult(
    val headingSensorRegistered: Boolean = false,
    val rotationVectorRegistered: Boolean = false,
    val accelerometerRegistered: Boolean = false,
    val magnetometerRegistered: Boolean = false,
) {
    fun isOperational(pipeline: HeadingPipeline): Boolean =
        when (pipeline) {
            HeadingPipeline.HEADING_SENSOR -> headingSensorRegistered
            HeadingPipeline.ROTATION_VECTOR -> rotationVectorRegistered
            HeadingPipeline.MAG_ACCEL_FALLBACK -> accelerometerRegistered && magnetometerRegistered
            HeadingPipeline.NONE -> false
        }
}

internal data class SensorMagAccelComponentSample(
    val sourceTimestampElapsedRealtimeMs: Long? = null,
    val registrationGeneration: Long? = null,
)

internal data class SensorMagAccelPairState(
    val accelerometer: SensorMagAccelComponentSample = SensorMagAccelComponentSample(),
    val magnetometer: SensorMagAccelComponentSample = SensorMagAccelComponentSample(),
)

internal enum class SensorMagAccelComponent {
    ACCELEROMETER,
    MAGNETOMETER,
}

internal enum class SensorMagAccelPairReason(
    val telemetryToken: String,
) {
    ACCEPTED("accepted"),
    ACCELEROMETER_MISSING("accelerometer_missing"),
    MAGNETOMETER_MISSING("magnetometer_missing"),
    ACCELEROMETER_GENERATION_MISMATCH("accelerometer_generation_mismatch"),
    MAGNETOMETER_GENERATION_MISMATCH("magnetometer_generation_mismatch"),
    ACCELEROMETER_STALE("accelerometer_stale"),
    MAGNETOMETER_STALE("magnetometer_stale"),
    EXCESSIVE_SKEW("excessive_skew"),
}

internal data class SensorMagAccelPairValidity(
    val reason: SensorMagAccelPairReason,
    val accelerometerSourceTimestampElapsedRealtimeMs: Long?,
    val magnetometerSourceTimestampElapsedRealtimeMs: Long?,
    val accelerometerAgeMs: Long?,
    val magnetometerAgeMs: Long?,
    val pairAgeMs: Long?,
    val pairSkewMs: Long?,
) {
    val accepted: Boolean
        get() = reason == SensorMagAccelPairReason.ACCEPTED
}

internal data class CompassRegistrationResetState(
    val headingRelockUntilElapsedMs: Long,
    val magneticInterferenceStartupGraceUntilElapsedMs: Long,
    val pendingBootstrapRawSamplesToIgnore: Int,
    val startupStabilizationUntilElapsedMs: Long,
    val pendingStartupBogusSamplesToIgnore: Int,
    val pendingStartupHeadingPublishesToMask: Int,
    val startupHeadingPublishMaskUntilElapsedMs: Long,
)

internal data class RotationVectorUncertaintyUpdate(
    val changed: Boolean,
    val uncertaintyDeg: Float,
)

internal fun remapForDisplayRotation(
    rotation: Int,
    inR: FloatArray,
    outR: FloatArray,
) {
    when (rotation) {
        Surface.ROTATION_0 -> System.arraycopy(inR, 0, outR, 0, 9)
        Surface.ROTATION_90 ->
            SensorManager.remapCoordinateSystem(
                inR,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                outR,
            )
        Surface.ROTATION_180 ->
            SensorManager.remapCoordinateSystem(
                inR,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Y,
                outR,
            )
        Surface.ROTATION_270 ->
            SensorManager.remapCoordinateSystem(
                inR,
                SensorManager.AXIS_MINUS_Y,
                SensorManager.AXIS_X,
                outR,
            )
        else -> System.arraycopy(inR, 0, outR, 0, 9)
    }
}

@Suppress("DEPRECATION")
internal fun queryDisplayRotation(windowManager: WindowManager): Int =
    runCatching { windowManager.defaultDisplay.rotation }
        .getOrDefault(Surface.ROTATION_0)

internal fun shouldSampleDisplayRotation(
    nowElapsedMs: Long,
    lastSampleAtMs: Long,
): Boolean = nowElapsedMs - lastSampleAtMs >= DISPLAY_ROTATION_SAMPLE_INTERVAL_MS

internal fun resolveCurrentHeadingPipeline(
    usingHeadingSensor: Boolean,
    usingRotationVector: Boolean,
    usingMagAccelFallback: Boolean,
): HeadingPipeline =
    when {
        usingHeadingSensor -> HeadingPipeline.HEADING_SENSOR
        usingRotationVector -> HeadingPipeline.ROTATION_VECTOR
        usingMagAccelFallback -> HeadingPipeline.MAG_ACCEL_FALLBACK
        else -> HeadingPipeline.NONE
    }

internal fun applyHeadingPipelineFlags(pipeline: HeadingPipeline): CompassPipelineFlags =
    CompassPipelineFlags(
        usingHeadingSensor = pipeline == HeadingPipeline.HEADING_SENSOR,
        usingRotationVector = pipeline == HeadingPipeline.ROTATION_VECTOR,
        usingMagAccelFallback = pipeline == HeadingPipeline.MAG_ACCEL_FALLBACK,
    )

internal fun resolveCompassSensorAvailability(
    headingSensor: Sensor?,
    rotationVector: Sensor?,
    accelerometer: Sensor?,
    magnetometer: Sensor?,
): CompassSensorAvailability =
    CompassSensorAvailability(
        headingSensorAvailable = headingSensor != null,
        rotationVectorAvailable = rotationVector != null,
        magAccelFallbackAvailable = accelerometer != null && magnetometer != null,
    )

internal fun resolveActiveHeadingSource(
    pipeline: HeadingPipeline,
): HeadingSource =
    when (pipeline) {
        HeadingPipeline.HEADING_SENSOR -> HeadingSource.HEADING_SENSOR
        HeadingPipeline.ROTATION_VECTOR -> HeadingSource.ROTATION_VECTOR
        HeadingPipeline.MAG_ACCEL_FALLBACK -> HeadingSource.MAG_ACCEL_FALLBACK
        HeadingPipeline.NONE -> HeadingSource.NONE
    }

internal fun bootstrapSamplesToIgnoreForPipeline(pipeline: HeadingPipeline): Int =
    when (pipeline) {
        HeadingPipeline.ROTATION_VECTOR -> BOOTSTRAP_RAW_SAMPLES_TO_IGNORE_ROTATION_VECTOR
        HeadingPipeline.MAG_ACCEL_FALLBACK -> BOOTSTRAP_RAW_SAMPLES_TO_IGNORE_MAG_ACCEL
        HeadingPipeline.HEADING_SENSOR -> BOOTSTRAP_RAW_SAMPLES_TO_IGNORE_HEADING_SENSOR
        HeadingPipeline.NONE -> BOOTSTRAP_RAW_SAMPLES_TO_IGNORE_DEFAULT
    }

internal fun startupBogusSamplesToIgnoreForPipeline(pipeline: HeadingPipeline): Int =
    when (pipeline) {
        HeadingPipeline.ROTATION_VECTOR -> STARTUP_BOGUS_SAMPLES_TO_IGNORE_ROTATION_VECTOR
        else -> 0
    }

internal fun startupHeadingPublishesToMaskForPipeline(
    pipeline: HeadingPipeline,
    hasPreviousPublishedHeading: Boolean,
): Int {
    if (!hasPreviousPublishedHeading) return 0
    return when (pipeline) {
        HeadingPipeline.ROTATION_VECTOR -> STARTUP_HEADING_PUBLISH_MASK_SAMPLES_ROTATION_VECTOR
        HeadingPipeline.HEADING_SENSOR,
        HeadingPipeline.MAG_ACCEL_FALLBACK,
        -> STARTUP_HEADING_PUBLISH_MASK_SAMPLES_DEFAULT
        HeadingPipeline.NONE -> 0
    }
}

internal fun prepareCompassRegistrationResetState(
    nowElapsedMs: Long,
    pipeline: HeadingPipeline,
    hasPreviousPublishedHeading: Boolean,
    currentHeadingRelockUntilElapsedMs: Long,
    currentMagneticInterferenceStartupGraceUntilElapsedMs: Long,
): CompassRegistrationResetState {
    val pendingStartupHeadingPublishesToMask =
        startupHeadingPublishesToMaskForPipeline(
            pipeline = pipeline,
            hasPreviousPublishedHeading = hasPreviousPublishedHeading,
        )
    return CompassRegistrationResetState(
        headingRelockUntilElapsedMs =
            maxOf(
                currentHeadingRelockUntilElapsedMs,
                nowElapsedMs + HEADING_RELOCK_WINDOW_MS,
            ),
        magneticInterferenceStartupGraceUntilElapsedMs =
            maxOf(
                currentMagneticInterferenceStartupGraceUntilElapsedMs,
                nowElapsedMs + MAG_INTERFERENCE_STARTUP_GRACE_MS,
            ),
        pendingBootstrapRawSamplesToIgnore = bootstrapSamplesToIgnoreForPipeline(pipeline),
        startupStabilizationUntilElapsedMs = nowElapsedMs + STARTUP_STABILIZATION_WINDOW_MS,
        pendingStartupBogusSamplesToIgnore = startupBogusSamplesToIgnoreForPipeline(pipeline),
        pendingStartupHeadingPublishesToMask = pendingStartupHeadingPublishesToMask,
        startupHeadingPublishMaskUntilElapsedMs =
            if (pendingStartupHeadingPublishesToMask > 0) {
                nowElapsedMs + STARTUP_HEADING_PUBLISH_MASK_WINDOW_MS
            } else {
                0L
            },
    )
}

internal fun sensorDelayForRate(mode: SensorRateMode): Int =
    when (mode) {
        SensorRateMode.HIGH -> SensorManager.SENSOR_DELAY_GAME // ~50 Hz while compass is active
        SensorRateMode.LOW -> SensorManager.SENSOR_DELAY_NORMAL // ~5 Hz (was UI ~16 Hz)
    }

internal fun shouldReuseSensorCallbackHandler(
    callbackThreadAlive: Boolean,
    callbackThreadStopping: Boolean,
): Boolean = callbackThreadAlive && !callbackThreadStopping

internal fun updateSensorMagAccelPairState(
    state: SensorMagAccelPairState,
    component: SensorMagAccelComponent,
    sourceTimestampElapsedRealtimeMs: Long?,
    registrationGeneration: Long,
): SensorMagAccelPairState {
    val sample =
        SensorMagAccelComponentSample(
            sourceTimestampElapsedRealtimeMs = sourceTimestampElapsedRealtimeMs,
            registrationGeneration = registrationGeneration,
        )
    return when (component) {
        SensorMagAccelComponent.ACCELEROMETER -> state.copy(accelerometer = sample)
        SensorMagAccelComponent.MAGNETOMETER -> state.copy(magnetometer = sample)
    }
}

// Each branch is a distinct, externally reported safety rejection; collapsing them obscures the
// trace reason needed to diagnose stale or cross-generation physical samples.
@Suppress("CyclomaticComplexMethod")
internal fun validateSensorMagAccelPair(
    state: SensorMagAccelPairState,
    nowElapsedRealtimeMs: Long,
    registrationGeneration: Long,
    maxComponentAgeMs: Long = SENSOR_MAG_ACCEL_COMPONENT_STALE_MS,
    maxPairSkewMs: Long = SENSOR_MAG_ACCEL_PAIR_MAX_SKEW_MS,
): SensorMagAccelPairValidity {
    val accelerometerTimestamp = state.accelerometer.sourceTimestampElapsedRealtimeMs
    val magnetometerTimestamp = state.magnetometer.sourceTimestampElapsedRealtimeMs
    val accelerometerAgeMs = accelerometerTimestamp?.let { (nowElapsedRealtimeMs - it).coerceAtLeast(0L) }
    val magnetometerAgeMs = magnetometerTimestamp?.let { (nowElapsedRealtimeMs - it).coerceAtLeast(0L) }
    val pairAgeMs =
        if (accelerometerAgeMs != null && magnetometerAgeMs != null) {
            maxOf(accelerometerAgeMs, magnetometerAgeMs)
        } else {
            null
        }
    val pairSkewMs =
        if (accelerometerTimestamp != null && magnetometerTimestamp != null) {
            abs(accelerometerTimestamp - magnetometerTimestamp)
        } else {
            null
        }
    val reason =
        when {
            accelerometerTimestamp == null -> SensorMagAccelPairReason.ACCELEROMETER_MISSING
            magnetometerTimestamp == null -> SensorMagAccelPairReason.MAGNETOMETER_MISSING
            state.accelerometer.registrationGeneration != registrationGeneration ->
                SensorMagAccelPairReason.ACCELEROMETER_GENERATION_MISMATCH
            state.magnetometer.registrationGeneration != registrationGeneration ->
                SensorMagAccelPairReason.MAGNETOMETER_GENERATION_MISMATCH
            accelerometerAgeMs?.let { it >= maxComponentAgeMs } == true ->
                SensorMagAccelPairReason.ACCELEROMETER_STALE
            magnetometerAgeMs?.let { it >= maxComponentAgeMs } == true ->
                SensorMagAccelPairReason.MAGNETOMETER_STALE
            pairSkewMs != null && pairSkewMs > maxPairSkewMs ->
                SensorMagAccelPairReason.EXCESSIVE_SKEW
            else -> SensorMagAccelPairReason.ACCEPTED
        }
    return SensorMagAccelPairValidity(
        reason = reason,
        accelerometerSourceTimestampElapsedRealtimeMs = accelerometerTimestamp,
        magnetometerSourceTimestampElapsedRealtimeMs = magnetometerTimestamp,
        accelerometerAgeMs = accelerometerAgeMs,
        magnetometerAgeMs = magnetometerAgeMs,
        pairAgeMs = pairAgeMs,
        pairSkewMs = pairSkewMs,
    )
}

internal fun buildSensorMagAccelPairTrace(
    registrationGeneration: Long,
    validity: SensorMagAccelPairValidity,
): String =
    "sensor_mag_accel_pair generation=$registrationGeneration " +
        "accepted=${validity.accepted} reason=${validity.reason.telemetryToken} " +
        "accelAtMs=${validity.accelerometerSourceTimestampElapsedRealtimeMs ?: "na"} " +
        "magAtMs=${validity.magnetometerSourceTimestampElapsedRealtimeMs ?: "na"} " +
        "pairAgeMs=${validity.pairAgeMs ?: "na"} pairSkewMs=${validity.pairSkewMs ?: "na"}"

internal fun buildCompassSensorRegistrationTrace(
    registrationGeneration: Long,
    pipeline: HeadingPipeline,
    result: CompassSensorRegistrationResult,
): String =
    "sensor_registration generation=$registrationGeneration pipeline=${pipeline.name} " +
        "heading=${result.headingSensorRegistered} rotVec=${result.rotationVectorRegistered} " +
        "accel=${result.accelerometerRegistered} mag=${result.magnetometerRegistered} " +
        "operational=${result.isOperational(pipeline)}"

// This directly mirrors Android's three listener-registration contracts, including optional
// diagnostic magnetometer registration for the heading and rotation-vector pipelines.
@Suppress("CyclomaticComplexMethod")
internal fun registerCompassSensors(
    sensorManager: SensorManager,
    listener: SensorEventListener,
    callbackHandler: Handler,
    pipeline: HeadingPipeline,
    headingRate: Int,
    accuracyRate: Int,
    headingSensor: Sensor?,
    rotationVector: Sensor?,
    magnetometer: Sensor?,
    accelerometer: Sensor?,
): CompassSensorRegistrationResult {
    val result =
        when (pipeline) {
            HeadingPipeline.HEADING_SENSOR -> {
                CompassSensorRegistrationResult(
                    headingSensorRegistered =
                        headingSensor?.let {
                            sensorManager.registerListener(listener, it, headingRate, callbackHandler)
                        } ?: false,
                    magnetometerRegistered =
                        magnetometer?.let {
                            sensorManager.registerListener(listener, it, accuracyRate, callbackHandler)
                        } ?: false,
                )
            }
            HeadingPipeline.ROTATION_VECTOR -> {
                CompassSensorRegistrationResult(
                    rotationVectorRegistered =
                        rotationVector?.let {
                            sensorManager.registerListener(listener, it, headingRate, callbackHandler)
                        } ?: false,
                    magnetometerRegistered =
                        magnetometer?.let {
                            sensorManager.registerListener(listener, it, accuracyRate, callbackHandler)
                        } ?: false,
                )
            }
            HeadingPipeline.MAG_ACCEL_FALLBACK -> {
                if (accelerometer != null && magnetometer != null) {
                    CompassSensorRegistrationResult(
                        accelerometerRegistered =
                            sensorManager.registerListener(listener, accelerometer, headingRate, callbackHandler),
                        magnetometerRegistered =
                            sensorManager.registerListener(listener, magnetometer, headingRate, callbackHandler),
                    )
                } else {
                    CompassSensorRegistrationResult()
                }
            }
            HeadingPipeline.NONE -> CompassSensorRegistrationResult()
        }
    if (pipeline != HeadingPipeline.NONE && !result.isOperational(pipeline)) {
        sensorManager.unregisterListener(listener)
    }
    return result
}

internal fun resolveSensorReportedAccuracy(
    pipeline: HeadingPipeline,
    headingAccuracy: Int,
    headingUncertaintyDeg: Float,
    magAccuracy: Int,
    rotVecAccuracy: Int,
    rotVecHeadingUncertaintyDeg: Float,
): Int {
    if (pipeline == HeadingPipeline.NONE) {
        return SensorManager.SENSOR_STATUS_UNRELIABLE
    }
    if (pipeline == HeadingPipeline.HEADING_SENSOR) {
        val fromUncertainty = headingAccuracyFromUncertainty(headingUncertaintyDeg)
        return when {
            fromUncertainty != SensorManager.SENSOR_STATUS_UNRELIABLE -> fromUncertainty
            headingAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE -> headingAccuracy
            magAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE -> magAccuracy
            else -> SensorManager.SENSOR_STATUS_UNRELIABLE
        }
    }
    if (pipeline == HeadingPipeline.ROTATION_VECTOR) {
        val fromUncertainty = headingAccuracyFromUncertainty(rotVecHeadingUncertaintyDeg)
        return when {
            fromUncertainty != SensorManager.SENSOR_STATUS_UNRELIABLE -> fromUncertainty
            rotVecAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE -> rotVecAccuracy
            magAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE ->
                maxOf(magAccuracy, SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
            else -> SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        }
    }
    return when {
        magAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE -> magAccuracy
        else -> SensorManager.SENSOR_STATUS_UNRELIABLE
    }
}

internal fun decodeRotationVectorUncertainty(
    previousUncertaintyDeg: Float,
    values: FloatArray,
): RotationVectorUncertaintyUpdate {
    if (values.size <= ROTATION_VECTOR_UNCERTAINTY_INDEX) {
        return RotationVectorUncertaintyUpdate(
            changed = false,
            uncertaintyDeg = previousUncertaintyDeg,
        )
    }
    val uncertaintyRad = values[ROTATION_VECTOR_UNCERTAINTY_INDEX]
    val uncertaintyDeg =
        if (uncertaintyRad.isFinite() && uncertaintyRad >= 0f) {
            Math.toDegrees(uncertaintyRad.toDouble()).toFloat()
        } else {
            Float.NaN
        }
    val unchanged =
        when {
            !previousUncertaintyDeg.isFinite() && !uncertaintyDeg.isFinite() -> true
            previousUncertaintyDeg.isFinite() && uncertaintyDeg.isFinite() ->
                abs(previousUncertaintyDeg - uncertaintyDeg) < ROTATION_VECTOR_UNCERTAINTY_EPSILON_DEG
            else -> false
        }
    return RotationVectorUncertaintyUpdate(
        changed = !unchanged,
        uncertaintyDeg = uncertaintyDeg,
    )
}

internal fun buildCompassHeadingSampleLog(
    rawHeading: Float,
    smoothedHeading: Float,
    combinedAccuracy: Int,
    sensorReportedAccuracy: Int,
    inferredHeadingAccuracy: Int,
    declinationDeg: Float?,
    northReferenceMode: NorthReferenceMode,
    sensorRateMode: SensorRateMode,
    northStatus: NorthReferenceStatus,
    activeHeadingSource: HeadingSource,
    headingSourceMode: CompassHeadingSourceMode,
    magneticFieldStrengthEmaUt: Float,
    magneticInterferenceDetected: Boolean,
): String =
    "heading raw=${rawHeading.format(1)} smoothed=${smoothedHeading.format(1)} " +
        "acc=$combinedAccuracy sensorAcc=$sensorReportedAccuracy " +
        "inferredAcc=$inferredHeadingAccuracy " +
        "decl=${declinationDeg.formatOrNA(1)} ref=$northReferenceMode mode=$sensorRateMode " +
        "effectiveRef=${northStatus.effectiveMode.name} declReady=${northStatus.declinationAvailable} " +
        "src=${activeHeadingSource.telemetryToken} requested=${headingSourceMode.name} " +
        "magUt=${magneticFieldStrengthEmaUt.takeIf { it.isFinite() }.formatOrNA(1)} " +
        "magInterf=$magneticInterferenceDetected"
