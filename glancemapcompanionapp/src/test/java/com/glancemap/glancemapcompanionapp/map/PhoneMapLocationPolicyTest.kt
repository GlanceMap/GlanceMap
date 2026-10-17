package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
