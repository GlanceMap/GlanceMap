package com.glancemap.glancemapwearos.core.service.location.config

import kotlin.math.abs

internal const val NOTIFICATION_ID = 1
internal const val CHANNEL_ID = "LocationServiceChannel"
internal const val TELEMETRY_TAG = "LocTelemetry"
internal const val TELEMETRY_SUMMARY_INTERVAL_MS = 15_000L
internal const val ENERGY_SAMPLE_INTERVAL_MS = 60_000L

internal const val JITTER_THRESHOLD_MOVING = 2.0f
internal const val JITTER_THRESHOLD_STATIONARY = 8.0f
internal const val ACCURACY_THRESHOLD = 25.0f

internal const val MOVEMENT_HISTORY_DURATION = 35_000L

internal const val MIN_USER_INTERVAL_MS = 1_000L
internal const val MAX_USER_INTERVAL_MS = 120_000L
internal const val FOREGROUND_MIN_DISTANCE_M = 1f
internal const val BACKGROUND_MIN_DISTANCE_M = 5f

// Keep auto-resume responsive without spending the normal REC cadence while stationary.
internal const val AUTO_PAUSE_GPS_INTERVAL_MS = 5_000L
internal const val AUTO_PAUSE_PROLONGED_GPS_INTERVAL_MS = 12_000L
internal const val AUTO_PAUSE_PROLONGED_AFTER_MS = 60_000L
internal const val AUTO_PAUSE_MIN_DISTANCE_M = 5f
internal const val SCREEN_OFF_RECORDING_MAX_BATCH_DELAY_MS = 15_000L

internal const val HIGH_ACCURACY_BURST_DURATION = 8_000L
internal const val HIGH_ACCURACY_BURST_INITIAL_DURATION = 4_000L
internal const val HIGH_ACCURACY_BURST_INTERVAL = 1_000L
internal const val HIGH_ACCURACY_BURST_EARLY_STOP_ACCURACY_M = 20f
internal const val WAKE_BURST_START_DEBOUNCE_MS = 350L
internal const val BIND_CACHED_FIX_MAX_ACCURACY_M = 35f
internal const val BIND_CACHED_FIX_MAX_ACCURACY_COARSE_M = 80f
internal const val BIND_CACHED_FIX_MIN_MAX_AGE_MS = 6_000L
internal const val BIND_CACHED_FIX_MAX_MAX_AGE_MS = 30_000L

internal const val FINE_FIX_MAX_ACCURACY_M = ACCURACY_THRESHOLD * 2f
internal const val COARSE_FIX_MAX_ACCURACY_M = 120f
internal const val FIX_MAX_AGE_FINE_MAX_MS = 60_000L
internal const val FIX_MAX_AGE_COARSE_MAX_MS = 120_000L
internal const val WATCH_GPS_MAX_ACCEPTED_ACCURACY_M = 130f
internal const val WATCH_GPS_BURST_EARLY_STOP_ACCURACY_M = WATCH_GPS_MAX_ACCEPTED_ACCURACY_M
internal const val WATCH_GPS_AUTO_FALLBACK_INTERACTIVE_MAX_ACCURACY_M = 65f
internal const val WATCH_GPS_DEGRADED_ACCURACY_M = 100f
internal const val WATCH_GPS_DEGRADED_STREAK_THRESHOLD = 4
internal const val WATCH_GPS_ACCURACY_FLOOR_M = 125f
internal const val WATCH_GPS_ACCURACY_FLOOR_TOLERANCE_M = 3f
internal const val WATCH_GPS_EFFECTIVE_ACCURACY_M = 18f

internal fun isKnownWatchGpsAccuracyFloor(accuracyMeters: Float?): Boolean =
    accuracyMeters?.let { accuracy ->
        accuracy.isFinite() &&
            abs(accuracy - WATCH_GPS_ACCURACY_FLOOR_M) <= WATCH_GPS_ACCURACY_FLOOR_TOLERANCE_M
    } == true

internal fun resolveEffectiveWatchGpsAccuracyMeters(
    rawAccuracyMeters: Float?,
    watchGpsActive: Boolean,
    watchGpsDegraded: Boolean = false,
): Float? =
    if (
        watchGpsActive &&
        !watchGpsDegraded &&
        isKnownWatchGpsAccuracyFloor(rawAccuracyMeters)
    ) {
        WATCH_GPS_EFFECTIVE_ACCURACY_M
    } else {
        rawAccuracyMeters
    }

internal const val IMMEDIATE_COOLDOWN_MS = 2_500L
