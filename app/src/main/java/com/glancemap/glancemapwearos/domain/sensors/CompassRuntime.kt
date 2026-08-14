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
) {
    when (pipeline) {
        HeadingPipeline.HEADING_SENSOR -> {
            headingSensor?.let {
                sensorManager.registerListener(listener, it, headingRate, callbackHandler)
            }
            magnetometer?.let {
                sensorManager.registerListener(listener, it, accuracyRate, callbackHandler)
            }
        }
        HeadingPipeline.ROTATION_VECTOR -> {
            rotationVector?.let {
                sensorManager.registerListener(listener, it, headingRate, callbackHandler)
            }
            magnetometer?.let {
                sensorManager.registerListener(listener, it, accuracyRate, callbackHandler)
            }
        }
        HeadingPipeline.MAG_ACCEL_FALLBACK -> {
            if (accelerometer != null && magnetometer != null) {
                sensorManager.registerListener(listener, accelerometer, headingRate, callbackHandler)
                sensorManager.registerListener(listener, magnetometer, headingRate, callbackHandler)
            }
        }
        HeadingPipeline.NONE -> Unit
    }
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
