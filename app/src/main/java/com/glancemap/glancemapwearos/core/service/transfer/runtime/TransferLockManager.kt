package com.glancemap.glancemapwearos.core.service.transfer.runtime
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import java.util.IdentityHashMap

internal class TransferLockManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val powerManager by lazy { appContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wifiManager by lazy { appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val wakeLockIds = IdentityHashMap<PowerManager.WakeLock, Int>()

    fun acquireWakeLock(
        tag: String,
        timeoutMs: Long,
    ): PowerManager.WakeLock {
        val wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        val lockId = System.identityHashCode(wakeLock)
        if (EnergyDiagnostics.isEnabled()) {
            synchronized(wakeLockIds) {
                wakeLockIds[wakeLock] = lockId
            }
            EnergyDiagnostics.recordPartialWakeLockAcquired(
                lockId = lockId,
                tag = tag,
                timeoutMs = timeoutMs,
            )
        }
        return wakeLock
    }

    fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        if (wakeLock.isHeld) wakeLock.release()
        val lockId =
            synchronized(wakeLockIds) {
                wakeLockIds.remove(wakeLock)
            }
        lockId?.let(EnergyDiagnostics::recordPartialWakeLockReleased)
    }

    fun acquireWifiLock(tag: String): WifiManager.WifiLock =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, tag)
        } else {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
        }.apply {
            setReferenceCounted(false)
            acquire()
        }

    fun releaseWifiLock(wifiLock: WifiManager.WifiLock) {
        if (wifiLock.isHeld) wifiLock.release()
    }
}
