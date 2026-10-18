package com.glancemap.glancemapcompanionapp.livetracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class LiveTrackingLocationPriority {
    HIGH_ACCURACY,
    BALANCED_POWER,
}

internal data class LiveTrackingLocationRequest(
    val priority: LiveTrackingLocationPriority,
    val intervalMillis: Long,
    val minUpdateDistanceMeters: Float,
)

internal data class LiveTrackingLocationSample(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeMillis: Long,
)

internal object LiveTrackingLocationPolicySettings {
    const val MILLIS_PER_SECOND = 1_000L

    // Keep the two shortest presets suitable for emergency/high-frequency sharing.
    const val MANUAL_HIGH_ACCURACY_MAX_INTERVAL_SECONDS = 30

    // Ten minutes avoids downgrading during short trail pauses and matches the default no-movement alert.
    const val STATIONARY_AFTER_MILLIS = 10 * 60 * MILLIS_PER_SECOND

    // Two minutes caps movement-resume detection delay while reducing the default 60s request rate.
    const val STATIONARY_MIN_INTERVAL_SECONDS = 120

    // 25m exceeds common stationary GNSS jitter while remaining small relative to hiking movement.
    const val MIN_MEANINGFUL_MOVEMENT_METERS = 25.0

    const val MIN_INTERVAL_SECONDS = 15
    const val MAX_INTERVAL_SECONDS = 600

    // Mean Earth radius converts the location fixes to distance for the 25m movement threshold.
    const val EARTH_RADIUS_METERS = 6_371_000.0
}

/** Keeps the configured cadence while moving and downgrades only after a sustained stop. */
internal class LiveTrackingLocationPolicy {
    private var moving = true
    private var lastMeaningfulFix: LiveTrackingLocationSample? = null
    private var lastMeaningfulMovementMillis: Long? = null

    fun start(updateIntervalSeconds: Int): LiveTrackingLocationRequest {
        moving = true
        lastMeaningfulFix = null
        lastMeaningfulMovementMillis = null
        return requestFor(updateIntervalSeconds)
    }

    fun observe(
        sample: LiveTrackingLocationSample,
        updateIntervalSeconds: Int,
    ): LiveTrackingLocationRequest {
        val intervalSeconds =
            updateIntervalSeconds.coerceIn(
                LiveTrackingLocationPolicySettings.MIN_INTERVAL_SECONDS,
                LiveTrackingLocationPolicySettings.MAX_INTERVAL_SECONDS,
            )
        val previousFix = lastMeaningfulFix

        if (intervalSeconds <= LiveTrackingLocationPolicySettings.MANUAL_HIGH_ACCURACY_MAX_INTERVAL_SECONDS) {
            moving = true
            lastMeaningfulFix = sample
            lastMeaningfulMovementMillis = sample.elapsedRealtimeMillis
        } else if (previousFix == null) {
            lastMeaningfulFix = sample
            lastMeaningfulMovementMillis = sample.elapsedRealtimeMillis
        } else if (
            distanceMeters(previousFix, sample) >= LiveTrackingLocationPolicySettings.MIN_MEANINGFUL_MOVEMENT_METERS
        ) {
            moving = true
            lastMeaningfulFix = sample
            lastMeaningfulMovementMillis = sample.elapsedRealtimeMillis
        } else {
            val lastMovement = lastMeaningfulMovementMillis ?: sample.elapsedRealtimeMillis
            if (
                sample.elapsedRealtimeMillis - lastMovement >=
                LiveTrackingLocationPolicySettings.STATIONARY_AFTER_MILLIS
            ) {
                moving = false
            }
        }

        return requestFor(intervalSeconds)
    }

    fun reset() {
        moving = true
        lastMeaningfulFix = null
        lastMeaningfulMovementMillis = null
    }

    private fun requestFor(updateIntervalSeconds: Int): LiveTrackingLocationRequest {
        val intervalSeconds =
            updateIntervalSeconds.coerceIn(
                LiveTrackingLocationPolicySettings.MIN_INTERVAL_SECONDS,
                LiveTrackingLocationPolicySettings.MAX_INTERVAL_SECONDS,
            )
        return if (moving) {
            LiveTrackingLocationRequest(
                priority = LiveTrackingLocationPriority.HIGH_ACCURACY,
                intervalMillis = intervalSeconds * LiveTrackingLocationPolicySettings.MILLIS_PER_SECOND,
                minUpdateDistanceMeters = 0f,
            )
        } else {
            LiveTrackingLocationRequest(
                priority = LiveTrackingLocationPriority.BALANCED_POWER,
                intervalMillis =
                    maxOf(intervalSeconds, LiveTrackingLocationPolicySettings.STATIONARY_MIN_INTERVAL_SECONDS) *
                        LiveTrackingLocationPolicySettings.MILLIS_PER_SECOND,
                minUpdateDistanceMeters =
                    LiveTrackingLocationPolicySettings.MIN_MEANINGFUL_MOVEMENT_METERS.toFloat(),
            )
        }
    }

    private fun distanceMeters(
        first: LiveTrackingLocationSample,
        second: LiveTrackingLocationSample,
    ): Double {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val haversine =
            sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                cos(firstLatitude) * cos(secondLatitude) *
                sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * LiveTrackingLocationPolicySettings.EARTH_RADIUS_METERS *
            asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }
}

/** Owns one location subscription and applies a new request only when the profile changes. */
internal class LiveTrackingLocationUpdates(
    private val requestLocationUpdates: (LiveTrackingLocationRequest) -> Unit,
    private val removeLocationUpdates: () -> Unit,
) {
    private val policy = LiveTrackingLocationPolicy()
    private var activeRequest: LiveTrackingLocationRequest? = null

    @Synchronized
    fun start(updateIntervalSeconds: Int) {
        if (activeRequest != null) return
        policy.start(updateIntervalSeconds).applyIfChanged()
    }

    @Synchronized
    fun onLocation(
        sample: LiveTrackingLocationSample,
        updateIntervalSeconds: Int,
    ) {
        if (activeRequest == null) return
        policy.observe(sample, updateIntervalSeconds).applyIfChanged()
    }

    @Synchronized
    fun stop() {
        if (activeRequest == null) return
        activeRequest = null
        policy.reset()
        removeLocationUpdates()
    }

    private fun LiveTrackingLocationRequest.applyIfChanged() {
        if (this == activeRequest) return
        requestLocationUpdates(this)
        activeRequest = this
    }
}
