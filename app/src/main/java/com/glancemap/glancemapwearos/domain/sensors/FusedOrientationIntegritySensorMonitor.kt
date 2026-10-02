package com.glancemap.glancemapwearos.domain.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Supplies a tilt-aware, magnetometer-independent turn witness and magnetic integrity to Google Fused. */
internal class FusedOrientationIntegritySensorMonitor(
    context: Context,
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gameRotationVector =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gameRotationMatrix = FloatArray(9)

    private var started = false

    @Volatile private var gameRotationVectorRegistered = false

    @Volatile private var magnetometerRegistered = false
    private var onRelativeHeading: ((RelativeHeadingWitness, Long) -> Unit)? = null
    private var onMagneticField: ((Float, Long) -> Unit)? = null

    val relativeSensorAvailable: Boolean
        get() = gameRotationVector != null

    val magnetometerAvailable: Boolean
        get() = magnetometer != null

    fun start(
        handler: Handler,
        lowPower: Boolean,
        onRelativeHeading: (RelativeHeadingWitness, Long) -> Unit,
        onMagneticField: (Float, Long) -> Unit,
    ) {
        stop()
        this.onRelativeHeading = onRelativeHeading
        this.onMagneticField = onMagneticField
        val relativePeriodUs =
            if (lowPower) INTEGRITY_LOW_POWER_PERIOD_US else INTEGRITY_RELATIVE_PERIOD_US
        val magneticPeriodUs =
            if (lowPower) INTEGRITY_LOW_POWER_PERIOD_US else INTEGRITY_MAGNETIC_PERIOD_US
        val relativeRegistered =
            gameRotationVector?.let { sensor ->
                sensorManager.registerListener(this, sensor, relativePeriodUs, handler)
            } == true
        val magneticRegistered =
            magnetometer?.let { sensor ->
                sensorManager.registerListener(this, sensor, magneticPeriodUs, handler)
            } == true
        gameRotationVectorRegistered = relativeRegistered
        magnetometerRegistered = magneticRegistered
        started = gameRotationVectorRegistered || magnetometerRegistered
    }

    fun stop() {
        if (started) sensorManager.unregisterListener(this)
        started = false
        gameRotationVectorRegistered = false
        magnetometerRegistered = false
        onRelativeHeading = null
        onMagneticField = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started) return
        val atElapsedMs =
            (event.timestamp / NANOS_PER_MILLISECOND).takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (gameRotationVectorRegistered) publishRelativeHeading(event, atElapsedMs)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (magnetometerRegistered) publishMagneticField(event, atElapsedMs)
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor,
        accuracy: Int,
    ) = Unit

    private fun publishRelativeHeading(
        event: SensorEvent,
        atElapsedMs: Long,
    ) {
        if (event.values.size < 3) return
        SensorManager.getRotationMatrixFromVector(gameRotationMatrix, event.values)
        onRelativeHeading?.invoke(
            gameRotationScreenTopWitness(gameRotationMatrix),
            atElapsedMs,
        )
    }

    private fun publishMagneticField(
        event: SensorEvent,
        atElapsedMs: Long,
    ) {
        if (event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        onMagneticField?.invoke(sqrt(x * x + y * y + z * z), atElapsedMs)
    }
}

/**
 * A heading measured from the projected top of the watch screen.
 *
 * TYPE_GAME_ROTATION_VECTOR deliberately has no north reference. Its heading is therefore only
 * suitable as a relative witness for Google Fused, never as the heading displayed on the map.
 */
internal data class RelativeHeadingWitness(
    val headingDeg: Float?,
    val horizontalProjection: Float,
)

/**
 * Finds the horizontal direction of device +Y (the top of a watch screen) in the game-RV world
 * frame. A heading is unavailable when that axis is nearly vertical, because any azimuth would be
 * dominated by wrist pitch/roll noise.
 */
internal fun gameRotationScreenTopWitness(rotationMatrix: FloatArray): RelativeHeadingWitness {
    if (rotationMatrix.size < ROTATION_MATRIX_SIZE) return RelativeHeadingWitness(null, 0f)
    // getRotationMatrixFromVector transforms device coordinates to world coordinates. The second
    // column is therefore the world direction of device +Y / screen top.
    val eastComponent = rotationMatrix[1]
    val northComponent = rotationMatrix[4]
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
    return RelativeHeadingWitness(headingDeg, horizontalProjection)
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
private const val MIN_SCREEN_TOP_HORIZONTAL_PROJECTION = 0.35f
private const val RELATIVE_STEP_BASE_ALLOWANCE_DEG = 5f
private const val RELATIVE_STEP_MAX_RATE_DEG_PER_SEC = 1_080f
private const val RELATIVE_STEP_ABSOLUTE_MAX_DEG = 120f
