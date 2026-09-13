package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceSession
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceTuning
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import org.mapsforge.core.model.LatLong

/**
 * Memoizes only route geometry work. Per-fix processing must stay outside this cache.
 */
internal class NavigateGuidanceGeometryCache {
    private var primaryKey: PrimaryKey? = null
    private var primaryValue: TurnByTurnGuidanceState? = null
    private var nearestKey: LocationKey? = null
    private var nearestValue: LatLong? = null
    private var nearestValueComputed = false

    fun primaryState(
        session: GpxGuidanceSession?,
        currentLocation: LatLong?,
        tuning: GpxGuidanceTuning,
        previousDistanceFromStartMeters: Double?,
        compute: () -> TurnByTurnGuidanceState,
    ): TurnByTurnGuidanceState {
        val key =
            PrimaryKey(
                session = session,
                latitude = currentLocation?.latitude,
                longitude = currentLocation?.longitude,
                tuning = tuning,
                previousDistanceFromStartMeters = previousDistanceFromStartMeters,
            )
        if (key == primaryKey) {
            return checkNotNull(primaryValue)
        }

        return compute().also { value ->
            primaryKey = key
            primaryValue = value
        }
    }

    fun nearestRoutePoint(
        session: GpxGuidanceSession?,
        currentLocation: LatLong?,
        compute: () -> LatLong?,
    ): LatLong? {
        val key =
            LocationKey(
                session = session,
                latitude = currentLocation?.latitude,
                longitude = currentLocation?.longitude,
            )
        if (nearestValueComputed && key == nearestKey) {
            return nearestValue
        }

        return compute().also { value ->
            nearestKey = key
            nearestValue = value
            nearestValueComputed = true
        }
    }

    private data class PrimaryKey(
        val session: GpxGuidanceSession?,
        val latitude: Double?,
        val longitude: Double?,
        val tuning: GpxGuidanceTuning,
        val previousDistanceFromStartMeters: Double?,
    )

    private data class LocationKey(
        val session: GpxGuidanceSession?,
        val latitude: Double?,
        val longitude: Double?,
    )
}
