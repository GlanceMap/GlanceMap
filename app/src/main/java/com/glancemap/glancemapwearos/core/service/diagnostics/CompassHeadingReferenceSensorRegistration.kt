package com.glancemap.glancemapwearos.core.service.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.remapForDisplayRotation
import kotlin.math.sqrt

/** Short-lived raw absolute references for a heading-reference test; never a runtime provider. */
internal class CompassHeadingReferenceSensorRegistration(
    private val sensorManager: SensorManager?,
    private val listener: SensorEventListener,
    private val callbackThread: HandlerThread,
    val initialSamples: CompassHeadingReferenceIndependentSamples,
) {
    fun stop() {
        sensorManager?.unregisterListener(listener)
        callbackThread.quitSafely()
    }
}

internal fun startCompassHeadingReferenceSensorRegistration(
    context: Context,
    onSamples: (CompassHeadingReferenceIndependentSamples) -> Unit,
): CompassHeadingReferenceSensorRegistration {
    val appContext = context.applicationContext
    val sensorManager = appContext.getSystemService(SensorManager::class.java)
    val windowManager = appContext.getSystemService(WindowManager::class.java)
    val rotationVector = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val geomagneticRotationVector =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val collector =
        CompassHeadingReferenceSensorCollector(
            rotationVectorAvailable = rotationVector != null,
            geomagneticRotationVectorAvailable = geomagneticRotationVector != null,
            accelMagAvailable = accelerometer != null && magnetometer != null,
            magneticFieldAvailable = magnetometer != null,
            displayRotation = { windowManager?.let(::displayRotation) ?: Surface.ROTATION_0 },
            onSamples = onSamples,
        )
    val thread = HandlerThread("HeadingReferenceSensors").apply { start() }
    val handler = Handler(thread.looper)
    sensorManager?.let { manager ->
        listOfNotNull(rotationVector, geomagneticRotationVector, accelerometer, magnetometer).forEach { sensor ->
            manager.registerListener(
                collector,
                sensor,
                HEADING_REFERENCE_SENSOR_PERIOD_US,
                handler,
            )
        }
    }
    return CompassHeadingReferenceSensorRegistration(
        sensorManager = sensorManager,
        listener = collector,
        callbackThread = thread,
        initialSamples = collector.samples,
    )
}

private class CompassHeadingReferenceSensorCollector(
    rotationVectorAvailable: Boolean,
    geomagneticRotationVectorAvailable: Boolean,
    accelMagAvailable: Boolean,
    magneticFieldAvailable: Boolean,
    private val displayRotation: () -> Int,
    private val onSamples: (CompassHeadingReferenceIndependentSamples) -> Unit,
) : SensorEventListener {
    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var accelerometerValues: FloatArray? = null
    private var accelerometerAtElapsedMs: Long? = null
    private var magnetometerValues: FloatArray? = null
    private var magnetometerAtElapsedMs: Long? = null

    var samples =
        CompassHeadingReferenceIndependentSamples(
            rotationVector = CompassHeadingReferenceIndependentSource(available = rotationVectorAvailable),
            geomagneticRotationVector =
                CompassHeadingReferenceIndependentSource(available = geomagneticRotationVectorAvailable),
            accelMag = CompassHeadingReferenceIndependentSource(available = accelMagAvailable),
            magneticField = CompassHeadingReferenceMagneticFieldSample(available = magneticFieldAvailable),
        )
        private set

    override fun onSensorChanged(event: SensorEvent) {
        val atElapsedMs =
            (event.timestamp / NANOS_PER_MILLISECOND).takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                samples =
                    samples.copy(
                        rotationVector =
                            headingFromRotationVector(event.values, atElapsedMs, displayRotation()),
                    )
            }

            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                samples =
                    samples.copy(
                        geomagneticRotationVector =
                            headingFromRotationVector(event.values, atElapsedMs, displayRotation()),
                    )
            }

            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerValues = event.values.copyOf()
                accelerometerAtElapsedMs = atElapsedMs
                updateAccelMagHeading(atElapsedMs)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetometerValues = event.values.copyOf()
                magnetometerAtElapsedMs = atElapsedMs
                samples =
                    samples.copy(
                        magneticField =
                            CompassHeadingReferenceMagneticFieldSample(
                                available = true,
                                strengthUt = magneticFieldMagnitude(event.values),
                                atElapsedMs = atElapsedMs,
                            ),
                    )
                updateAccelMagHeading(atElapsedMs)
            }

            else -> return
        }
        onSamples(samples)
    }

    override fun onAccuracyChanged(
        sensor: Sensor,
        accuracy: Int,
    ) = Unit

    private fun updateAccelMagHeading(atElapsedMs: Long) {
        val gravity = accelerometerValues ?: return
        val magnetic = magnetometerValues ?: return
        val gravityAtElapsedMs = accelerometerAtElapsedMs ?: return
        val magneticAtElapsedMs = magnetometerAtElapsedMs ?: return
        if (kotlin.math.abs(gravityAtElapsedMs - magneticAtElapsedMs) > MAX_ACCEL_MAG_PAIR_AGE_MS) return
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic)) return
        samples =
            samples.copy(
                accelMag =
                    headingFromRotationMatrix(
                        atElapsedMs = atElapsedMs,
                        displayRotation = displayRotation(),
                    ),
            )
    }

    private fun headingFromRotationVector(
        values: FloatArray,
        atElapsedMs: Long,
        displayRotation: Int,
    ): CompassHeadingReferenceIndependentSource {
        if (values.size < 3) return CompassHeadingReferenceIndependentSource(available = true)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        return headingFromRotationMatrix(atElapsedMs, displayRotation)
    }

    private fun headingFromRotationMatrix(
        atElapsedMs: Long,
        displayRotation: Int,
    ): CompassHeadingReferenceIndependentSource {
        remapForDisplayRotation(
            rotation = displayRotation,
            inR = rotationMatrix,
            outR = remappedRotationMatrix,
        )
        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)
        val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
        return CompassHeadingReferenceIndependentSource(
            available = true,
            headingDeg = azimuthDeg.takeIf(Float::isFinite)?.let(::normalizeHeadingDeg),
            pitchDeg = pitchDeg.takeIf(Float::isFinite),
            rollDeg = rollDeg.takeIf(Float::isFinite),
            atElapsedMs = atElapsedMs,
            northBasis = CompassNorthBasis.MAGNETIC,
        )
    }
}

private fun displayRotation(windowManager: WindowManager): Int =
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

private fun magneticFieldMagnitude(values: FloatArray): Float? {
    if (values.size < 3) return null
    val x = values[0]
    val y = values[1]
    val z = values[2]
    return sqrt(x * x + y * y + z * z).takeIf(Float::isFinite)
}

private fun normalizeHeadingDeg(value: Float): Float = ((value % 360f) + 360f) % 360f

private const val HEADING_REFERENCE_SENSOR_PERIOD_US = 40_000
private const val MAX_ACCEL_MAG_PAIR_AGE_MS = 250L
private const val NANOS_PER_MILLISECOND = 1_000_000L
