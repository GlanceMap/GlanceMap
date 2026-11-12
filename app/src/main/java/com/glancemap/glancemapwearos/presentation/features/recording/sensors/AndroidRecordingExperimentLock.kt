package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import android.content.Context
import android.os.PowerManager

internal class AndroidRecordingExperimentLock(
    context: Context,
) : RecordingExperimentLock {
    private val lock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlanceMap:RecordingExperiment")
            .apply { setReferenceCounted(false) }

    override val isHeld: Boolean get() = lock.isHeld

    override fun acquire(timeoutMillis: Long) = lock.acquire(timeoutMillis)

    override fun release() = lock.release()
}
