package com.glancemap.glancemapwearos.core.service.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

internal class CompassDeepTraceSensorRegistration(
    private val sensorManager: SensorManager?,
    private val listener: SensorEventListener,
    private val callbackThread: HandlerThread,
    val callbackHandler: Handler,
    val registeredSensors: String,
) {
    fun stop() {
        callbackHandler.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(listener)
        callbackThread.quitSafely()
    }
}

internal fun startCompassDeepTraceSensorRegistration(
    context: Context,
    onSample: (CompassDeepTraceRawSensor, FloatArray, Int, Long) -> Unit,
): CompassDeepTraceSensorRegistration {
    val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    val thread = HandlerThread("CompassDeepTrace").apply { start() }
    val handler = Handler(thread.looper)
    val listener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sensor =
                    when (event.sensor.type) {
                        Sensor.TYPE_GYROSCOPE -> CompassDeepTraceRawSensor.GYROSCOPE
                        Sensor.TYPE_ACCELEROMETER -> CompassDeepTraceRawSensor.ACCELEROMETER
                        Sensor.TYPE_MAGNETIC_FIELD -> CompassDeepTraceRawSensor.MAGNETOMETER
                        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> CompassDeepTraceRawSensor.UNCALIBRATED_MAGNETOMETER
                        else -> return
                    }
                val atElapsedMs =
                    (event.timestamp / 1_000_000L).takeIf { it > 0L }
                        ?: SystemClock.elapsedRealtime()
                onSample(sensor, event.values, event.accuracy, atElapsedMs)
            }

            override fun onAccuracyChanged(
                sensor: Sensor,
                accuracy: Int,
            ) = Unit
        }
    val registeredSensors =
        manager
            ?.let { registerCompassDeepTraceSensors(it, listener, handler) }
            .orEmpty()
    return CompassDeepTraceSensorRegistration(
        sensorManager = manager,
        listener = listener,
        callbackThread = thread,
        callbackHandler = handler,
        registeredSensors = registeredSensors,
    )
}

private fun registerCompassDeepTraceSensors(
    manager: SensorManager,
    listener: SensorEventListener,
    handler: Handler,
): String {
    val registered = mutableListOf<String>()
    listOf(
        Sensor.TYPE_GYROSCOPE to "gyroscope",
        Sensor.TYPE_ACCELEROMETER to "accelerometer",
        Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED to "uncalibrated_magnetometer",
    ).forEach { (sensorType, label) ->
        val sensor = manager.getDefaultSensor(sensorType) ?: return@forEach
        val success =
            runCatching {
                manager.registerListener(listener, sensor, COMPASS_DEEP_TRACE_SENSOR_PERIOD_US, handler)
            }.getOrDefault(false)
        if (success) registered += label
    }
    return registered.joinToString(",")
}

private const val COMPASS_DEEP_TRACE_SENSOR_PERIOD_US = 40_000
