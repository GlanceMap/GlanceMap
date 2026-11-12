package com.glancemap.glancemapwearos.core.service.location.policy

import android.location.Location
import com.glancemap.glancemapwearos.core.service.location.config.isKnownWatchGpsAccuracyFloor
import com.glancemap.glancemapwearos.core.service.location.model.resolveLocationTimingProfile
import kotlin.math.max

internal data class FixAcceptancePolicy(
    val maxAgeMs: Long,
    val maxAccuracyM: Float,
    /**
     * When set, a fix reporting the known fixed watch-GPS accuracy (125 m) is accepted up to
     * this limit. Used for fused fixes while the phone is not confirmed connected: Wear OS fused
     * location then comes from the watch's own GNSS chip and reports the same fixed value.
     */
    val knownWatchGpsFloorMaxAccuracyM: Float? = null,
) {
    fun rejectsAccuracy(accuracyM: Float): Boolean {
        val floorLimitM = knownWatchGpsFloorMaxAccuracyM
        val acceptedAsWatchGpsFloor =
            floorLimitM != null && isKnownWatchGpsAccuracyFloor(accuracyM) && accuracyM <= floorLimitM
        return !accuracyM.isFinite() || (accuracyM > maxAccuracyM && !acceptedAsWatchGpsFloor)
    }
}

internal object LocationFixPolicy {
    fun resolveAcceptancePolicy(
        hasFinePermission: Boolean,
        hasCoarsePermission: Boolean,
        expectedIntervalMs: Long,
        minMaxAgeMs: Long,
        fineMaxAgeMs: Long,
        coarseMaxAgeMs: Long,
        fineMaxAccuracyM: Float,
        coarseMaxAccuracyM: Float,
    ): FixAcceptancePolicy {
        val intervalMs = expectedIntervalMs.coerceAtLeast(1_000L)
        val maxAgeUpperBound = if (hasFinePermission) fineMaxAgeMs else coarseMaxAgeMs
        val maxAgeMs = (intervalMs * 2L).coerceIn(minMaxAgeMs, maxAgeUpperBound)
        val maxAccuracyM =
            if (hasFinePermission) {
                fineMaxAccuracyM
            } else if (hasCoarsePermission) {
                coarseMaxAccuracyM
            } else {
                fineMaxAccuracyM
            }

        return FixAcceptancePolicy(
            maxAgeMs = maxAgeMs,
            maxAccuracyM = maxAccuracyM,
        )
    }

    fun strictFreshFixMaxAgeMs(
        gpsIntervalMs: Long,
        minFreshAgeMs: Long = 0L,
        intervalMultiplier: Long = 0L,
    ): Long {
        val derivedMaxAgeMs = resolveLocationTimingProfile(gpsIntervalMs).strictFreshFixMaxAgeMs
        if (minFreshAgeMs <= 0L && intervalMultiplier <= 0L) {
            return derivedMaxAgeMs
        }
        val safeIntervalMs = gpsIntervalMs.coerceAtLeast(1_000L)
        val safeMultiplier = intervalMultiplier.coerceAtLeast(1L)
        val safeMinFreshAgeMs = minFreshAgeMs.coerceAtLeast(1_000L)
        return max(derivedMaxAgeMs, max(safeMinFreshAgeMs, safeIntervalMs * safeMultiplier))
    }

    fun adaptAcceptanceForSourceMode(
        policy: FixAcceptancePolicy,
        sourceMode: LocationSourceMode,
        watchGpsMaxAccuracyM: Float,
    ): FixAcceptancePolicy {
        if (sourceMode != LocationSourceMode.WATCH_GPS) return policy
        val relaxedMaxAccuracyM = max(policy.maxAccuracyM, watchGpsMaxAccuracyM)
        return policy.copy(maxAccuracyM = relaxedMaxAccuracyM)
    }

    /**
     * Placeholder accuracies from the watch chip are accepted above the normal limit: always for
     * watch GPS, and for fused fixes while the phone is not confirmed connected (fused location
     * then comes from the same chip).
     */
    fun allowKnownWatchGpsFloor(
        policy: FixAcceptancePolicy,
        sourceMode: LocationSourceMode,
        phoneConnected: Boolean?,
        floorMaxAccuracyM: Float,
    ): FixAcceptancePolicy {
        val allowed =
            sourceMode == LocationSourceMode.WATCH_GPS ||
                (sourceMode == LocationSourceMode.AUTO_FUSED && phoneConnected != true)
        return if (allowed) policy.copy(knownWatchGpsFloorMaxAccuracyM = floorMaxAccuracyM) else policy
    }

    fun resolveWatchGpsAcceptanceAccuracyM(
        sourceMode: LocationSourceMode,
        watchGpsOnly: Boolean,
        runtimeMode: LocationRuntimeMode?,
        watchGpsMaxAccuracyM: Float,
        watchGpsAutoFallbackInteractiveMaxAccuracyM: Float,
    ): Float {
        if (sourceMode != LocationSourceMode.WATCH_GPS) return watchGpsMaxAccuracyM
        if (watchGpsOnly || runtimeMode == LocationRuntimeMode.PASSIVE) return watchGpsMaxAccuracyM
        return max(watchGpsMaxAccuracyM, watchGpsAutoFallbackInteractiveMaxAccuracyM)
    }

    fun locationAgeMs(
        location: Location,
        nowElapsedMs: Long,
    ): Long {
        if (location.elapsedRealtimeNanos > 0L) {
            val fixElapsedMs = location.elapsedRealtimeNanos / 1_000_000L
            if (fixElapsedMs > nowElapsedMs + MAX_FUTURE_TIMESTAMP_SKEW_MS) {
                return Long.MAX_VALUE
            }
            return (nowElapsedMs - fixElapsedMs).coerceAtLeast(0L)
        }
        if (location.time > 0L) {
            val nowWallClockMs = System.currentTimeMillis()
            if (location.time > nowWallClockMs + MAX_FUTURE_TIMESTAMP_SKEW_MS) {
                return Long.MAX_VALUE
            }
            return (nowWallClockMs - location.time).coerceAtLeast(0L)
        }
        return Long.MAX_VALUE
    }

    fun hasValidCoordinates(location: Location): Boolean =
        hasValidCoordinates(
            latitude = location.latitude,
            longitude = location.longitude,
        )

    fun hasValidCoordinates(
        latitude: Double,
        longitude: Double,
    ): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in MIN_LATITUDE_DEGREES..MAX_LATITUDE_DEGREES &&
            longitude in MIN_LONGITUDE_DEGREES..MAX_LONGITUDE_DEGREES
}

private const val MAX_FUTURE_TIMESTAMP_SKEW_MS = 2_000L
private const val MIN_LATITUDE_DEGREES = -90.0
private const val MAX_LATITUDE_DEGREES = 90.0
private const val MIN_LONGITUDE_DEGREES = -180.0
private const val MAX_LONGITUDE_DEGREES = 180.0
