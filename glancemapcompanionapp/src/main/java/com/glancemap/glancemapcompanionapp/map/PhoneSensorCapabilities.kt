package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager

/** Hardware availability shown in General Settings; this does not imply that a sensor is active. */
internal data class PhoneSensorCapabilities(
    val gpsAvailable: Boolean,
    val compassFeatureAvailable: Boolean,
    val headingSensorAvailable: Boolean,
    val rotationVectorAvailable: Boolean,
    val accelerometerAvailable: Boolean,
    val magnetometerAvailable: Boolean,
    val gyroscopeAvailable: Boolean,
    val barometerAvailable: Boolean,
    val stepDetectorAvailable: Boolean,
    val stepCounterAvailable: Boolean,
) {
    val compassAvailable: Boolean
        get() = compassFeatureAvailable || magnetometerAvailable
}

internal fun resolvePhoneSensorCapabilities(context: Context): PhoneSensorCapabilities {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val packageManager = context.packageManager

    fun hasSensor(type: Int): Boolean = sensorManager.getDefaultSensor(type) != null

    return PhoneSensorCapabilities(
        gpsAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
        compassFeatureAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS),
        headingSensorAvailable = resolvePhoneHeadingSensor(sensorManager) != null,
        rotationVectorAvailable = hasSensor(Sensor.TYPE_ROTATION_VECTOR),
        accelerometerAvailable = hasSensor(Sensor.TYPE_ACCELEROMETER),
        magnetometerAvailable = hasSensor(Sensor.TYPE_MAGNETIC_FIELD),
        gyroscopeAvailable = hasSensor(Sensor.TYPE_GYROSCOPE),
        barometerAvailable = hasSensor(Sensor.TYPE_PRESSURE),
        stepDetectorAvailable = hasSensor(Sensor.TYPE_STEP_DETECTOR),
        stepCounterAvailable = hasSensor(Sensor.TYPE_STEP_COUNTER),
    )
}
