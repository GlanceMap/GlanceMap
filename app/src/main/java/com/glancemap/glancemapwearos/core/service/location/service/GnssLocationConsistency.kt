package com.glancemap.glancemapwearos.core.service.location.service

import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot

/**
 * GNSS status is diagnostic-only: vendor implementations can report no satellites used in a
 * fix while the location stream is still delivering valid positions. Keep that discrepancy
 * visible without allowing it to suppress a usable location.
 */
internal enum class GnssLocationConsistency(
    val telemetryValue: String,
) {
    CONSISTENT("consistent"),
    USED_ZERO_WITH_FRESH_LOCATION("used_zero_with_fresh_location"),
    SIGNALS_WITHOUT_FRESH_LOCATION("signals_without_fresh_location"),
}

internal fun resolveGnssLocationConsistency(
    sourceMode: String,
    usedInFixCount: Int,
    signalSatelliteCount: Int,
    signalSnapshot: GpsSignalSnapshot,
): GnssLocationConsistency =
    when {
        sourceMode != "watch_gps" -> GnssLocationConsistency.CONSISTENT
        usedInFixCount <= 0 && signalSnapshot.lastFixFresh ->
            GnssLocationConsistency.USED_ZERO_WITH_FRESH_LOCATION
        signalSatelliteCount > 0 && !signalSnapshot.lastFixFresh ->
            GnssLocationConsistency.SIGNALS_WITHOUT_FRESH_LOCATION
        else -> GnssLocationConsistency.CONSISTENT
    }
