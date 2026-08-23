package com.glancemap.glancemapwearos.core.service.location.model

import com.glancemap.glancemapwearos.core.service.location.config.resolveEffectiveWatchGpsAccuracyMeters

data class GpsSignalSnapshot(
    val lastFixElapsedRealtimeMs: Long = 0L,
    val lastFixAgeMs: Long = Long.MAX_VALUE,
    val lastFixAccuracyM: Float = Float.POSITIVE_INFINITY,
    val lastFixFresh: Boolean = false,
    val lastFixFreshMaxAgeMs: Long = 0L,
    val isLocationAvailable: Boolean = true,
    val unavailableSinceElapsedMs: Long = 0L,
    val watchGpsOnlyActive: Boolean = false,
    val watchGpsDegraded: Boolean = false,
    val watchGpsDegradedFixStreak: Int = 0,
    val watchGpsDegradedSinceElapsedMs: Long = 0L,
    val environmentWarning: GpsEnvironmentWarning = GpsEnvironmentWarning.NONE,
    val environmentWarningSinceElapsedMs: Long = 0L,
    val activeSourceModeValue: String? = null,
    val sourceAcquisitionStartedElapsedMs: Long = 0L,
    val sourceEpoch: Long = 0L,
    val requiresFreshLiveFixAfterSourceChange: Boolean = false,
)

internal fun GpsSignalSnapshot.effectiveAccuracyMeters(): Float =
    resolveEffectiveWatchGpsAccuracyMeters(
        rawAccuracyMeters = lastFixAccuracyM,
        watchGpsActive = watchGpsOnlyActive,
        watchGpsDegraded = watchGpsDegraded,
    ) ?: lastFixAccuracyM
