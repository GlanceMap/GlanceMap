package com.glancemap.glancemapwearos.domain.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

internal data class FusedIntegrityMonitorCallbacks(
    val registrationGeneration: Long,
    val relativeRegistered: Boolean,
    val magneticRegistered: Boolean,
    val onRelativeHeading: ((RelativeHeadingWitness, Long) -> Unit)?,
    val onMagneticField: ((Float, Long) -> Unit)?,
)

@Suppress("LongParameterList") // Mirrors the monitor's independent registration/callback seams.
internal fun captureFusedIntegrityMonitorCallbacks(
    registrationGeneration: Long,
    activeRegistrationGeneration: Long,
    started: Boolean,
    relativeRegistered: Boolean,
    magneticRegistered: Boolean,
    onRelativeHeading: ((RelativeHeadingWitness, Long) -> Unit)?,
    onMagneticField: ((Float, Long) -> Unit)?,
): FusedIntegrityMonitorCallbacks? =
    if (started && registrationGeneration == activeRegistrationGeneration) {
        FusedIntegrityMonitorCallbacks(
            registrationGeneration = registrationGeneration,
            relativeRegistered = relativeRegistered,
            magneticRegistered = magneticRegistered,
            onRelativeHeading = onRelativeHeading,
            onMagneticField = onMagneticField,
        )
    } else {
        null
    }

/** Supplies a tilt-aware, magnetometer-independent turn witness and magnetic integrity to Google Fused. */
internal class FusedOrientationIntegritySensorMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val gameRotationVector =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private var started = false
    private var registrationGeneration = 0L
    private var activeSensorListener: SensorEventListener? = null

    @Volatile private var gameRotationVectorRegistered = false

    @Volatile private var magnetometerRegistered = false

    val relativeSensorAvailable: Boolean
        get() = gameRotationVector != null

    val magnetometerAvailable: Boolean
        get() = magnetometer != null

    @Synchronized
    fun start(
        handler: Handler,
        lowPower: Boolean,
        onRelativeHeading: (RelativeHeadingWitness, Long) -> Unit,
        onMagneticField: (Float, Long) -> Unit,
    ) {
        stop()
        val listener = createSensorListener(registrationGeneration, onRelativeHeading, onMagneticField)
        activeSensorListener = listener
        val relativePeriodUs =
            if (lowPower) INTEGRITY_LOW_POWER_PERIOD_US else INTEGRITY_RELATIVE_PERIOD_US
        val magneticPeriodUs =
            if (lowPower) INTEGRITY_LOW_POWER_PERIOD_US else INTEGRITY_MAGNETIC_PERIOD_US
        val relativeRegistered =
            gameRotationVector?.let { sensor ->
                sensorManager.registerListener(listener, sensor, relativePeriodUs, handler)
            } == true
        val magneticRegistered =
            magnetometer?.let { sensor ->
                sensorManager.registerListener(listener, sensor, magneticPeriodUs, handler)
            } == true
        gameRotationVectorRegistered = relativeRegistered
        magnetometerRegistered = magneticRegistered
        started = gameRotationVectorRegistered || magnetometerRegistered
    }

    @Synchronized
    fun stop() {
        registrationGeneration += 1L
        started = false
        activeSensorListener?.let(sensorManager::unregisterListener)
        activeSensorListener = null
        gameRotationVectorRegistered = false
        magnetometerRegistered = false
    }

    private fun createSensorListener(
        generation: Long,
        onRelativeHeading: (RelativeHeadingWitness, Long) -> Unit,
        onMagneticField: (Float, Long) -> Unit,
    ): SensorEventListener =
        object : SensorEventListener {
            // Both callbacks and scratch state belong to this registration, including while an
            // old event waits for a restart lock. Call the adapter outside the monitor lock.
            private val gameRotationMatrix = FloatArray(9)
            private var displayRotation = Surface.ROTATION_0
            private var displayRotationSampledAtMs = Long.MIN_VALUE

            override fun onSensorChanged(event: SensorEvent) {
                val callbacks = captureCallbacks(generation, onRelativeHeading, onMagneticField) ?: return
                val atElapsedMs =
                    (event.timestamp / NANOS_PER_MILLISECOND).takeIf { it > 0L }
                        ?: SystemClock.elapsedRealtime()
                when (event.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        if (callbacks.relativeRegistered) {
                            if (
                                displayRotationSampledAtMs == Long.MIN_VALUE ||
                                shouldSampleDisplayRotation(atElapsedMs, displayRotationSampledAtMs)
                            ) {
                                displayRotation = queryDisplayRotation(windowManager)
                                displayRotationSampledAtMs = atElapsedMs
                            }
                            publishRelativeHeading(
                                event,
                                atElapsedMs,
                                callbacks.onRelativeHeading,
                                gameRotationMatrix,
                                displayRotation,
                            )
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        if (callbacks.magneticRegistered) {
                            publishMagneticField(event, atElapsedMs, callbacks.onMagneticField)
                        }
                    }
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor,
                accuracy: Int,
            ) = Unit
        }

    @Synchronized
    private fun captureCallbacks(
        generation: Long,
        onRelativeHeading: (RelativeHeadingWitness, Long) -> Unit,
        onMagneticField: (Float, Long) -> Unit,
    ): FusedIntegrityMonitorCallbacks? =
        captureFusedIntegrityMonitorCallbacks(
            registrationGeneration = generation,
            activeRegistrationGeneration = registrationGeneration,
            started = started,
            relativeRegistered = gameRotationVectorRegistered,
            magneticRegistered = magnetometerRegistered,
            onRelativeHeading = onRelativeHeading,
            onMagneticField = onMagneticField,
        )

    private fun publishRelativeHeading(
        event: SensorEvent,
        atElapsedMs: Long,
        callback: ((RelativeHeadingWitness, Long) -> Unit)?,
        gameRotationMatrix: FloatArray,
        displayRotation: Int,
    ) {
        if (event.values.size < 3) return
        SensorManager.getRotationMatrixFromVector(gameRotationMatrix, event.values)
        callback?.invoke(
            gameRotationScreenTopWitness(gameRotationMatrix, displayRotation),
            atElapsedMs,
        )
    }

    private fun publishMagneticField(
        event: SensorEvent,
        atElapsedMs: Long,
        callback: ((Float, Long) -> Unit)?,
    ) {
        if (event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        callback?.invoke(sqrt(x * x + y * y + z * z), atElapsedMs)
    }
}

/**
 * A heading measured from the projected top of the watch screen.
 *
 * TYPE_GAME_ROTATION_VECTOR deliberately has no north reference. Its heading is therefore only
 * suitable for validating Google Fused and for relative turns anchored to a prior map angle,
 * never as an absolute heading displayed on the map.
 */
internal data class RelativeHeadingWitness(
    val headingDeg: Float?,
    val horizontalProjection: Float,
    val displayRotation: Int = Surface.ROTATION_0,
)

/**
 * Finds the horizontal direction of the current screen top in the game-RV world
 * frame. A heading is unavailable when that axis is nearly vertical, because any azimuth would be
 * dominated by wrist pitch/roll noise.
 */
internal fun gameRotationScreenTopWitness(
    rotationMatrix: FloatArray,
    displayRotation: Int = Surface.ROTATION_0,
): RelativeHeadingWitness {
    if (rotationMatrix.size < ROTATION_MATRIX_SIZE) return RelativeHeadingWitness(null, 0f)
    // Google heading uses +Y, -X, -Y, +X for display rotations 0, 90, 180, 270.
    val axisColumn = if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) 0 else 1
    val sign = if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_180) -1f else 1f
    val eastComponent = sign * rotationMatrix[axisColumn]
    val northComponent = sign * rotationMatrix[3 + axisColumn]
    val horizontalProjection = sqrt(eastComponent * eastComponent + northComponent * northComponent)
    val headingDeg =
        when {
            !eastComponent.isFinite() -> null
            !northComponent.isFinite() -> null
            !horizontalProjection.isFinite() -> null
            horizontalProjection < MIN_SCREEN_TOP_HORIZONTAL_PROJECTION -> null
            else ->
                normalize360Deg(
                    Math.toDegrees(atan2(eastComponent.toDouble(), northComponent.toDouble())).toFloat(),
                )
        }
    return RelativeHeadingWitness(headingDeg, horizontalProjection, displayRotation)
}

internal fun isPlausibleRelativeHeadingStep(
    headingStepDeg: Float,
    elapsedMs: Long,
): Boolean {
    if (!headingStepDeg.isFinite() || elapsedMs <= 0L) return false
    val maximumStepDeg =
        (
            RELATIVE_STEP_BASE_ALLOWANCE_DEG +
                RELATIVE_STEP_MAX_RATE_DEG_PER_SEC * elapsedMs / 1_000f
        ).coerceAtMost(RELATIVE_STEP_ABSOLUTE_MAX_DEG)
    return abs(headingStepDeg) <= maximumStepDeg
}

private const val INTEGRITY_RELATIVE_PERIOD_US = 20_000
private const val INTEGRITY_MAGNETIC_PERIOD_US = 100_000
private const val INTEGRITY_LOW_POWER_PERIOD_US = 200_000
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val ROTATION_MATRIX_SIZE = 9
internal const val MIN_SCREEN_TOP_HORIZONTAL_PROJECTION = 0.35f
private const val RELATIVE_STEP_BASE_ALLOWANCE_DEG = 5f
private const val RELATIVE_STEP_MAX_RATE_DEG_PER_SEC = 1_080f
private const val RELATIVE_STEP_ABSOLUTE_MAX_DEG = 120f
