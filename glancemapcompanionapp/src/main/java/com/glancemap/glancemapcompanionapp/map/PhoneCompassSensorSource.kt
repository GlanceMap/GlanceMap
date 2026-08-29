package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener
import java.util.concurrent.CopyOnWriteArraySet

internal enum class PhoneCompassPipeline {
    NONE,
    ROTATION_VECTOR,
    HEADING_SENSOR,
    MAG_ACCEL_FALLBACK,
}

internal data class PhoneCompassState(
    val headingDegrees: Float? = null,
    val isRenderable: Boolean = false,
    val sensorAvailable: Boolean = false,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val pipeline: PhoneCompassPipeline = PhoneCompassPipeline.NONE,
)

internal fun resolvePhoneCompassPipeline(
    hasRotationVector: Boolean,
    hasHeadingSensor: Boolean,
    hasMagAccelerometerFallback: Boolean,
): PhoneCompassPipeline =
    when {
        hasRotationVector -> PhoneCompassPipeline.ROTATION_VECTOR
        hasHeadingSensor -> PhoneCompassPipeline.HEADING_SENSOR
        hasMagAccelerometerFallback -> PhoneCompassPipeline.MAG_ACCEL_FALLBACK
        else -> PhoneCompassPipeline.NONE
    }

internal fun phoneCompassStateForSample(
    pipeline: PhoneCompassPipeline,
    headingDegrees: Float?,
    accuracy: Int,
): PhoneCompassState {
    val available = pipeline != PhoneCompassPipeline.NONE
    val normalizedHeading =
        headingDegrees
            ?.takeIf { available && it.isFinite() }
            ?.let(::normalizePhoneHeadingDegrees)
    return PhoneCompassState(
        headingDegrees = normalizedHeading,
        isRenderable = available && normalizedHeading != null && accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE,
        sensorAvailable = available,
        accuracy = accuracy,
        pipeline = pipeline,
    )
}

/** Map-screen-only SensorManager adapter using the same rotation-vector-first fallback order as Wear. */
internal class PhoneCompassSensorSource(
    context: Context,
) : SensorEventListener,
    CompassEngine {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val headingSensor = resolvePhoneHeadingSensor(sensorManager)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val pipeline =
        resolvePhoneCompassPipeline(
            hasRotationVector = rotationVector != null,
            hasHeadingSensor = headingSensor != null,
            hasMagAccelerometerFallback = magnetometer != null && accelerometer != null,
        )
    private val _state =
        MutableStateFlow(
            phoneCompassStateForSample(
                pipeline = pipeline,
                headingDegrees = null,
                accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
            ),
        )
    val state: StateFlow<PhoneCompassState> = _state.asStateFlow()
    private val listeners = CopyOnWriteArraySet<CompassListener>()
    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var started = false
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var smoothedHeading: Float? = null

    fun start() {
        if (started || pipeline == PhoneCompassPipeline.NONE) return
        started = true
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        smoothedHeading = null
        when (pipeline) {
            PhoneCompassPipeline.ROTATION_VECTOR -> registerPhoneCompassSensor(sensorManager, this, rotationVector)
            PhoneCompassPipeline.HEADING_SENSOR -> registerPhoneCompassSensor(sensorManager, this, headingSensor)
            PhoneCompassPipeline.MAG_ACCEL_FALLBACK -> {
                registerPhoneCompassSensor(sensorManager, this, accelerometer)
                registerPhoneCompassSensor(sensorManager, this, magnetometer)
            }
            PhoneCompassPipeline.NONE -> Unit
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        smoothedHeading = null
        publish(headingDegrees = null)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (started) {
            when (pipeline) {
                PhoneCompassPipeline.ROTATION_VECTOR -> {
                    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        publish(
                            headingFromPhoneRotationMatrix(
                                matrix = rotationMatrix,
                                remappedMatrix = remappedRotationMatrix,
                                orientationAngles = orientationAngles,
                                displayRotation = phoneDisplayRotation(windowManager),
                            ),
                        )
                    }
                }
                PhoneCompassPipeline.HEADING_SENSOR -> {
                    if (event.sensor.type == PHONE_HEADING_SENSOR_TYPE) publish(event.values.firstOrNull())
                }
                PhoneCompassPipeline.MAG_ACCEL_FALLBACK -> {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            event.values.copyInto(gravity)
                            hasGravity = true
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            event.values.copyInto(geomagnetic)
                            hasGeomagnetic = true
                        }
                    }
                    if (
                        hasGravity &&
                        hasGeomagnetic &&
                        SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
                    ) {
                        publish(
                            headingFromPhoneRotationMatrix(
                                matrix = rotationMatrix,
                                remappedMatrix = remappedRotationMatrix,
                                orientationAngles = orientationAngles,
                                displayRotation = phoneDisplayRotation(windowManager),
                            ),
                        )
                    }
                }
                PhoneCompassPipeline.NONE -> Unit
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor,
        accuracy: Int,
    ) {
        if (!started || !sensorBelongsToPhoneCompassPipeline(sensor, pipeline)) return
        this.accuracy = accuracy
        publish(smoothedHeading)
    }

    override fun addCompassListener(compassListener: CompassListener) {
        listeners += compassListener
    }

    override fun removeCompassListener(compassListener: CompassListener) {
        listeners -= compassListener
    }

    override fun getLastHeading(): Float = _state.value.headingDegrees ?: 0f

    override fun getLastAccuracySensorStatus(): Int = _state.value.accuracy

    private fun publish(headingDegrees: Float?) {
        val normalized = headingDegrees?.takeIf(Float::isFinite)?.let(::normalizePhoneHeadingDegrees)
        smoothedHeading = normalized?.let { heading -> smoothPhoneHeading(smoothedHeading, heading) }
        val next = phoneCompassStateForSample(pipeline, smoothedHeading, accuracy)
        if (next == _state.value) return
        _state.value = next
        listeners.forEach { listener ->
            listener.onCompassAccuracyChange(next.accuracy)
            next.headingDegrees?.takeIf { next.isRenderable }?.let(listener::onCompassChanged)
        }
    }
}

private fun registerPhoneCompassSensor(
    sensorManager: SensorManager,
    listener: SensorEventListener,
    sensor: Sensor?,
) {
    sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
}

private fun headingFromPhoneRotationMatrix(
    matrix: FloatArray,
    remappedMatrix: FloatArray,
    orientationAngles: FloatArray,
    displayRotation: Int,
): Float {
    remapForPhoneDisplayRotation(matrix, remappedMatrix, displayRotation)
    SensorManager.getOrientation(remappedMatrix, orientationAngles)
    return Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
}

private fun sensorBelongsToPhoneCompassPipeline(
    sensor: Sensor,
    pipeline: PhoneCompassPipeline,
): Boolean =
    when (pipeline) {
        PhoneCompassPipeline.ROTATION_VECTOR -> sensor.type == Sensor.TYPE_ROTATION_VECTOR
        PhoneCompassPipeline.HEADING_SENSOR -> sensor.type == PHONE_HEADING_SENSOR_TYPE
        PhoneCompassPipeline.MAG_ACCEL_FALLBACK ->
            sensor.type == Sensor.TYPE_ACCELEROMETER || sensor.type == Sensor.TYPE_MAGNETIC_FIELD
        PhoneCompassPipeline.NONE -> false
    }

@Suppress("DEPRECATION")
private fun phoneDisplayRotation(
    windowManager: WindowManager,
): Int = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

internal fun remapForPhoneDisplayRotation(
    input: FloatArray,
    output: FloatArray,
    displayRotation: Int,
) {
    when (displayRotation) {
        Surface.ROTATION_0 -> input.copyInto(output)
        Surface.ROTATION_90 ->
            SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, output)
        Surface.ROTATION_180 ->
            SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, output)
        Surface.ROTATION_270 ->
            SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, output)
        else -> input.copyInto(output)
    }
}

private const val PHONE_HEADING_SENSOR_TYPE = 42
private const val PHONE_HEADING_SENSOR_STRING_TYPE = "android.sensor.heading"

private fun resolvePhoneHeadingSensor(sensorManager: SensorManager): Sensor? =
    sensorManager.getDefaultSensor(PHONE_HEADING_SENSOR_TYPE)
        ?: runCatching { sensorManager.getDefaultSensor(PHONE_HEADING_SENSOR_TYPE, true) }.getOrNull()
        ?: runCatching { sensorManager.getSensorList(Sensor.TYPE_ALL) }
            .getOrDefault(emptyList())
            .firstOrNull { sensor ->
                sensor.type == PHONE_HEADING_SENSOR_TYPE || sensor.stringType == PHONE_HEADING_SENSOR_STRING_TYPE
            }
