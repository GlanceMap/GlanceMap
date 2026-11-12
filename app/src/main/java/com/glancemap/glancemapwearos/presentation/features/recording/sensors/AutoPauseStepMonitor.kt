@file:Suppress("FunctionNaming")

package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Suppress("LongMethod")
@Composable
internal fun AutoPauseStepMonitor(
    enabled: Boolean,
    onStepCounter: (Float, Long) -> Unit,
) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onStepCounter)
    DisposableEffect(context, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        if (
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
                    PackageManager.PERMISSION_GRANTED
            )
        ) {
            DebugTelemetry.log(
                "TraceRecordingSensors",
                "event=auto_pause_step_monitor registered=false reason=permission",
            )
            return@DisposableEffect onDispose {}
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            DebugTelemetry.log(
                "TraceRecordingSensors",
                "event=auto_pause_step_monitor registered=false reason=unsupported",
            )
            return@DisposableEffect onDispose {}
        }
        val startedAt = SystemClock.elapsedRealtime()
        val disposed = AtomicBoolean(false)
        val callbackCount = AtomicLong()
        val beforeRegistrationCount = AtomicLong()
        val invalidValueCount = AtomicLong()
        val lastCallbackElapsed = AtomicLong()
        val lastSourceElapsed = AtomicLong()
        val thread = HandlerThread("AutoPauseStepMonitor").apply { start() }
        val main = Handler(Looper.getMainLooper())
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val measured = event.timestamp / 1_000_000L
                    if (DebugTelemetry.isEnabled()) {
                        callbackCount.incrementAndGet()
                        lastCallbackElapsed.set(SystemClock.elapsedRealtime())
                        lastSourceElapsed.set(measured)
                    }
                    val value = event.values.firstOrNull()
                    if (value == null || !value.isFinite() || value < 0f) {
                        if (DebugTelemetry.isEnabled()) invalidValueCount.incrementAndGet()
                        return
                    }
                    if (measured < startedAt) {
                        if (DebugTelemetry.isEnabled()) beforeRegistrationCount.incrementAndGet()
                        return
                    }
                    main.post { if (!disposed.get()) callback(value, measured) }
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }
        val registered =
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(thread.looper))
        DebugTelemetry.log(
            "TraceRecordingSensors",
            "event=auto_pause_step_monitor registered=$registered wakeUp=${sensor.isWakeUpSensor} " +
                "reportingMode=${sensor.reportingMode} minDelayUs=${sensor.minDelay} maxDelayUs=${sensor.maxDelay}",
        )
        onDispose {
            disposed.set(true)
            if (registered) manager.unregisterListener(listener)
            val endedAt = SystemClock.elapsedRealtime()
            DebugTelemetry.log(
                "TraceRecordingSensors",
                "event=auto_pause_step_monitor_end registered=$registered durationMs=${endedAt - startedAt} " +
                    "callbacks=${callbackCount.get()} beforeRegistration=${beforeRegistrationCount.get()} " +
                    "invalidValues=${invalidValueCount.get()} " +
                    "lastCallbackAgeMs=${lastCallbackElapsed.get().takeIf { it > 0L }?.let { endedAt - it } ?: -1L} " +
                    "lastSourceAgeMs=${lastSourceElapsed.get().takeIf { it > 0L }?.let { endedAt - it } ?: -1L}",
            )
            thread.quitSafely()
        }
    }
}
