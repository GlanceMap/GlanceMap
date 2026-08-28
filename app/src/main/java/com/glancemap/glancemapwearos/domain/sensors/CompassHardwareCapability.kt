package com.glancemap.glancemapwearos.domain.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager

internal data class CompassHardwareCapability(
    val compassFeatureAvailable: Boolean,
    val magnetometerAvailable: Boolean,
) {
    val hardwareCompassUnavailable: Boolean
        get() = !compassFeatureAvailable && !magnetometerAvailable
}

internal fun resolveCompassHardwareCapability(context: Context): CompassHardwareCapability {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    return CompassHardwareCapability(
        compassFeatureAvailable =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS),
        magnetometerAvailable = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
    )
}

internal fun shouldShowCompassHardwareUnavailableNotice(
    capability: CompassHardwareCapability,
    acknowledged: Boolean,
): Boolean =
    // Runtime HeadingSource.NONE is intentionally not an input: it can be a normal temporary state.
    capability.hardwareCompassUnavailable && !acknowledged
