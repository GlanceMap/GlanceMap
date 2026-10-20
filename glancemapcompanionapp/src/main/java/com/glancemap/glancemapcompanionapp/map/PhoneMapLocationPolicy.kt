package com.glancemap.glancemapcompanionapp.map

/** Profiles for the foreground map's location puck; recording and guidance use the service stream. */
internal enum class PhoneMapLocationRequestProfile(
    val expectedIntervalMillis: Long,
) {
    /** Map browsing still refreshes the puck, but avoids a continuous GNSS-rate request. */
    BROWSE(10_000L),

    /** Following the user's position needs responsive, precise fixes for camera recentering. */
    FOLLOW(2_000L),
}

/** Match the watch marker policy: three expected intervals, bounded between ten and thirty seconds. */
internal fun phoneMapMarkerFreshnessMaxAgeMillis(expectedIntervalMillis: Long): Long {
    val interval = expectedIntervalMillis.coerceAtLeast(1_000L)
    val triple = interval.coerceAtMost(10_000L) * 3L
    return triple.coerceIn(10_000L, 30_000L)
}

internal fun isPhoneMapLocationFreshForMarker(
    location: PhoneMapLocation?,
    nowElapsedRealtimeMillis: Long,
    expectedIntervalMillis: Long,
): Boolean {
    val fix = location ?: return false
    val ageMillis =
        if (fix.fixElapsedRealtimeMillis > 0L) {
            (nowElapsedRealtimeMillis - fix.fixElapsedRealtimeMillis).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
    return fix.fixElapsedRealtimeMillis > 0L &&
        ageMillis <= phoneMapMarkerFreshnessMaxAgeMillis(expectedIntervalMillis) &&
        fix.accuracyMeters?.isFinite() == true
}

/** Returns null when the visible map should leave location acquisition to another owner or stop. */
internal fun selectPhoneMapLocationRequestProfile(
    mapActive: Boolean,
    onlineMap: Boolean,
    hasLocationPermission: Boolean,
    recordingServiceOwnsLocation: Boolean,
    followMode: PhoneMapFollowMode,
): PhoneMapLocationRequestProfile? {
    if (!mapActive || !onlineMap || !hasLocationPermission || recordingServiceOwnsLocation) return null
    return if (followMode == PhoneMapFollowMode.FOLLOW_LOCATION) {
        PhoneMapLocationRequestProfile.FOLLOW
    } else {
        PhoneMapLocationRequestProfile.BROWSE
    }
}
