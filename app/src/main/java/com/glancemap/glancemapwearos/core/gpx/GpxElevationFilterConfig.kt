package com.glancemap.glancemapwearos.core.gpx

import com.glancemap.trailcore.recording.RecordingElevationProfileDefaults as Defaults

data class GpxElevationFilterConfig(
    val smoothingDistanceMeters: Float,
    val neutralDiffThresholdMeters: Float,
    val trendActivationThresholdMeters: Float,
    val autoAdjustPerGpx: Boolean,
)

object GpxElevationFilterDefaults {
    const val DEFAULT_SMOOTHING_DISTANCE_METERS = Defaults.DEFAULT_SMOOTHING_DISTANCE_METERS
    const val DEFAULT_NEUTRAL_DIFF_THRESHOLD_METERS = Defaults.DEFAULT_NEUTRAL_DIFF_THRESHOLD_METERS
    const val DEFAULT_TREND_ACTIVATION_THRESHOLD_METERS = Defaults.DEFAULT_TREND_ACTIVATION_THRESHOLD_METERS

    const val MIN_SMOOTHING_DISTANCE_METERS = Defaults.MIN_SMOOTHING_DISTANCE_METERS
    const val MAX_SMOOTHING_DISTANCE_METERS = 100f
    const val STEP_SMOOTHING_DISTANCE_METERS = 1f

    const val MIN_NEUTRAL_DIFF_THRESHOLD_METERS = 0f
    const val MAX_NEUTRAL_DIFF_THRESHOLD_METERS = 2f
    const val STEP_NEUTRAL_DIFF_THRESHOLD_METERS = 0.1f

    const val MIN_TREND_ACTIVATION_THRESHOLD_METERS = Defaults.MIN_TREND_ACTIVATION_THRESHOLD_METERS
    const val MAX_TREND_ACTIVATION_THRESHOLD_METERS = 10f
    const val STEP_TREND_ACTIVATION_THRESHOLD_METERS = 0.5f

    fun defaultConfig(): GpxElevationFilterConfig =
        GpxElevationFilterConfig(
            smoothingDistanceMeters = DEFAULT_SMOOTHING_DISTANCE_METERS,
            neutralDiffThresholdMeters = DEFAULT_NEUTRAL_DIFF_THRESHOLD_METERS,
            trendActivationThresholdMeters = DEFAULT_TREND_ACTIVATION_THRESHOLD_METERS,
            autoAdjustPerGpx = true,
        )

    fun sanitizeSmoothingDistanceMeters(value: Float): Float =
        snap(value, STEP_SMOOTHING_DISTANCE_METERS)
            .coerceIn(MIN_SMOOTHING_DISTANCE_METERS, MAX_SMOOTHING_DISTANCE_METERS)

    fun sanitizeNeutralDiffThresholdMeters(value: Float): Float =
        snap(value, STEP_NEUTRAL_DIFF_THRESHOLD_METERS)
            .coerceIn(MIN_NEUTRAL_DIFF_THRESHOLD_METERS, MAX_NEUTRAL_DIFF_THRESHOLD_METERS)

    fun sanitizeTrendActivationThresholdMeters(value: Float): Float =
        snap(value, STEP_TREND_ACTIVATION_THRESHOLD_METERS)
            .coerceIn(MIN_TREND_ACTIVATION_THRESHOLD_METERS, MAX_TREND_ACTIVATION_THRESHOLD_METERS)

    fun sanitize(config: GpxElevationFilterConfig): GpxElevationFilterConfig =
        GpxElevationFilterConfig(
            smoothingDistanceMeters = sanitizeSmoothingDistanceMeters(config.smoothingDistanceMeters),
            neutralDiffThresholdMeters = sanitizeNeutralDiffThresholdMeters(config.neutralDiffThresholdMeters),
            trendActivationThresholdMeters =
                sanitizeTrendActivationThresholdMeters(
                    config.trendActivationThresholdMeters,
                ),
            autoAdjustPerGpx = config.autoAdjustPerGpx,
        )

    private fun snap(
        value: Float,
        step: Float,
    ): Float {
        if (step <= 0f) return value
        return kotlin.math.round(value / step) * step
    }
}
