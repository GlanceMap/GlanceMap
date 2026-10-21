package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import java.util.Locale

internal object GpxElevationFilterUi {
    const val SMOOTHING_LABEL = R.string.settings_smoothing_distance
    const val NOISE_THRESHOLD_LABEL = R.string.settings_noise_threshold
    const val TREND_THRESHOLD_LABEL = R.string.settings_trend_threshold

    fun formatSmoothingDistance(
        valueMeters: Float,
        resources: Resources,
    ): String = String.format(Locale.getDefault(), resources.getString(R.string.settings_0f_m), valueMeters)

    fun formatThreshold(
        valueMeters: Float,
        resources: Resources,
    ): String = String.format(Locale.getDefault(), resources.getString(R.string.settings_1f_m), valueMeters)
}
