package com.glancemap.glancemapwearos.core.service.transfer.runtime

import android.content.Context
import android.os.PowerManager
import com.glancemap.glancemapwearos.core.service.diagnostics.EnergyDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics

internal class TransferPrewarmHoldManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val powerManager by lazy { appContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    fun hold(
        reason: String,
        timeoutMs: Long,
    ) {
        synchronized(lock) {
            releaseLocked("replace:$reason")
            wakeLock =
                powerManager
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "GlanceMap::TransferPrewarm",
                    ).apply {
                        setReferenceCounted(false)
                        acquire(timeoutMs)
                    }.also { acquiredWakeLock ->
                        EnergyDiagnostics.recordPartialWakeLockAcquired(
                            lockId = System.identityHashCode(acquiredWakeLock),
                            tag = PREWARM_WAKE_LOCK_TAG,
                            timeoutMs = timeoutMs,
                        )
                    }
        }
        TransferDiagnostics.log(
            "Prewarm",
            "Hold wake lock reason=$reason timeoutMs=$timeoutMs",
        )
    }

    fun release(reason: String) {
        synchronized(lock) {
            releaseLocked(reason)
        }
    }

    private fun releaseLocked(reason: String) {
        val existing = wakeLock ?: return
        if (existing.isHeld) {
            existing.release()
            TransferDiagnostics.log("Prewarm", "Release wake lock reason=$reason")
        }
        EnergyDiagnostics.recordPartialWakeLockReleased(System.identityHashCode(existing))
        wakeLock = null
    }

    private companion object {
        const val PREWARM_WAKE_LOCK_TAG = "GlanceMap::TransferPrewarm"
    }
}
