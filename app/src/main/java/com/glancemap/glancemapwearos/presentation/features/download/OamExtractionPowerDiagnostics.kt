package com.glancemap.glancemapwearos.presentation.features.download

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry

internal fun captureOamExtractionPowerSnapshot(context: Context): OamExtractionPowerSnapshot {
    val power = context.getSystemService(PowerManager::class.java)
    val activity = context.getSystemService(ActivityManager::class.java)
    val usage = context.getSystemService(UsageStatsManager::class.java)
    return OamExtractionPowerSnapshot(
        deviceIdleMode = runCatching { power?.isDeviceIdleMode }.getOrNull(),
        ignoringBatteryOptimizations =
            runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
        powerSaveMode = runCatching { power?.isPowerSaveMode }.getOrNull(),
        lowPowerStandbyEnabled =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { power?.isLowPowerStandbyEnabled }.getOrNull()
            } else {
                null
            },
        lowPowerStandbyExempt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { power?.isExemptFromLowPowerStandby }.getOrNull()
            } else {
                null
            },
        backgroundRestricted = runCatching { activity?.isBackgroundRestricted }.getOrNull(),
        processImportance =
            runCatching {
                ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance
            }.getOrNull(),
        standbyBucket = runCatching { usage?.appStandbyBucket }.getOrNull(),
    )
}

internal class OamWakeLockObserver(
    private val context: Context,
    private val tag: String,
) {
    private val diagnostics =
        OamWakeLockDiagnostics { event ->
            if (DebugTelemetry.isEnabled()) {
                DebugTelemetry.log(
                    "OamDownload",
                    "$event wakeLockTag=$tag elapsedRealtimeMs=${SystemClock.elapsedRealtime()} " +
                        "uptimeMillis=${SystemClock.uptimeMillis()}",
                )
            }
        }

    val systemEnabled: Boolean? get() = diagnostics.systemEnabled

    fun attach(
        lock: PowerManager.WakeLock,
        generation: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !DebugTelemetry.isEnabled()) return
        diagnostics.begin(generation, System.identityHashCode(lock))
        runCatching {
            lock.setStateListener(context.mainExecutor) { enabled -> diagnostics.onSystemState(generation, enabled) }
        }.onFailure {
            diagnostics.clear()
            DebugTelemetry.log("OamDownload", "event=wake_lock_state_listener_unavailable")
        }
    }

    fun detach(lock: PowerManager.WakeLock?) {
        diagnostics.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && lock != null) {
            runCatching { lock.setStateListener(context.mainExecutor, null) }
        }
    }
}
