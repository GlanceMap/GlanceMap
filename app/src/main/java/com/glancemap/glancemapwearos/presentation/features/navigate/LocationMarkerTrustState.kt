package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning

internal enum class LocationMarkerTrustState {
    NO_POSITION,
    CURRENT,
    HISTORICAL,
}

internal fun <T> navigationMarkerBitmapForTrustState(
    trustState: LocationMarkerTrustState,
    currentBitmap: T,
    historicalBitmap: T,
): T =
    if (trustState == LocationMarkerTrustState.CURRENT) {
        currentBitmap
    } else {
        historicalBitmap
    }

internal data class LocationMarkerTrustPolicy(
    val nowElapsedRealtimeMs: Long,
    val currentSourceEpoch: Long,
    val requiresFreshLiveFixAfterSourceChange: Boolean,
    val freshnessMaxAgeMs: Long,
    val hasHardEnvironmentRestriction: Boolean = false,
)

internal fun resolveLocationMarkerTrustState(
    retainedLocationAnchor: RetainedLocationAnchor?,
    policy: LocationMarkerTrustPolicy,
): LocationMarkerTrustState {
    val anchor = retainedLocationAnchor
    val sourceChanged =
        anchor != null &&
            anchor.sourceEpoch > 0L &&
            policy.currentSourceEpoch > 0L &&
            anchor.sourceEpoch != policy.currentSourceEpoch
    val acceptedFixIsFresh =
        anchor != null &&
            anchor.isAcceptedFix &&
            isAcceptedLocationFixFreshForMarker(
                acceptedFixElapsedRealtimeMs = anchor.fixElapsedRealtimeMs,
                acceptedFixAccuracyM = anchor.accuracyM,
                nowElapsedRealtimeMs = policy.nowElapsedRealtimeMs,
                requiresFreshLiveFixAfterSourceChange = policy.requiresFreshLiveFixAfterSourceChange,
                freshnessMaxAgeMs = policy.freshnessMaxAgeMs,
            )
    val isHistorical =
        anchor == null ||
            !acceptedFixIsFresh ||
            sourceChanged ||
            policy.hasHardEnvironmentRestriction

    return when {
        anchor == null -> LocationMarkerTrustState.NO_POSITION
        isHistorical -> LocationMarkerTrustState.HISTORICAL
        else -> LocationMarkerTrustState.CURRENT
    }
}

// Keep the call shape convenient for focused pure tests; production code uses the policy object.
@Suppress("LongParameterList")
internal fun resolveLocationMarkerTrustState(
    retainedLocationAnchor: RetainedLocationAnchor?,
    nowElapsedRealtimeMs: Long,
    currentSourceEpoch: Long,
    requiresFreshLiveFixAfterSourceChange: Boolean,
    freshnessMaxAgeMs: Long,
    hasHardEnvironmentRestriction: Boolean = false,
): LocationMarkerTrustState =
    resolveLocationMarkerTrustState(
        retainedLocationAnchor = retainedLocationAnchor,
        policy =
            LocationMarkerTrustPolicy(
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                currentSourceEpoch = currentSourceEpoch,
                requiresFreshLiveFixAfterSourceChange = requiresFreshLiveFixAfterSourceChange,
                freshnessMaxAgeMs = freshnessMaxAgeMs,
                hasHardEnvironmentRestriction = hasHardEnvironmentRestriction,
            ),
    )

internal fun isAcceptedLocationFixFreshForMarker(
    acceptedFixElapsedRealtimeMs: Long,
    acceptedFixAccuracyM: Float,
    nowElapsedRealtimeMs: Long,
    requiresFreshLiveFixAfterSourceChange: Boolean,
    freshnessMaxAgeMs: Long,
): Boolean =
    isGpsFixFreshBeforeIndicatorEscalation(
        lastFixAtElapsedMs = acceptedFixElapsedRealtimeMs,
        accuracyM = acceptedFixAccuracyM,
        requiresFreshLiveFixAfterSourceChange = requiresFreshLiveFixAfterSourceChange,
        nowElapsedMs = nowElapsedRealtimeMs,
        staleThresholdMs = freshnessMaxAgeMs,
    )

internal fun isGpsFixFreshBeforeIndicatorEscalation(
    lastFixAtElapsedMs: Long,
    accuracyM: Float,
    requiresFreshLiveFixAfterSourceChange: Boolean,
    nowElapsedMs: Long,
    staleThresholdMs: Long,
): Boolean {
    val ageMs =
        if (lastFixAtElapsedMs > 0L) {
            (nowElapsedMs - lastFixAtElapsedMs).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
    return lastFixAtElapsedMs > 0L &&
        ageMs <= staleThresholdMs &&
        accuracyM.isFinite() &&
        !requiresFreshLiveFixAfterSourceChange
}

internal fun hasHardLocationEnvironmentRestriction(
    environmentWarning: GpsEnvironmentWarning,
): Boolean = environmentWarning == GpsEnvironmentWarning.LOCATION_SETTINGS_UNSATISFIED
