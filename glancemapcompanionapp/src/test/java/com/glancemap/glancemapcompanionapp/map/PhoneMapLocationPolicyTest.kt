package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapLocationPolicyTest {
    @Test
    fun resumedVisibleMapFollowingUserUsesHighAccuracyProfile() {
        assertEquals(
            PhoneMapLocationRequestProfile.FOLLOW,
            selectPhoneMapLocationRequestProfile(
                mapActive = true,
                onlineMap = true,
                hasLocationPermission = true,
                recordingServiceOwnsLocation = false,
                followMode = PhoneMapFollowMode.FOLLOW_LOCATION,
            ),
        )
    }

    @Test
    fun visibleMapBrowsingUsesLowerPowerProfile() {
        assertEquals(
            PhoneMapLocationRequestProfile.BROWSE,
            selectPhoneMapLocationRequestProfile(
                mapActive = true,
                onlineMap = true,
                hasLocationPermission = true,
                recordingServiceOwnsLocation = false,
                followMode = PhoneMapFollowMode.FREE,
            ),
        )
    }

    @Test
    fun hiddenOrPausedMapStopsAndReturningMapCanRestart() {
        val visibleProfile = profile(mapActive = true)
        val hiddenProfile = profile(mapActive = false)
        val pausedProfile = profile(mapActive = false)
        val returnedProfile = profile(mapActive = true)

        assertEquals(PhoneMapLocationRequestProfile.FOLLOW, visibleProfile)
        assertNull(hiddenProfile)
        assertNull(pausedProfile)
        assertEquals(PhoneMapLocationRequestProfile.FOLLOW, returnedProfile)
    }

    @Test
    fun recordingOrActiveGuidanceLeavesLocationToServiceOwner() {
        assertNull(profile(recordingServiceOwnsLocation = true))
        assertEquals(PhoneMapLocationRequestProfile.FOLLOW, profile(recordingServiceOwnsLocation = false))
    }

    @Test
    fun offlineMapDoesNotStartForegroundMapLocation() {
        assertNull(profile(onlineMap = false))
    }

    @Test
    fun markerFreshnessMatchesWatchThreeIntervalWindowAndHistoricalFallback() {
        assertEquals(10_000L, phoneMapMarkerFreshnessMaxAgeMillis(1_000L))
        assertEquals(10_000L, phoneMapMarkerFreshnessMaxAgeMillis(2_000L))
        assertEquals(30_000L, phoneMapMarkerFreshnessMaxAgeMillis(10_000L))
        assertEquals(30_000L, phoneMapMarkerFreshnessMaxAgeMillis(20_000L))

        val location = PhoneMapLocation(46.0, 7.0, 5f, fixElapsedRealtimeMillis = 90_000L)
        assertTrue(isPhoneMapLocationFreshForMarker(location, 100_000L, expectedIntervalMillis = 2_000L))
        assertFalse(isPhoneMapLocationFreshForMarker(location, 100_001L, expectedIntervalMillis = 2_000L))
        assertFalse(isPhoneMapLocationFreshForMarker(location.copy(accuracyMeters = null), 95_000L, 2_000L))
        assertFalse(isPhoneMapLocationFreshForMarker(location.copy(fixElapsedRealtimeMillis = 0L), 95_000L, 2_000L))
    }

    private fun profile(
        mapActive: Boolean = true,
        onlineMap: Boolean = true,
        hasLocationPermission: Boolean = true,
        recordingServiceOwnsLocation: Boolean = false,
    ): PhoneMapLocationRequestProfile? =
        selectPhoneMapLocationRequestProfile(
            mapActive = mapActive,
            onlineMap = onlineMap,
            hasLocationPermission = hasLocationPermission,
            recordingServiceOwnsLocation = recordingServiceOwnsLocation,
            followMode = PhoneMapFollowMode.FOLLOW_LOCATION,
        )
}
