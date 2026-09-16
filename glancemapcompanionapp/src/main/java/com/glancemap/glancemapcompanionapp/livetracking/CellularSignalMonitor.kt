@file:Suppress("DEPRECATION")

package com.glancemap.glancemapcompanionapp.livetracking

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class CellularSignalMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val signalPercent = AtomicInteger(UNKNOWN_SIGNAL_PERCENT)
    private val isRegistered = AtomicBoolean(false)
    private val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private var modernCallback: Any? = null
    private var legacyListener: PhoneStateListener? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!isCellularCapable() || !isRegistered.compareAndSet(false, true)) return
        val manager =
            telephonyManager ?: run {
                isRegistered.set(false)
                return
            }
        signalPercent.set(UNKNOWN_SIGNAL_PERCENT)
        val registered =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val callback =
                        modernCallback
                            ?: ModernSignalCallback().also { modernCallback = it }
                    manager.registerTelephonyCallback(
                        callbackExecutor,
                        callback as TelephonyCallback,
                    )
                } else {
                    registerLegacyListener(manager)
                }
            }.isSuccess
        if (!registered) isRegistered.set(false)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isRegistered.compareAndSet(true, false)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback?.let { callback ->
                    telephonyManager?.unregisterTelephonyCallback(callback as TelephonyCallback)
                }
            } else {
                legacyListener?.let { listener ->
                    telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
                }
            }
        }
        signalPercent.set(UNKNOWN_SIGNAL_PERCENT)
    }

    fun currentPercent(): Int = signalPercent.get()

    fun isAvailable(): Boolean = isCellularCapable()

    private fun isCellularCapable(): Boolean =
        telephonyManager != null &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    @Suppress("DEPRECATION")
    private fun registerLegacyListener(manager: TelephonyManager) {
        val listener =
            legacyListener
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PhoneStateListener(callbackExecutor)
                } else {
                    check(Looper.myLooper() != null) {
                        "Legacy cellular signal monitoring must start on a looper thread"
                    }
                    PhoneStateListener()
                }.also { legacyListener = it }
        manager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
    }

    private fun onSignalStrength(signalStrength: SignalStrength) {
        signalPercent.set(signalStrength.getLevel().toArkluzSignalPercent())
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class ModernSignalCallback :
        TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            onSignalStrength(signalStrength)
        }
    }

    private companion object {
        const val UNKNOWN_SIGNAL_PERCENT = -1
    }
}

internal fun Int.toArkluzSignalPercent(): Int =
    when {
        this < 0 -> -1
        this == 0 -> 0
        this >= MAX_ANDROID_SIGNAL_LEVEL -> 100
        else -> (this * 100) / MAX_ANDROID_SIGNAL_LEVEL
    }

private const val MAX_ANDROID_SIGNAL_LEVEL = 4
