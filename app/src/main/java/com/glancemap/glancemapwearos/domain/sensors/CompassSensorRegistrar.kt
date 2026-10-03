package com.glancemap.glancemapwearos.domain.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

internal class CompassSensorRegistrar(
    context: Context,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val headingSensor: Sensor? = resolveHeadingSensor(sensorManager)
    val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val availability: CompassSensorAvailability =
        resolveCompassSensorAvailability(
            headingSensor = headingSensor,
            rotationVector = rotationVector,
            accelerometer = accelerometer,
            magnetometer = magnetometer,
        )

    fun register(
        listener: SensorEventListener,
        callbackHandler: Handler,
        pipeline: HeadingPipeline,
        rateMode: SensorRateMode,
    ): CompassSensorRegistrationResult =
        registerCompassSensors(
            sensorManager = sensorManager,
            listener = listener,
            callbackHandler = callbackHandler,
            pipeline = pipeline,
            headingRate = sensorDelayForRate(rateMode),
            accuracyRate = SensorManager.SENSOR_DELAY_UI,
            headingSensor = headingSensor,
            rotationVector = rotationVector,
            magnetometer = magnetometer,
            accelerometer = accelerometer,
        )

    fun unregister(listener: SensorEventListener) {
        sensorManager.unregisterListener(listener)
    }
}
