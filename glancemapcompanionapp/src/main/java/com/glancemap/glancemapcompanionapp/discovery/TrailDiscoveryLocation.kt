package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.glancemapcompanionapp.map.PhoneMapLocation
import com.glancemap.trailcore.geo.GeoPoint

/** A discovery search must not mistake a saved foreground-map fix for the current location. */
internal fun discoveryCurrentLocationQuery(
    location: PhoneMapLocation?,
    nowElapsedMillis: Long,
): TrailDiscoveryQuery? {
    if (location == null || nowElapsedMillis - location.fixElapsedRealtimeMillis !in 0..120000L) return null
    return runCatching { TrailDiscoveryQuery(GeoPoint(location.latitude, location.longitude)) }.getOrNull()
}
