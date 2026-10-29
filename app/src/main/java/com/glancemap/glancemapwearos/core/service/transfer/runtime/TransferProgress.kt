package com.glancemap.glancemapwearos.core.service.transfer.runtime
import android.content.res.Resources
import android.os.SystemClock
import com.glancemap.glancemapwearos.R

internal class UiUpdateThrottler(
    private val minIntervalMs: Long,
    private val minStepBytes: Long,
) {
    private var lastTime = 0L
    private var lastBytes = 0L

    fun shouldUpdate(bytesCopied: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastTime == 0L) {
            lastTime = now
            lastBytes = bytesCopied
            return true
        }

        val timeDelta = now - lastTime
        val bytesDelta = bytesCopied - lastBytes

        if (timeDelta >= minIntervalMs || bytesDelta >= minStepBytes) {
            lastTime = now
            lastBytes = bytesCopied
            return true
        }
        return false
    }
}

internal class ProgressTracker(
    private val totalSize: Long,
    private val resources: Resources,
) {
    private var lastTime = 0L
    private var lastBytes = 0L

    fun formatStatus(bytesCopied: Long): String {
        val now = SystemClock.elapsedRealtime()
        if (lastTime == 0L) {
            lastTime = now
            lastBytes = bytesCopied
            return baseText(bytesCopied, 0f)
        }

        val timeDelta = now - lastTime
        val bytesDelta = bytesCopied - lastBytes

        val speedMiBps =
            if (timeDelta > 0) {
                (bytesDelta * 1000f) / (timeDelta * 1024f * 1024f)
            } else {
                0f
            }

        lastTime = now
        lastBytes = bytesCopied

        return baseText(bytesCopied, speedMiBps)
    }

    private fun baseText(
        bytesCopied: Long,
        speedMiBps: Float?,
    ): String {
        val mib = bytesCopied / 1_048_576f
        val totalStr =
            if (totalSize >
                0
            ) {
                resources.getString(R.string.download_transfer_total_mib, totalSize / 1_048_576f)
            } else {
                ""
            }
        val speedStr = speedMiBps?.let { resources.getString(R.string.download_transfer_speed_mib, it) }.orEmpty()
        return resources.getString(R.string.download_transfer_progress, mib, totalStr, speedStr)
    }
}
