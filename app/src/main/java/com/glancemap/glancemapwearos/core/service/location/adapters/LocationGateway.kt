package com.glancemap.glancemapwearos.core.service.location.adapters

import android.location.Location
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode

internal data class CurrentLocationRequestParams(
    val priority: Int,
    val maxUpdateAgeMs: Long,
    val durationMs: Long,
)

internal data class LocationUpdateRequestParams(
    val priority: Int,
    val intervalMs: Long,
    val minDistanceMeters: Float,
    val waitForAccurateLocation: Boolean,
    val maxUpdateDelayMs: Long,
    val durationMs: Long? = null,
)

internal data class LocationUpdateEvent(
    val origin: LocationSourceMode,
    val candidates: List<Location>,
    val lastCandidate: Location? = null,
    val rawCandidateCount: Int = candidates.size,
    val duplicateCandidatesDropped: Int = 0,
    val maxUpdateDelayMs: Long = 0L,
)

internal interface LocationUpdateSink {
    fun onLocationAvailability(isAvailable: Boolean) = Unit

    fun onLocations(event: LocationUpdateEvent)
}

internal interface LocationGateway {
    suspend fun getCurrentLocation(request: CurrentLocationRequestParams): Location?

    suspend fun getLastLocation(): Location?

    suspend fun requestLocationUpdates(
        request: LocationUpdateRequestParams,
        sink: LocationUpdateSink,
    )

    suspend fun removeLocationUpdates()

    suspend fun flushLocations() = Unit

    fun removeLocationUpdatesBestEffort()
}
