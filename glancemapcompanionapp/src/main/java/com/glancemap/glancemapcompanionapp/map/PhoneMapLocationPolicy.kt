package com.glancemap.glancemapcompanionapp.map

/** Profiles for the foreground map's location puck; recording and guidance use the service stream. */
internal enum class PhoneMapLocationRequestProfile {
    /** Map browsing still refreshes the puck, but avoids a continuous GNSS-rate request. */
    BROWSE,

    /** Following the user's position needs responsive, precise fixes for camera recentering. */
    FOLLOW,
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
