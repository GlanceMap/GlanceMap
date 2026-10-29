package com.glancemap.glancemapcompanionapp.livetracking

internal fun shouldUseSharedLocation(
    active: Boolean,
    sourceIntervalMillis: Long,
    trackingIntervalMillis: Long,
    ageMillis: Long,
    accuracyMeters: Float,
): Boolean =
    active &&
        sourceIntervalMillis in 1L..trackingIntervalMillis &&
        ageMillis in 0L..minOf(trackingIntervalMillis, sourceIntervalMillis * 2L + 2_000L) &&
        accuracyMeters.isFinite() &&
        accuracyMeters in 0f..50f
