package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.trailcore.recording.RecordingSettings
import java.util.Locale

internal fun nextFixedInterval(current: Int): Int {
    val intervals =
        RecordingSettings.recordingGpsFixedIntervalSecondsOptions +
            RecordingSettings.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
    return intervals[(intervals.indexOf(current).coerceAtLeast(0) + 1) % intervals.size]
}

internal fun nextScreenOffInterval(current: Int): Int {
    val intervals =
        listOf(RecordingSettings.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS) +
            RecordingSettings.recordingGpsFixedIntervalSecondsOptions +
            RecordingSettings.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
    return intervals[(intervals.indexOf(current).coerceAtLeast(0) + 1) % intervals.size]
}

internal fun intervalLabel(
    context: Context,
    seconds: Int,
): String =
    when (seconds) {
        RecordingSettings.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS ->
            context.getString(R.string.phone_rec_interval_off)
        RecordingSettings.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS ->
            context.getString(R.string.phone_rec_interval_same_as_screen_on)
        else -> context.getString(R.string.phone_rec_interval_seconds, seconds)
    }

internal fun formatRecordingDuration(
    context: Context,
    durationMillis: Long,
): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
    return String.format(
        Locale.getDefault(),
        context.getString(R.string.phone_rec_time_format),
        totalSeconds / 3_600,
        (totalSeconds / 60) % 60,
        totalSeconds % 60,
    )
}

internal fun formatRecordingDistance(
    context: Context,
    distanceMeters: Double,
    isMetric: Boolean,
): String =
    if (isMetric) {
        if (distanceMeters >= 1_000.0) {
            context.getString(R.string.phone_rec_distance_kilometers, distanceMeters / 1_000.0)
        } else {
            context.getString(R.string.phone_rec_distance_meters, distanceMeters)
        }
    } else {
        context.getString(R.string.phone_rec_distance_miles, distanceMeters / 1_609.344)
    }

internal fun gpsPresetLabel(
    context: Context,
    profile: String,
): String =
    when (profile) {
        RecordingSettings.GPS_USAGE_PROFILE_CUSTOM -> context.getString(R.string.phone_rec_gps_custom)
        RecordingSettings.GPS_USAGE_PROFILE_BEST_TRACE -> context.getString(R.string.phone_rec_gps_best_trace)
        RecordingSettings.GPS_USAGE_PROFILE_LONG_BATTERY -> context.getString(R.string.phone_rec_gps_long_battery)
        else -> context.getString(R.string.phone_rec_gps_balanced)
    }

internal fun smoothingLabel(
    context: Context,
    mode: String,
): String =
    when (mode) {
        RecordingSettings.RECORDING_TRACK_SMOOTHING_OFF -> context.getString(R.string.phone_rec_smoothing_off)
        RecordingSettings.RECORDING_TRACK_SMOOTHING_STRONG -> context.getString(R.string.phone_rec_smoothing_strong)
        else -> context.getString(R.string.phone_rec_smoothing_adaptive)
    }

internal fun elevationLabel(
    context: Context,
    source: String,
): String =
    when (source) {
        RecordingSettings.RECORDING_ELEVATION_SOURCE_DEM -> context.getString(R.string.phone_rec_elevation_dem)
        RecordingSettings.RECORDING_ELEVATION_SOURCE_GPS -> context.getString(R.string.phone_rec_elevation_gps)
        RecordingSettings.RECORDING_SOURCE_DISABLED -> context.getString(R.string.phone_rec_elevation_off)
        else -> context.getString(R.string.phone_rec_elevation_auto)
    }

internal fun profileLabel(
    context: Context,
    profile: PhoneActivityProfile,
): String =
    context.getString(
        if (profile == PhoneActivityProfile.BIKE) R.string.phone_rec_profile_bike else R.string.phone_rec_profile_hike,
    )

internal fun vibrationLabel(
    context: Context,
    settings: PhoneRecordingSettings,
    isMetric: Boolean,
): String =
    when {
        settings.progressVibrationMode == RecordingSettings.RECORDING_PROGRESS_VIBRATION_OFF ->
            context.getString(R.string.phone_rec_vibration_off)
        settings.progressVibrationMode.startsWith("DISTANCE_") ->
            context.getString(
                R.string.phone_rec_vibration_every_distance,
                formatRecordingDistance(context, settings.progressDistanceMeters.toDouble(), isMetric),
            )
        else -> context.getString(R.string.phone_rec_vibration_every_minutes, settings.progressTimeMinutes)
    }
