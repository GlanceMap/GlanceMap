package com.glancemap.glancemapwearos.presentation.formatting

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import kotlin.math.roundToInt

object DurationFormatter {
    // Keep the existing guard order and presentation thresholds while localizing the result.
    @Suppress("ReturnCount")
    fun formatDurationShort(
        seconds: Double?,
        resources: Resources,
    ): String {
        if (seconds == null || !seconds.isFinite() || seconds <= 0.0) return "--"

        val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        if (totalMinutes < 60) return resources.getString(R.string.navigation_m, totalMinutes)

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0) {
            resources.getString(R.string.navigation_h, hours)
        } else {
            resources.getString(R.string.gpx_h_3, hours, minutes.toString().padStart(2, '0'))
        }
    }
}
